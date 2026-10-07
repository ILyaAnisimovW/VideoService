package com.videoservice.app;

import com.videoservice.upload.StorageGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/health")
@RequiredArgsConstructor
public class HealthController {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final StorageGateway storage;

    @GetMapping
    public Map<String, String> liveness() {
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> readiness() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            if (!Boolean.TRUE.equals(rabbit.execute(channel -> channel.isOpen())) || !storage.available()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
            }
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
        }
    }
}
