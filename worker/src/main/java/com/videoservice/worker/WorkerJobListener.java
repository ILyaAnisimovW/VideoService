package com.videoservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
class WorkerJobListener {
    private final ObjectMapper mapper;
    private final WorkerApi api;
    private final MediaProcessor media;
    private final RabbitTemplate rabbit;

    @RabbitListener(queues = WorkerRabbitConfig.JOBS)
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        WorkerMessages.Requested request;
        try {
            request = mapper.readValue(message.getBody(), WorkerMessages.Requested.class);
            if (request.eventId() == null || request.jobId() == null || request.videoId() == null ||
                    request.processingVersion() < 1 || request.schemaVersion() != 1 ||
                    !"VideoProcessingRequested".equals(request.eventType())) {
                throw new IllegalArgumentException("Invalid job envelope");
            }
        } catch (Exception ex) {
            channel.basicReject(tag, false);
            return;
        }
        WorkerMessages.Lease lease;
        try {
            var claim = api.claim(request.jobId(), request.processingVersion());
            if (claim.isEmpty()) {
                channel.basicAck(tag, false);
                return;
            }
            lease = claim.get();
            if (!lease.videoId().equals(request.videoId()) || !lease.jobId().equals(request.jobId())) {
                channel.basicReject(tag, false);
                return;
            }
        } catch (RuntimeException ex) {
            channel.basicReject(tag, false);
            return;
        }
        try (LeaseGuard guard = new LeaseGuard(api, lease)) {
            WorkerMessages.Result result;
            try {
                MediaProcessor.Output output = media.process(lease, guard);
                result = new WorkerMessages.Result(UUID.randomUUID(), "VideoProcessingSucceeded", 1, Instant.now(),
                        request.traceId(), lease.videoId(), lease.jobId(), lease.processingVersion(),
                        lease.attemptNo(), lease.leaseId(), output.durationMs(), output.assets(), null, null);
            } catch (MediaFailure failure) {
                if (!guard.active()) {
                    channel.basicReject(tag, false);
                    return;
                }
                result = new WorkerMessages.Result(UUID.randomUUID(), "VideoProcessingFailed", 1, Instant.now(),
                        request.traceId(), lease.videoId(), lease.jobId(), lease.processingVersion(),
                        lease.attemptNo(), lease.leaseId(), null, null, failure.code(), failure.retryable());
            }
            try {
                publish(result);
                channel.basicAck(tag, false);
            } catch (Exception ex) {
                channel.basicReject(tag, false);
                return;
            }
            while (guard.active() && guard.terminalState() == null) {
                try { Thread.sleep(1000); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void publish(WorkerMessages.Result result) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(mapper.writeValueAsBytes(result), properties);
        CorrelationData correlation = new CorrelationData(result.eventId().toString());
        String routing = result.eventType().equals("VideoProcessingSucceeded")
                ? "processing.succeeded" : "processing.failed";
        rabbit.send(WorkerRabbitConfig.EXCHANGE, routing, message, correlation);
        var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("Result was not routed");
        }
    }
}
