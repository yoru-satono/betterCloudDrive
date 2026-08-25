package com.betterclouddrive.web.security;

import com.betterclouddrive.web.config.ClientIpProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final ClientIpProperties properties;

    public String resolve(HttpServletRequest request) {
        String current = normalize(request.getRemoteAddr());
        if (current == null) {
            return "unknown";
        }
        if (!isTrustedProxy(current)) {
            return current;
        }
        String directPeer = current;

        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String[] addresses = forwarded.split(",");
            for (int index = addresses.length - 1; index >= 0 && isTrustedProxy(current); index--) {
                String candidate = normalize(addresses[index]);
                if (candidate == null) {
                    return directPeer;
                }
                current = candidate;
            }
            return current;
        }

        String realIp = normalize(request.getHeader("X-Real-IP"));
        return realIp != null ? realIp : current;
    }

    private boolean isTrustedProxy(String address) {
        return properties.getTrustedProxyCidrs().stream()
                .filter(StringUtils::hasText)
                .anyMatch(cidr -> matches(cidr.trim(), address));
    }

    private boolean matches(String cidr, String address) {
        try {
            return new IpAddressMatcher(cidr).matches(address);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String candidate = value.trim();
        if (!isIpv4Literal(candidate) && !isIpv6Literal(candidate)) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private boolean isIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean isIpv6Literal(String value) {
        return value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+");
    }
}
