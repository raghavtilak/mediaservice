package com.mediaalterations.mediaservice.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaalterations.mediaservice.dto.FfmpegCmdResponse;
import com.mediaalterations.mediaservice.dto.ProcessDto;
import com.mediaalterations.mediaservice.dto.ProcessStatus;
import com.mediaalterations.mediaservice.exception.MediaProcessingException;
import com.mediaalterations.mediaservice.feignClients.MainClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.*;

@RequiredArgsConstructor
@Slf4j
@Service
public class MediaServiceImpl implements MediaService {

    @Value("${ffmpeg.path}")
    private String ffmpegExePath;

    @Value("${ffprobe.path}")
    private String ffprobeExePath;

    private final MainClient mainClient;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ===================== MAIN PROCESS =====================

    @Override
    public void extractAudioFromVideo(ProcessDto processDto) {

        log.info("Starting media processing. processId={}, inputPath={}",
                processDto.id(), processDto.storageInputPath());

        FfmpegCmdResponse ffmpegCmdRes = new FfmpegCmdResponse(0,"0 KB");

        try {

            double durationSeconds = probeAndParse(processDto.storageInputPath());
            long totalDurationMs = (long) (durationSeconds * 1000);

            List<String> command = buildCommand(processDto.command());

            boolean success = executeWithProgress(
                    command,
                    it -> {
                        log.debug("Progress {}% for processId={}", it.getProgress(), processDto.id());
                        ffmpegCmdRes.setDuration(it.getDuration());
                        ffmpegCmdRes.setProgress(it.getProgress());
                    },
                    totalDurationMs
            );

            if (!success) {
                throw new MediaProcessingException("FFmpeg execution failed");
            }

            mainClient.updateStatusForProcess(
                    ProcessStatus.COMPLETED,
                    ffmpegCmdRes.getDuration(),
                    processDto.id().toString()
            );

            log.info("Processing completed successfully. processId={}", processDto.id());

        } catch (Exception ex) {

            log.error("Processing failed. processId={}", processDto.id(), ex);

            mainClient.updateStatusForProcess(
                    ProcessStatus.FAILED,
                    ffmpegCmdRes.getDuration(),
                    processDto.id().toString()
            );

            throw new MediaProcessingException("Media processing failed", ex);
        }
    }

    // ===================== COMMAND BUILDER =====================

    private List<String> buildCommand(String rawCommand) {

        List<String> command = new ArrayList<>();
        command.add(ffmpegExePath);

        command.addAll(Arrays.asList(rawCommand.split(" ")));

        log.info("Executing FFmpeg command: {}", String.join(" ", command));

        return command;
    }

    // ===================== EXECUTION =====================

    public boolean executeWithProgress(
            List<String> command,
            Consumer<FfmpegCmdResponse> progressCallback,
            long totalDurationMs
    ) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                int percent = 0;
                String finalFileSize = "0 KB";

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("out_time_ms=")) {
                        try {
                            long currentTimeMs = Long.parseLong(line.split("=")[1].trim()) / 1000; // Convert micro to milli
                            if (totalDurationMs > 0) {
                                percent = (int) ((currentTimeMs * 100) / totalDurationMs);
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Could not parse out_time_ms");
                        }
                    }
                    else if (line.startsWith("total_size=")) {
                        try {
                            long sizeInBytes = Long.parseLong(line.split("=")[1].trim());
                            finalFileSize = formatFileSize(sizeInBytes);
                        } catch (NumberFormatException e) {
                            log.warn("Could not parse total_size");
                        }
                    }

                    progressCallback.accept(new FfmpegCmdResponse(Math.min(percent, 100), finalFileSize));
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new MediaProcessingException("FFmpeg timed out");
            }

            return process.exitValue() == 0;

        } catch (Exception e) {
            throw new MediaProcessingException("Failed during FFmpeg execution", e);
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
                inputPath
        );

        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {

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

    public double probeAndParse(String inputPath) {

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

    private String formatFileSize(long bytes) {
        if (bytes < 0) return "0 KB";

        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;

        if (gb >= 1.0) {
            return String.format("%.2f GB", gb);
        } else if (mb >= 1.0) {
            return String.format("%.2f MB", mb);
        } else {
            return String.format("%.2f KB", kb);
        }
    }
}
