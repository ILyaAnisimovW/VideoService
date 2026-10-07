package com.videoservice.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ProcessingResultListener {
    private final ObjectMapper mapper;
    private final ProcessingCoordinator processing;

    @RabbitListener(queues = RabbitProcessingConfig.RESULTS)
    public void consume(Message message) {
        try {
            ProcessingResultEvent result = mapper.readValue(message.getBody(), ProcessingResultEvent.class);
            processing.applyResult(result);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid result JSON", ex);
        }
    }
}
