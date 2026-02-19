package com.mediaalterations.mediaservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter
@Setter
public class FfmpegCmdResponse {
    private int progress;
    private String duration;
}
