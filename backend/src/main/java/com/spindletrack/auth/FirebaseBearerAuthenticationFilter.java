package com.spindletrack.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class FirebaseBearerAuthenticationFilter extends OncePerRequestFilter {
    private final FirebaseTokenVerifier tokenVerifier;
    private final UserAccountRepository userAccounts;
    private final ObjectMapper objectMapper;
    private final String adminEmail;

    public FirebaseBearerAuthenticationFilter(FirebaseTokenVerifier tokenVerifier, UserAccountRepository userAccounts,
            ObjectMapper objectMapper, @Value("${spindletrack.auth.admin-email:}") String adminEmail) {
        this.tokenVerifier = tokenVerifier;
        this.userAccounts = userAccounts;
        this.objectMapper = objectMapper;
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/api/health".equals(request.getServletPath()) || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            filterChain.doFilter(request, response);
            return;
        }
        String rawToken = header.substring(7).trim();
        if (rawToken.isEmpty()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "A Firebase ID token is required.");
            return;
        }
        try {
            FirebaseIdentity identity = tokenVerifier.verify(rawToken);
            UserAccount account = userAccounts.findByFirebaseUid(identity.uid()).orElse(null);
            if (account != null && "PLATFORM_ADMIN".equals(account.role())
                    && (adminEmail.isBlank() || !adminEmail.equals(identity.email() == null ? ""
                            : identity.email().trim().toLowerCase(java.util.Locale.ROOT)))) {
                writeError(response, HttpStatus.FORBIDDEN, "This account is not configured as the platform administrator.");
                return;
            }
            if (account != null && !account.active()) {
                writeError(response, HttpStatus.FORBIDDEN, "This account is inactive.");
                return;
            }
            if (account != null && !ShopOperationPolicy.mayUseShopOperations(account.role(), account.shopStatus(),
                    request.getMethod(), request.getServletPath())) {
                SecurityContextHolder.clearContext();
                writeShopStatusError(response, ShopOperationPolicy.blockedMessage(account.shopStatus()));
                return;
            }
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"));
            String userId = null;
            String role = null;
            String shopId = null;
            String shopStatus = null;
            if (account != null) {
                role = account.role();
                userId = account.id();
                shopId = account.shopId();
                shopStatus = account.shopStatus();
                authorities.add(new SimpleGrantedAuthority("ROLE_" + account.role()));
            }
            FirebasePrincipal principal = new FirebasePrincipal(identity.uid(), identity.email(), identity.displayName(),
                    userId, role, shopId, shopStatus);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities));
            filterChain.doFilter(request, response);
        } catch (FirebaseAuthenticationException exception) {
            SecurityContextHolder.clearContext();
            writeError(response, exception.getStatus(), exception.getMessage());
        }
    }

    private void writeShopStatusError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("code", "SHOP_NOT_ACTIVE", "message", message));
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("message", message));
    }
}
