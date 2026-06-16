package com.betterclouddrive.web.download;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class DownloadTicketService {

    private static final String KEY_PREFIX = "download:ticket:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public String createTicket(Long userId, Long resourceId, DownloadTicketType type) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(KEY_PREFIX + token, userId + ":" + type.name() + ":" + resourceId, TTL);
        return token;
    }

    public DownloadTicket consumeTicket(String token, Long resourceId, DownloadTicketType type) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }

        String key = KEY_PREFIX + token;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            throw new BusinessException(ApiCode.UNAUTHORIZED, "Download ticket expired");
        }
        redisTemplate.expire(key, TTL);

        String[] parts = value.split(":", 3);
        if (parts.length != 3) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
        Long ticketUserId = Long.parseLong(parts[0]);
        DownloadTicketType ticketType = DownloadTicketType.valueOf(parts[1]);
        Long ticketResourceId = Long.parseLong(parts[2]);
        if (!ticketType.equals(type) || !ticketResourceId.equals(resourceId)) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
        return new DownloadTicket(ticketUserId, ticketResourceId, ticketType);
    }
}
