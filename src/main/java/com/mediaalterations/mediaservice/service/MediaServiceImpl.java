package com.mediaalterations.mediaservice.service;

import com.mediaalterations.mediaservice.dto.FfmpegCmdResponse;
import com.mediaalterations.mediaservice.dto.ProcessDto;
import com.mediaalterations.mediaservice.dto.ProcessStatus;
import com.mediaalterations.mediaservice.exception.MediaProcessingException;
import com.mediaalterations.mediaservice.exception.ProcessKillException;
import com.mediaalterations.mediaservice.feignClients.MainClient;
import com.mediaalterations.mediaservice.feignClients.StorageClient;
import com.mediaalterations.mediaservice.messaging.RabbitMQProducer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.concurrent.*;

@RequiredArgsConstructor
@Slf4j
@Service
public class MediaServiceImpl implements MediaService {

    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @Value("${ffmpeg.path}")
    private String ffmpegExePath;

    @Value("${ffprobe.path}")
    private String ffprobeExePath;

    @Value("${garage.bucket.uploads}")
    private String uploadsBucket;

    @Value("${garage.bucket.downloads}")
    private String downloadsBucket;

    private final RabbitMQProducer progressProducer;
    private final MainClient mainClient;
    private final StorageClient storageClient;

    private final S3Client s3Client;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ===================== MAIN PROCESS =====================

    @Override
    public void workOnProcess(ProcessDto processDto) {

        log.info("Starting media processing. processId={}",
                processDto.id());

        FfmpegCmdResponse ffmpegCmdRes = new FfmpegCmdResponse(-1L, processDto.id().toString(), 0, "00:00:00:00.0000",
                "0 KB", ProcessStatus.WAITING);

        ArrayList<Path> tempInputs = new ArrayList<>();
        Path tempOutput = null;

        try {

            tempOutput = Files.createTempFile("output-", processDto.fileName().substring(
                    processDto.fileName().lastIndexOf('.')));
            log.info("Temporary Output Path: {}", tempOutput);
            String updatedCommand = processDto.command();

            for (Map.Entry<String, String> entry : processDto.storageInputDetails().entrySet()) {

                String storageId = entry.getKey();
                String storagePath = entry.getValue();

                log.info("Storage ID: {}, Storage Path: {}", storageId, storagePath);

                Path tempInput = downloadFromGarage(uploadsBucket, downloadsBucket, storagePath);
                log.info("Temporary Input Path: {}", tempInput);

                tempInputs.add(tempInput);

                // Replace storage path with temp path
                updatedCommand = updatedCommand.replace(
                        storagePath,
                        tempInput.toString());
            }

            log.info("Transformed command with temp paths: {}", updatedCommand);

            double durationSeconds = tempInputs.stream().mapToDouble(a -> probeAndParse(a.toString())).sum();
            long totalDurationMs = (long) (durationSeconds * 1000);
            log.info("Total Duration: {}", totalDurationMs);

            List<String> command = buildCommand(
                    updatedCommand.replace(processDto.storageOutputPath(), tempOutput.toString()));

            boolean success = executeWithProgress(
                    command,
                    it -> {
                        ffmpegCmdRes.setPid(it.getPid());
                        ffmpegCmdRes.setDuration(it.getDuration());
                        ffmpegCmdRes.setProgress(it.getProgress());
                        ffmpegCmdRes.setFinalFileSize(it.getFinalFileSize());
                        ffmpegCmdRes.setStatus(ProcessStatus.PROCESSING);
                        log.info("Progress update: {}% complete, duration={}, finalFileSize={} for processId={}",
                                it.getProgress(), it.getDuration(), it.getFinalFileSize(), processDto.id());

                        progressProducer.publishFfmpegProcessProgress(ffmpegCmdRes);
                    },
                    totalDurationMs, processDto.id().toString());

            if (!success) {
                throw new MediaProcessingException("FFmpeg execution failed");
            }

            // Upload processed file back to Garage
            uploadToGarage(downloadsBucket, processDto.storageOutputPath(), tempOutput);

            log.info("FFMPEG finalDuration={}, finalFileSize={} ms for processId={}", ffmpegCmdRes.getDuration(),
                    ffmpegCmdRes.getFinalFileSize(), processDto.id());
            mainClient.updateStatusForProcess(
                    ProcessStatus.COMPLETED,
                    ffmpegCmdRes.getFinalFileSize(),
                    ffmpegCmdRes.getDuration(),
                    processDto.id().toString());

            ffmpegCmdRes.setStatus(ProcessStatus.COMPLETED);
            progressProducer.publishFfmpegProcessProgress(ffmpegCmdRes);

            // Make the Storage file downloadable
            storageClient.makeFileDownloadable(processDto.storageIdOutput());
            log.info("Processing completed successfully. processId={}", processDto.id());

        } catch (Exception ex) {

            ex.printStackTrace();

            log.error("Processing failed. processId={}, errorMessage={}, errorClass={}",
                    processDto.id(),
                    ex.getMessage(), ex);

            mainClient.updateStatusForProcess(
                    ProcessStatus.FAILED,
                    ffmpegCmdRes.getFinalFileSize(),
                    ffmpegCmdRes.getDuration(),
                    processDto.id().toString());

            ffmpegCmdRes.setStatus(ProcessStatus.FAILED);
            progressProducer.publishFfmpegProcessProgress(ffmpegCmdRes);

            throw new MediaProcessingException("Media processing failed", ex);
        } finally {
            // Clean up temp files
            tempInputs.forEach(this::deleteTempFile);
            // deleteTempFile(tempInput);
            deleteTempFile(tempOutput);
        }
    }

    // check in inputs bucket then if not found then in outputs bucket
    // since we are providing user the ability to choose from uploaded and processed
    // files
    private Path downloadFromGarage(String bucket1, String bucket2, String key) throws IOException {
        log.info("Downloading from Garage. bucket={}, key={}", bucket1, key);
        ResponseBytes<GetObjectResponse> obj = null;
        try {
            obj = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket1).key(key).build());
        } catch (Exception e) {
            log.warn("Failed to download from bucket: {}, trying bucket: {}. key={}", bucket1, bucket2, key);
            obj = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket2).key(key).build());
        }

        Path temp = Files.createTempFile("garage-input-", key.substring(key.lastIndexOf('.')));
        Files.write(temp, obj.asByteArray());
        return temp;
    }

    private void uploadToGarage(String bucket, String key, Path file) throws IOException {
        log.info("Uploading to Garage. bucket={}, key={}", bucket, key);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromFile(file));
    }

    private void deleteTempFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", path);
            }
        }
    }

    // ===================== COMMAND BUILDER =====================

    private List<String> buildCommand(String rawCommand) {

        List<String> command = new ArrayList<>();
        command.add(ffmpegExePath);

        command.addAll(Arrays.asList(rawCommand.split("\s+")));

        log.info("Executing FFmpeg command: {}", String.join(" ", command));

        return command;
    }

    // ===================== EXECUTION =====================

    public boolean executeWithProgress(
            List<String> command,
            Consumer<FfmpegCmdResponse> progressCallback,
            long totalDurationMs,
            String processId) {

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            activeProcesses.put(processId, process);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                int percent = 0;
                String finalFileSize = "0 KB";
                String finalFileDuration = "0";

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("out_time_ms=")) {
                        try {
                            long currentTimeMs = Long.parseLong(line.split("=")[1].trim()) / 1000; // Convert micro to
                                                                                                   // milli
                            if (totalDurationMs > 0) {
                                percent = (int) ((currentTimeMs * 100) / totalDurationMs);
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Could not parse out_time_ms");
                        }
                    } else if (line.startsWith("size=")) {
                        try {
                            finalFileSize = line.split("=")[1].replace("time", "").trim();
                        } catch (NumberFormatException e) {
                            log.warn("Could not parse total_size");
                        }
                    } else if (line.startsWith("out_time=")) {
                        try {
                            finalFileDuration = line.split("=")[1].trim();
                        } catch (NumberFormatException e) {
                            log.warn("Could not parse total_size");
                        }
                    } else {
                        // log everything else — this is where ffmpeg errors show up
                        log.info("FFmpeg output: {}", line);
                    }

                    progressCallback
                            .accept(new FfmpegCmdResponse(process.pid(), "", Math.min(percent, 100), finalFileDuration,
                                    finalFileSize, ProcessStatus.PROCESSING));
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new MediaProcessingException("FFmpeg timed out");
            }
            return process.exitValue() == 0;

        } catch (Exception e) {
            throw new MediaProcessingException("Failed during FFmpeg execution", e);
        } finally {
            activeProcesses.remove(processId);
        }
    }

    // ===================== FFPROBE =====================

    public String probe(String inputPath) {

        log.debug("Running ffprobe for inputPath={}", inputPath);

        ProcessBuilder pb = new ProcessBuilder(
                ffprobeExePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                inputPath);

        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            boolean finished = process.waitFor(1, TimeUnit.MINUTES);

            if (!finished) {
                process.destroyForcibly();
                throw new MediaProcessingException("ffprobe timed out");
            }

            if (process.exitValue() != 0) {
                throw new MediaProcessingException(
                        "ffprobe exited with code " + process.exitValue());
            }

            return output.toString();

        } catch (Exception e) {
            throw new MediaProcessingException("Failed to execute ffprobe", e);
        }
    }

    // ===================== PARSE =====================

    public Double probeAndParse(String inputPath) {

        String output = probe(inputPath);

        try {
            JsonNode root = MAPPER.readTree(output);

            double duration = root.path("format")
                    .path("duration")
                    .asDouble(0.0);

            if (duration <= 0) {
                throw new MediaProcessingException("Invalid media duration detected");
            }

            log.debug("Media duration (seconds): {}", duration);

            return duration;

        } catch (Exception e) {
            throw new MediaProcessingException("Failed to parse ffprobe output", e);
        }
    }

    public String killProcess(String processId) {
        try {
            Process process = activeProcesses.get(processId);
            if (process == null) {
                log.warn("Process with processId: {} is not found", processId);
                throw new ProcessKillException(
                        "No process found with processId:{} or it is already finished" + processId);
            } else {
                log.info("Initiated killing of process with processId:{}", processId);
                process.destroyForcibly();
                mainClient.updateStatusForProcess(
                        ProcessStatus.CANCELLED,
                        "0 KB",
                        "00:00:00:00.0000",
                        processId);
                log.info("Process killed and FAILED status updated, processId:{}", processId);
                return "Process killed successfully";
            }
        } catch (Exception e) {
            log.error("Error: {} PID: {}", e.getMessage(), processId);
            throw new ProcessKillException("Failed to kill process", e);
        }
    }
}
