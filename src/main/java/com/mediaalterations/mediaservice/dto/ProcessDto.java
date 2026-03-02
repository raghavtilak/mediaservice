package com.mediaalterations.mediaservice.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record ProcessDto(

                UUID id,

                // storageIdInput , storageInputPath
                Map<String, String> storageInputDetails,
                String storageIdOutput,
                String storageOutputPath,

                String fileName,
                String finalFileSize,
                String command,

                ProcessStatus status,

                String userId,

                LocalDateTime created_at) {
}
