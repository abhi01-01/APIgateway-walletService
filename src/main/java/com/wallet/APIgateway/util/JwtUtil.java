package com.wallet.APIgateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

import static reactor.netty.http.HttpConnectionLiveness.log;

@Component
public class JwtUtil {

    // Must be the exact same secret used in WalletService
    @Value("${application.security.jwt.secret-key}")
    private String secretKey;

    private SecretKey getSigningKey(){
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // Unified Claim Extraction with Graceful Expiration
    public Claims extractClaims(String token, boolean allowExpired) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            if (allowExpired) {
                log.debug("Edge Security: Extracted claims from gracefully expired token.");
                return e.getClaims(); // Salvage the payload from the exception
            }
            throw e; // Rethrow to trigger the 401 Unauthorized
        }
    }

    // Safe TTL calculation
    public long getRemainingExpirationMs(String token) {
        try {
            Date expiration = extractClaims(token, false).getExpiration();
            return Math.max(0, expiration.getTime() - System.currentTimeMillis());
        } catch (ExpiredJwtException e) {
            return 0; // Token is already dead, TTL is zero
        }
    }

}
