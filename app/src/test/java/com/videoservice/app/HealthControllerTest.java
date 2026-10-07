package com.videoservice.app;

import com.videoservice.upload.StorageGateway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final StorageGateway storage = mock(StorageGateway.class);
    private final HealthController controller = new HealthController(jdbc, rabbit, storage);

    @Test
    void readinessIsDownWhenRabbitIsUnavailable() {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        assertEquals(503, controller.readiness().getStatusCode().value());
    }

    @Test
    void readinessIsUpOnlyWhenDependenciesAreAvailable() {
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(rabbit.execute(any())).thenReturn(true);
        when(storage.available()).thenReturn(true);
        assertEquals(200, controller.readiness().getStatusCode().value());
    }
}
