package com.spindletrack.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationRateLimitService {
    private final JdbcTemplate jdbcTemplate;

    public RegistrationRateLimitService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordAttempt(String remoteAddress) {
        String ipHash = hashAddress(remoteAddress);
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO shop_registration_rate_limits (ip_hash, attempt_date, attempt_count)
                VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE
                    attempt_count = attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                """, ipHash, todayUtc);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT attempt_count
                FROM shop_registration_rate_limits
                WHERE ip_hash = ? AND attempt_date = ?
                """, Integer.class, ipHash, todayUtc);
        return count == null ? 0 : count;
    }

    static String hashAddress(String remoteAddress) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(remoteAddress.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
