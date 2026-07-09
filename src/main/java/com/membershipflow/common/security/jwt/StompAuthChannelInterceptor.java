package com.membershipflow.common.security.jwt;

import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * STOMP CONNECT 프레임의 Authorization 헤더에서 JWT를 검증해 세션 Principal을 설정한다.
 *
 * <p>Principal.getName()이 memberId 문자열을 반환하도록 만들어야
 * {@code SimpMessagingTemplate.convertAndSendToUser(memberId.toString(), ...)}가
 * 올바른 세션을 찾아 메시지를 전달할 수 있다.
 *
 * <p>토큰이 없거나 유효하지 않아도 연결 자체는 허용한다(익명 연결). 해당 세션은
 * 단순히 개인화된 알림(/user/queue/alert)을 받지 못할 뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = resolveToken(accessor);
            if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
                Long memberId = jwtTokenProvider.getMemberIdFromToken(token);
                Principal principal = new StompPrincipal(String.valueOf(memberId));
                accessor.setUser(principal);
                log.debug("WebSocket STOMP CONNECT authenticated: memberId={}", memberId);
            } else {
                log.debug("WebSocket STOMP CONNECT without valid token: anonymous session");
            }
        }

        return message;
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String bearer = accessor.getFirstNativeHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    private record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
