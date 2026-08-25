package com.betterclouddrive.web.security;

import com.betterclouddrive.web.config.ClientIpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void ignoresForwardedHeadersFromUntrustedPeer() {
        ClientIpProperties properties = new ClientIpProperties();
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("203.0.113.5");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void walksTrustedProxyChainFromRightToLeft() {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustedProxyCidrs(List.of("172.16.0.0/12", "10.0.0.0/8"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("172.20.0.9");
        request.addHeader("X-Forwarded-For", "192.0.2.99, 198.51.100.7, 10.1.2.3");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void rejectsMalformedForwardedValuesAndCanonicalizesIpv6() {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustedProxyCidrs(List.of("172.16.0.0/12"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("172.20.0.9");
        request.addHeader("X-Forwarded-For", "attacker.example, 2001:db8::1");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void rejectsTheWholeForwardedChainWhenAProcessedHopIsMalformed() {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustedProxyCidrs(List.of("172.16.0.0/12"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("172.20.0.9");
        request.addHeader("X-Forwarded-For", "192.0.2.44, malformed");

        assertThat(resolver.resolve(request)).isEqualTo("172.20.0.9");
    }

    @Test
    void usesRealIpOnlyForTrustedPeerWithoutForwardedChain() {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustedProxyCidrs(List.of("172.16.0.0/12"));
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = request("172.20.0.9");
        request.addHeader("X-Real-IP", "192.0.2.44");

        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.44");
    }

    private MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
