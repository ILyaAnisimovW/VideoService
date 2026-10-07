package com.videoservice.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class WorkerApi {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${worker.api-base-url}") private String baseUrl;
    @Value("${worker.jwt-secret}") private String secret;
    @Value("${worker.id}") private String workerId;

    Optional<WorkerMessages.Lease> claim(UUID jobId, int version) {
        try {
            HttpRequest request = request("/api/v1/internal/processing-jobs/" + jobId + "/claims")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("processingVersion", version))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) return Optional.empty();
            if (response.statusCode() != 201) throw new IOException("Claim rejected: " + response.statusCode());
            return Optional.of(mapper.readValue(response.body(), WorkerMessages.Lease.class));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker claim unavailable", ex);
        }
    }

    WorkerMessages.LeaseState heartbeat(WorkerMessages.Lease lease) {
        try {
            HttpRequest request = request("/api/v1/internal/processing-jobs/" + lease.jobId() + "/lease")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "leaseId", lease.leaseId(), "attemptNo", lease.attemptNo())))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) throw new LeaseLostException();
            if (response.statusCode() != 200) throw new IOException("Heartbeat rejected: " + response.statusCode());
            return mapper.readValue(response.body(), WorkerMessages.LeaseState.class);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker heartbeat unavailable", ex);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json");
    }

    private String token() {
        try {
            if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("Worker JWT secret too short");
            String header = encoded(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            long now = Instant.now().getEpochSecond();
            String payload = encoded(mapper.writeValueAsBytes(Map.of("iss", "video-service-worker",
                    "sub", workerId, "aud", "video-worker", "iat", now, "exp", now + 300)));
            String input = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return input + "." + encoded(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot issue worker token", ex);
        }
    }

    private String encoded(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static final class LeaseLostException extends RuntimeException { }
}
