package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicClientAddressResolverTest {
    @Test
    void ignoresForwardedAddressFromUntrustedPeer() {
        PublicClientAddressResolver resolver = new PublicClientAddressResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.25");
        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void selectsFirstUntrustedAddressFromRightmostTrustedProxyChain() {
        PublicClientAddressResolver resolver = new PublicClientAddressResolver("127.0.0.1/32,10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.25, 10.2.3.4");
        assertEquals("198.51.100.25", resolver.resolve(request));
    }

    @Test
    void ignoresMalformedForwardedChainAndUsesTrustedProxyPeer() {
        PublicClientAddressResolver resolver = new PublicClientAddressResolver("127.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "attacker.example");
        assertEquals("127.0.0.1", resolver.resolve(request));
    }
}
