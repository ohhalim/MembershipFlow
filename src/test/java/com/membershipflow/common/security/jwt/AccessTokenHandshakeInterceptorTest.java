package com.membershipflow.common.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

class AccessTokenHandshakeInterceptorTest {

    private final AccessTokenHandshakeInterceptor interceptor = new AccessTokenHandshakeInterceptor();
    private final WebSocketHandler wsHandler = mock(WebSocketHandler.class);

    private boolean handshake(MockHttpServletRequest servletRequest, Map<String, Object> attributes) {
        return interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                wsHandler, attributes);
    }

    @Test
    @DisplayName("access_token 쿠키가 있으면 세션 속성에 토큰을 저장하고 핸드셰이크를 허용한다")
    void withAccessTokenCookie_storesTokenInAttributes() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", "cookie-token"));
        Map<String, Object> attributes = new HashMap<>();

        // when
        boolean allowed = handshake(request, attributes);

        // then
        assertThat(allowed).isTrue();
        assertThat(attributes)
                .containsEntry(AccessTokenHandshakeInterceptor.ACCESS_TOKEN_ATTR, "cookie-token");
    }

    @Test
    @DisplayName("access_token 쿠키가 없으면 세션 속성에 저장하지 않지만 핸드셰이크는 허용한다")
    void withoutAccessTokenCookie_noAttribute_handshakeAllowed() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "some-refresh"));
        Map<String, Object> attributes = new HashMap<>();

        // when
        boolean allowed = handshake(request, attributes);

        // then
        assertThat(allowed).isTrue();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("쿠키가 전혀 없어도 핸드셰이크는 허용한다")
    void noCookiesAtAll_handshakeAllowed() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        Map<String, Object> attributes = new HashMap<>();

        // when
        boolean allowed = handshake(request, attributes);

        // then
        assertThat(allowed).isTrue();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("access_token 쿠키 값이 비어 있으면 세션 속성에 저장하지 않는다")
    void blankAccessTokenCookie_noAttribute() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", ""));
        Map<String, Object> attributes = new HashMap<>();

        // when
        boolean allowed = handshake(request, attributes);

        // then
        assertThat(allowed).isTrue();
        assertThat(attributes).isEmpty();
    }
}
