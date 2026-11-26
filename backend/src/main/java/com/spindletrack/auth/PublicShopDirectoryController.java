package com.spindletrack.auth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/shops")
public class PublicShopDirectoryController {

    private final PublicShopDirectoryService directory;
    private final NominatimReverseGeocoder geocoder;
    private final RegistrationRateLimitService rateLimit;
    private final PublicClientAddressResolver addressResolver;

    public PublicShopDirectoryController(
            PublicShopDirectoryService directory,
            NominatimReverseGeocoder geocoder,
            RegistrationRateLimitService rateLimit,
            PublicClientAddressResolver addressResolver) {
        this.directory       = directory;
        this.geocoder        = geocoder;
        this.rateLimit       = rateLimit;
        this.addressResolver = addressResolver;
    }

    /** Public shop list — no authentication required. */
    @GetMapping
    public List<PublicShopDirectoryService.PublicShopSummary> search(
            @RequestParam(defaultValue = "")  String search,
            @RequestParam(required = false)   Double lat,
            @RequestParam(required = false)   Double lng,
            @RequestParam(defaultValue = "0") int    page,
            HttpServletRequest request) {
        rateLimit.recordPublicShopDirectoryAttempt(addressResolver.resolve(request));
        return directory.search(search, lat, lng, page);
    }

    /** Public nearby grouped shop search — no authentication required. */
    @GetMapping("/nearby")
    public PublicShopDirectoryService.NearbyResult nearby(
            @RequestParam(required = false)   Double lat,
            @RequestParam(required = false)   Double lng,
            @RequestParam(defaultValue = "")  String municipality,
            @RequestParam(required = false)   Double radiusKm,
            HttpServletRequest request) {
        rateLimit.recordPublicShopDirectoryAttempt(addressResolver.resolve(request));
        return directory.nearbyGrouped(lat, lng, municipality, radiusKm);
    }

    /** Public reverse geocode helper — no authentication required. */
    @GetMapping("/reverse-geocode")
    public NominatimReverseGeocoder.GeocodeResult reverseGeocode(
            @RequestParam double lat,
            @RequestParam double lng,
            HttpServletRequest request) {
        rateLimit.recordPublicShopDirectoryAttempt(addressResolver.resolve(request));
        return geocoder.resolve(lat, lng);
    }

    /** Public shop detail — no authentication required. Non-ACTIVE shops return 404. */
    @GetMapping("/{shopId}")
    public PublicShopDirectoryService.PublicShopDetail detail(
            @PathVariable String shopId,
            HttpServletRequest request) {
        rateLimit.recordPublicShopDirectoryAttempt(addressResolver.resolve(request));
        return directory.detail(shopId);
    }
}
