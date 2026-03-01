package com.mediaalterations.mediaservice.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.mediaalterations.mediaservice.service.MediaService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class KillListener {

    private final MediaService mediaService;

    // "#{killQueue.name}" — Spring EL that reads the name of the AnonymousQueue
    // bean
    // since the name is random, you can't hardcode it, so you reference the bean
    @RabbitListener(queues = "#{killQueue.name}")
    public void handleKill(String processId) {
        log.info("Request received for process kill, processId:{}", processId);
        try {
            mediaService.killProcess(processId);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("Error processing message", e);
        }

    }

}
