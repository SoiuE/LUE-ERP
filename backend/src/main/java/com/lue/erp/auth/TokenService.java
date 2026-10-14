package com.lue.erp.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class TokenService {
    private final byte[] secret;
    private final long tokenHours;

    public TokenService(@Value("${lue.auth.secret}") String secret, @Value("${lue.auth.token-hours}") long tokenHours) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tokenHours = tokenHours;
    }

    public String issue(long userId) {
        String payload = userId + ":" + Instant.now().plusSeconds(tokenHours * 3600).getEpochSecond();
        String data = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return data + "." + sign(data);
    }

    public long verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2 || !constantTimeEquals(sign(parts[0]), parts[1])) throw new IllegalArgumentException();
            String[] payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split(":");
            if (Long.parseLong(payload[1]) < Instant.now().getEpochSecond()) throw new IllegalArgumentException();
            return Long.parseLong(payload[0]);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
