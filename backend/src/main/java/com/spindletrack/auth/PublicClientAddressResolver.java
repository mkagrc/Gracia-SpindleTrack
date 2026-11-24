package com.spindletrack.auth;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves the client address without trusting caller-supplied forwarding headers by default. */
@Component
public class PublicClientAddressResolver {
    private final List<Network> trustedProxies;

    public PublicClientAddressResolver(@Value("${spindletrack.proxy.trusted-cidrs:}") String trustedCidrs) {
        this.trustedProxies = parseNetworks(trustedCidrs);
    }

    public String resolve(HttpServletRequest request) {
        String peer = cleanAddress(request.getRemoteAddr());
        if (peer.isBlank() || !isTrusted(peer)) return peer.isBlank() ? "unknown" : peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return peer;
        String[] chain = forwarded.split(",");
        String candidate = peer;
        for (int i = chain.length - 1; i >= 0; i--) {
            String address = cleanAddress(chain[i]);
            if (address.isBlank()) return peer;
            candidate = address;
            if (!isTrusted(address)) return address;
        }
        return candidate;
    }

    private boolean isTrusted(String address) {
        byte[] candidate = parseAddress(address);
        if (candidate == null) return false;
        return trustedProxies.stream().anyMatch(network -> network.contains(candidate));
    }

    private static List<Network> parseNetworks(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<Network> networks = new ArrayList<>();
        for (String token : value.split(",")) {
            String[] parts = token.trim().split("/", -1);
            byte[] address = parseAddress(parts[0]);
            if (address == null) continue;
            try {
                int bits = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
                if (parts.length > 2 || bits < 0 || bits > address.length * 8) continue;
                networks.add(new Network(address, bits));
            } catch (NumberFormatException ignored) {
                // Invalid proxy configuration is ignored rather than making untrusted headers trusted.
            }
        }
        return List.copyOf(networks);
    }

    private static String cleanAddress(String value) {
        if (value == null) return "";
        String address = value.trim();
        if (address.startsWith("[") && address.endsWith("]")) address = address.substring(1, address.length() - 1);
        if (address.contains("%")) address = address.substring(0, address.indexOf('%'));
        return parseAddress(address) == null ? "" : address;
    }

    private static byte[] parseAddress(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9a-fA-F:.]+")) return null;
        try { return InetAddress.getByName(value).getAddress(); }
        catch (Exception ignored) { return null; }
    }

    private record Network(byte[] address, int prefixBits) {
        boolean contains(byte[] candidate) {
            if (address.length != candidate.length) return false;
            int fullBytes = prefixBits / 8, remainingBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) if (address[i] != candidate[i]) return false;
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (address[fullBytes] & mask) == (candidate[fullBytes] & mask);
        }
    }
}
