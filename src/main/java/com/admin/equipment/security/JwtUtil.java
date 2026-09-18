package com.admin.equipment.security;

import com.admin.equipment.model.AppUser;
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

    /**
     * 签发令牌。令牌携带角色/班组/区域快照与权限版本，
     * 但服务端每次仍以数据库为准（见 AuthFilter）：快照仅用于减少对象装配，
     * 权限版本不一致或账号禁用即判定令牌失效。
     */
    public String createToken(AppUser user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("usr", user.getUsername())
                .claim("rol", user.getRole())
                .claim("tm", user.getTeamName() == null ? "" : user.getTeamName())
                .claim("ar", user.getManagedAreas() == null ? "" : user.getManagedAreas())
                .claim("pv", user.getPermissionVersion() == null ? 1 : user.getPermissionVersion())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXPIRE_MILLIS))
                .signWith(key)
                .compact();
    }

    /** 校验签名/过期并解析快照；失败返回 null。 */
    public TokenSnapshot parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String role = claims.get("rol", String.class);
            Integer pv = claims.get("pv", Integer.class);
            if (pv == null) {
                Number n = claims.get("pv", Number.class);
                pv = n == null ? 1 : n.intValue();
            }
            return new TokenSnapshot(userId, role, pv);
        } catch (Exception e) {
            return null;
        }
    }

    /** 令牌内的权限快照。 */
    public record TokenSnapshot(Long userId, String role, int permissionVersion) {}
}
