package com.mediaalterations.mediaservice.messaging;

import com.mediaalterations.mediaservice.dto.ProcessDto;
import com.mediaalterations.mediaservice.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProcessListener {

    private final MediaService mediaService;

    @RabbitListener(queues = "media.process.queue", concurrency = "2-4")
    public void handleAllOrderEvents(ProcessDto event) throws Exception {
        log.info("Received: {}",event);
        mediaService.extractAudioFromVideo(event);
    }

}
