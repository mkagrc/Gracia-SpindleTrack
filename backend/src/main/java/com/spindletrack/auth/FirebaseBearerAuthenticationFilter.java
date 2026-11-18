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

    public FirebaseBearerAuthenticationFilter(
            FirebaseTokenVerifier tokenVerifier,
            UserAccountRepository userAccounts,
            ObjectMapper objectMapper) {
        this.tokenVerifier = tokenVerifier;
        this.userAccounts = userAccounts;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/api/health".equals(request.getServletPath())
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
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
            if (account != null && !account.active()) {
                writeError(response, HttpStatus.FORBIDDEN, "This account is inactive.");
                return;
            }

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"));
            Long userId = null;
            String role = null;
            Long shopId = null;
            String shopStatus = null;
            if (account != null) {
                role = account.role();
                userId = account.id();
                shopId = account.shopId();
                shopStatus = account.shopStatus();
                authorities.add(new SimpleGrantedAuthority("ROLE_" + account.role()));
            }

            FirebasePrincipal principal = new FirebasePrincipal(
                    identity.uid(), identity.email(), identity.displayName(), userId, role, shopId, shopStatus);
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (FirebaseAuthenticationException exception) {
            SecurityContextHolder.clearContext();
            writeError(response, exception.getStatus(), exception.getMessage());
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("message", message));
    }
}
