package com.admin.equipment.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private static final long EXPIRE_MILLIS = 12L * 60 * 60 * 1000;

    public JwtUtil(@Value("${app.jwt-secret}") String secret) {
        // 密钥不足 32 字节时补齐，保证 HS256 安全长度
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** 令牌携带角色/班组/区域/权限版本快照；服务端仍以数据库为准并核对权限版本。 */
    public String createToken(Long userId, String username, Role role, String teamName,
                               String managedAreas, long permissionVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("usr", username)
                .claim("role", role == null ? null : role.name())
                .claim("team", teamName)
                .claim("areas", managedAreas)
                .claim("pv", permissionVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXPIRE_MILLIS))
                .signWith(key)
                .compact();
    }

    /** 校验签名/有效期并返回全部 Claims，失败返回 null。 */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
