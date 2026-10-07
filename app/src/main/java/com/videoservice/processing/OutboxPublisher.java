package com.videoservice.processing;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;

    @Scheduled(fixedDelayString = "${video.processing.outbox-poll-ms:2000}")
    public void publishPending() {
        List<Event> events = jdbc.query("SELECT event_id,payload::text FROM outbox_events " +
                        "WHERE published_at IS NULL AND next_attempt_at <= now() ORDER BY created_at LIMIT 25",
                (rs, row) -> new Event((UUID) rs.getObject("event_id"), rs.getString("payload")));
        for (Event event : events) {
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                Message message = new Message(event.payload().getBytes(StandardCharsets.UTF_8), properties);
                CorrelationData correlation = new CorrelationData(event.id().toString());
                rabbit.send(RabbitProcessingConfig.EXCHANGE, "processing.requested", message, correlation);
                var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("Broker did not confirm routed message");
                }
                jdbc.update("UPDATE outbox_events SET published_at=now() WHERE event_id=? AND published_at IS NULL", event.id());
            } catch (Exception ex) {
                jdbc.update("UPDATE outbox_events SET publish_attempts=publish_attempts+1, " +
                                "next_attempt_at=now()+make_interval(secs => LEAST(120, POWER(2, LEAST(publish_attempts, 6))::int)) " +
                                "WHERE event_id=? AND published_at IS NULL", event.id());
            }
        }
    }

    private record Event(UUID id, String payload) {
    }
}
