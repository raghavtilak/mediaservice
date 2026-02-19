package com.mediaalterations.mediaservice.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProcessDto(

        UUID id,

        String storageIdInput,
        String storageIdOutput,
        String storageInputPath,
        String command,

        ProcessStatus status,

        String userId,

        LocalDateTime created_at
) {
}
