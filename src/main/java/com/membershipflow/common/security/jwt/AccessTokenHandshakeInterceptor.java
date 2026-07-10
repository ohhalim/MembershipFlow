package com.membershipflow.common.security.jwt;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 핸드셰이크(HTTP) 요청의 access_token HttpOnly 쿠키를 세션 속성에 저장한다.
 *
 * <p>프론트가 HttpOnly 쿠키 방식으로 전환하면 JS에서 토큰을 읽을 수 없어 STOMP CONNECT
 * 프레임에 Authorization 헤더를 넣지 못한다. 핸드셰이크는 HTTP 요청이라 쿠키가 함께
 * 전송되므로, 여기서 토큰을 꺼내 두면 {@link StompAuthChannelInterceptor}가 CONNECT 시
 * 헤더가 없을 때 폴백으로 사용할 수 있다.
 *
 * <p>토큰이 없어도 핸드셰이크는 항상 허용한다(익명 연결 정책 유지).
 */
@Component
public class AccessTokenHandshakeInterceptor implements HandshakeInterceptor {

    /** 세션 속성에 저장되는 액세스 토큰 키. */
    public static final String ACCESS_TOKEN_ATTR = "ACCESS_TOKEN";

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response, @NonNull WebSocketHandler wsHandler,
            @NonNull Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            Cookie[] cookies = servletRequest.getServletRequest().getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if ("access_token".equals(cookie.getName())
                            && StringUtils.hasText(cookie.getValue())) {
                        attributes.put(ACCESS_TOKEN_ATTR, cookie.getValue());
                        break;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
            @NonNull ServerHttpResponse response, @NonNull WebSocketHandler wsHandler,
            @Nullable Exception exception) {
        // no-op
    }
}
