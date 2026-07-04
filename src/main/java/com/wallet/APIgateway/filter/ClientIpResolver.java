package com.wallet.APIgateway.filter;

import com.wallet.APIgateway.config.RateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Component
public class ClientIpResolver {

    private static final String UNKNOWN_IP = "unknown";

    private final RateLimitProperties rateLimitProperties;

    public ClientIpResolver(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    public String resolve(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        String remoteIp = extractRemoteIp(request);

        if (StringUtils.hasText(forwardedFor) && isTrustedProxy(remoteIp)) {
            return extractFirstForwardedIp(forwardedFor).orElse(remoteIp);
        }

        if (StringUtils.hasText(forwardedFor) && StringUtils.hasText(remoteIp)) {
            log.debug("Ignoring X-Forwarded-For because remote address {} is not a configured trusted proxy.", remoteIp);
        }

        return StringUtils.hasText(remoteIp) ? remoteIp : UNKNOWN_IP;
    }

    private String extractRemoteIp(ServerHttpRequest request) {
        return Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(InetAddress::getHostAddress)
                .orElse(null);
    }

    private Optional<String> extractFirstForwardedIp(String forwardedFor) {
        return Stream.of(forwardedFor.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private boolean isTrustedProxy(String remoteIp) {
        if (!StringUtils.hasText(remoteIp)) {
            return false;
        }

        return rateLimitProperties.getTrustedProxies().stream()
                .filter(StringUtils::hasText)
                .anyMatch(candidate -> matches(remoteIp, candidate.trim()));
    }

    private boolean matches(String ip, String candidate) {
        try {
            if (candidate.contains("/")) {
                return matchesCidr(ip, candidate);
            }

            return InetAddress.getByName(ip).equals(InetAddress.getByName(candidate));
        } catch (UnknownHostException | IllegalArgumentException ex) {
            log.warn("Ignoring invalid trusted proxy entry '{}'", candidate, ex);
            return false;
        }
    }

    private boolean matchesCidr(String ip, String cidr) throws UnknownHostException {
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("CIDR must contain an address and prefix length");
        }

        InetAddress address = InetAddress.getByName(ip);
        InetAddress networkAddress = InetAddress.getByName(parts[0]);
        int prefixLength = Integer.parseInt(parts[1]);

        byte[] addressBytes = address.getAddress();
        byte[] networkBytes = networkAddress.getAddress();

        if (addressBytes.length != networkBytes.length) {
            return false;
        }

        int totalBits = addressBytes.length * 8;
        if (prefixLength < 0 || prefixLength > totalBits) {
            throw new IllegalArgumentException("Invalid CIDR prefix length");
        }

        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;

        for (int i = 0; i < fullBytes; i++) {
            if (addressBytes[i] != networkBytes[i]) {
                return false;
            }
        }

        if (remainingBits == 0) {
            return true;
        }

        int mask = 0xFF << (8 - remainingBits);
        return (addressBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
    }
}
