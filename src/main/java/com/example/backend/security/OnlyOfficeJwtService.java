package com.example.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * Tokens HS256 del canal OnlyOffice — con su PROPIO secreto
 * ({@code app.onlyoffice.secret}), nunca el JWT principal de usuarios.
 *
 * Tres usos:
 *   1. Firmar la configuración del editor (campo {@code token} que el
 *      Document Server exige cuando JWT_ENABLED=true).
 *   2. Emitir tokens de corta vida en la query de los endpoints que el
 *      Document Server invoca server-to-server (descarga y callback) —
 *      el DS no tiene la sesión del usuario, el token ES la autorización.
 *   3. Verificar el JWT que el DS adjunta en el body del callback.
 */
@Service
public class OnlyOfficeJwtService {

    private final SecretKey signingKey;

    public OnlyOfficeJwtService(@Value("${app.onlyoffice.secret}") String secret) {
        // El secreto de OnlyOffice es texto plano (igual que en el
        // contenedor); HS256 exige >= 32 bytes.
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Firma un mapa de claims con expiración. */
    public String sign(Map<String, Object> claims, long ttlMillis) {
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(signingKey)
                .compact();
    }

    /** Verifica y devuelve los claims; lanza JwtException si es inválido. */
    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            verify(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }
}
