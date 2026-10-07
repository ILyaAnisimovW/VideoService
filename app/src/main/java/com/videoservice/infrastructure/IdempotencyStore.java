package com.videoservice.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoservice.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class IdempotencyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public String fingerprint(String method, String route, Object body) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(body);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method + " " + route + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot fingerprint request", ex);
        }
    }

    public void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Неверный Idempotency-Key");
        }
    }

    public <T> Optional<T> replay(UUID userId, String operation, String key, String hash, Class<T> type) {
        var rows = jdbc.query("SELECT request_hash, state, response_body, expires_at FROM idempotency_records " +
                        "WHERE user_id=? AND operation=? AND key=?", (rs, row) -> read(rs), userId, operation, key);
        if (rows.isEmpty() || rows.get(0).expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        Entry entry = rows.get(0);
        if (!entry.hash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Ключ уже использован с другим запросом");
        }
        if (!entry.state().equals("COMPLETED")) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Запрос ещё выполняется");
        }
        try {
            return Optional.of(mapper.readValue(entry.body(), type));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored idempotent response is invalid", ex);
        }
    }

    /** Called inside the business transaction; rollback also removes the reservation. */
    public boolean reserve(UUID userId, String operation, String key, String hash) {
        jdbc.update("DELETE FROM idempotency_records WHERE user_id=? AND operation=? AND key=? AND expires_at < now()",
                userId, operation, key);
        return jdbc.update("INSERT INTO idempotency_records(user_id,operation,key,request_hash,state,expires_at) " +
                        "VALUES (?,?,?,?,'IN_PROGRESS',now()+interval '24 hours') ON CONFLICT DO NOTHING",
                userId, operation, key, hash) == 1;
    }

    public void complete(UUID userId, String operation, String key, String hash, int status, Object body, String location, String etag) {
        String response;
        String headers;
        try {
            response = mapper.writeValueAsString(body);
            headers = mapper.writeValueAsString(new SavedHeaders(location, etag));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot store response", ex);
        }
        int updated = jdbc.update("UPDATE idempotency_records SET state='COMPLETED', response_status=?, " +
                        "response_body=?::jsonb, response_headers=?::jsonb, expires_at=now()+interval '24 hours' " +
                        "WHERE user_id=? AND operation=? AND key=? AND request_hash=?",
                status, response, headers, userId, operation, key, hash);
        if (updated == 0) {
            int inserted = jdbc.update("INSERT INTO idempotency_records(user_id,operation,key,request_hash,state,response_status,response_body,response_headers,expires_at) " +
                            "VALUES (?,?,?,?,'COMPLETED',?,?::jsonb,?::jsonb,now()+interval '24 hours') ON CONFLICT DO NOTHING",
                    userId, operation, key, hash, status, response, headers);
            if (inserted == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Ключ уже использован с другим запросом");
            }
        }
    }

    private Entry read(ResultSet rs) throws SQLException {
        return new Entry(rs.getString("request_hash"), rs.getString("state"), rs.getString("response_body"),
                rs.getTimestamp("expires_at").toInstant());
    }

    private record Entry(String hash, String state, String body, Instant expiresAt) {
    }

    private record SavedHeaders(String location, String etag) {
    }
}
