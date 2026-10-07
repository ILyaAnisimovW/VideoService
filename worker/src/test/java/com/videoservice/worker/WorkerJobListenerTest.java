package com.videoservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class WorkerJobListenerTest {
    @Test
    void malformedJobGoesToDeadLetterWithoutClaim() throws Exception {
        WorkerApi api = mock(WorkerApi.class);
        WorkerJobListener listener = new WorkerJobListener(new ObjectMapper().findAndRegisterModules(), api,
                mock(MediaProcessor.class), mock(RabbitTemplate.class));
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(42);
        Channel channel = mock(Channel.class);
        listener.consume(new Message("not-json".getBytes(StandardCharsets.UTF_8), properties), channel);
        verify(channel).basicReject(42, false);
        verifyNoInteractions(api);
    }

    @Test
    void duplicateTerminalCommandIsAcknowledged() throws Exception {
        WorkerApi api = mock(WorkerApi.class);
        WorkerJobListener listener = new WorkerJobListener(new ObjectMapper().findAndRegisterModules(), api,
                mock(MediaProcessor.class), mock(RabbitTemplate.class));
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"VideoProcessingRequested\"," +
                "\"schemaVersion\":1,\"occurredAt\":\"2026-10-07T00:00:00Z\",\"traceId\":\"t\"," +
                "\"videoId\":\"" + videoId + "\",\"jobId\":\"" + jobId + "\",\"processingVersion\":1}";
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(43);
        Channel channel = mock(Channel.class);
        when(api.claim(jobId, 1)).thenReturn(Optional.empty());
        listener.consume(new Message(body.getBytes(StandardCharsets.UTF_8), properties), channel);
        verify(channel).basicAck(43, false);
    }
}
