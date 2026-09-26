package io.annona.modules.identity.service;

import io.annona.modules.identity.entity.UserSessionEntity;
import io.annona.modules.identity.repository.UserSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * user_session 审计投影写入（identity ADR §后果 §2：写失败只 warn，绝不影响登录）。
 * 只存 UA 的 SHA-256 哈希，不落原始 User-Agent。调用方（{@link LoginService}）负责 try/catch。
 */
@Service
public class SessionProjectionWriter {

    private final UserSessionRepository repository;

    public SessionProjectionWriter(UserSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(UUID userId, String ip, String device, String userAgent) {
        UserSessionEntity s = new UserSessionEntity();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setIp(ip);
        s.setDevice(device);
        s.setUaHash(hashUserAgent(userAgent));
        s.setLastSeenAt(Instant.now());
        repository.save(s);
    }

    private static String hashUserAgent(String ua) {
        if (ua == null || ua.isBlank()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(ua.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
