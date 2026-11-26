package com.spindletrack.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Reverse geocodes latitude/longitude to municipality and province using Nominatim.
 * Results are cached by truncated coordinates (1 decimal place) with a 24-hour TTL.
 * The caller's precise coordinates are never stored; only truncated keys are cached.
 */
@Service
public class NominatimReverseGeocoder {

    private static final Logger log = LoggerFactory.getLogger(NominatimReverseGeocoder.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    @Value("${spindletrack.nominatim.user-agent:SpindleTrack/1.0 (contact: admin@spindletrack.app)}")
    private String userAgent;

    private final RestClient restClient;
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    public NominatimReverseGeocoder() {
        this.restClient = RestClient.builder()
                .baseUrl("https://nominatim.openstreetmap.org")
                .build();
    }

    public record GeocodeResult(String municipality, String province) {}

    private record CachedResult(GeocodeResult result, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }

    /**
     * Resolves a lat/lng to municipality and province.
     * Returns null municipality/province fields on failure (graceful degradation).
     * Never logs the precise coordinates.
     */
    public GeocodeResult resolve(double lat, double lng) {
        OwnerShopService.validateCoordinates(lat, lng);
        String key = String.format("%.1f,%.1f", lat, lng);
        CachedResult cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.result();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/reverse?format=json&lat={lat}&lon={lng}&zoom=10", lat, lng)
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return cacheAndReturn(key, new GeocodeResult(null, null));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) response.get("address");
            if (address == null) {
                return cacheAndReturn(key, new GeocodeResult(null, null));
            }
            String municipality = PublicShopDirectoryService.normalizeMunicipality(firstNonNull(
                    asNonBlankString(address.get("city")),
                    asNonBlankString(address.get("town")),
                    asNonBlankString(address.get("municipality")),
                    asNonBlankString(address.get("county"))));
            String province = asNonBlankString(address.get("state"));
            return cacheAndReturn(key, new GeocodeResult(municipality, province));
        } catch (Exception e) {
            log.warn("Nominatim reverse geocode failed: {}", e.getMessage());
            return new GeocodeResult(null, null);
        }
    }

    private GeocodeResult cacheAndReturn(String key, GeocodeResult result) {
        cache.put(key, new CachedResult(result, Instant.now().plus(CACHE_TTL)));
        return result;
    }

    private static String asNonBlankString(Object value) {
        if (!(value instanceof String text)) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}
