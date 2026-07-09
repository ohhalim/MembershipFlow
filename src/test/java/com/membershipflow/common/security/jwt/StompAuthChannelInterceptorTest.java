package com.membershipflow.common.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    StompAuthChannelInterceptor interceptor;

    MessageChannel channel = mock(MessageChannel.class);

    private Message<byte[]> connectMessage(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.addNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> messageWithCommand(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("CONNECT 프레임에 유효한 토큰이 있으면 Principal이 memberId로 설정된다")
    void connectWithValidToken_setsPrincipal() {
        // given
        given(jwtTokenProvider.validateToken("valid-token")).willReturn(true);
        given(jwtTokenProvider.getMemberIdFromToken("valid-token")).willReturn(42L);
        Message<byte[]> message = connectMessage("Bearer valid-token");

        // when
        Message<?> result = interceptor.preSend(message, channel);

        // then
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        Principal user = resultAccessor.getUser();
        assertThat(user).isNotNull();
        assertThat(user.getName()).isEqualTo("42");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("CONNECT 프레임에 토큰이 없으면 Principal은 설정되지 않지만 메시지는 통과한다")
    void connectWithoutToken_noPrincipal_messagePasses() {
        // given
        Message<byte[]> message = connectMessage(null);

        // when
        Message<?> result = interceptor.preSend(message, channel);

        // then
        assertThat(result).isNotNull();
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertThat(resultAccessor.getUser()).isNull();
        then(jwtTokenProvider).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("CONNECT 프레임에 유효하지 않은 토큰이 있으면 Principal은 설정되지 않지만 메시지는 통과한다")
    void connectWithInvalidToken_noPrincipal_messagePasses() {
        // given
        given(jwtTokenProvider.validateToken("bad-token")).willReturn(false);
        Message<byte[]> message = connectMessage("Bearer bad-token");

        // when
        Message<?> result = interceptor.preSend(message, channel);

        // then
        assertThat(result).isNotNull();
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertThat(resultAccessor.getUser()).isNull();
    }

    @Test
    @DisplayName("Bearer 접두사 없는 헤더는 토큰으로 인식하지 않는다")
    void connectWithoutBearerPrefix_noPrincipal() {
        // given
        Message<byte[]> message = connectMessage("Basic abc123");

        // when
        Message<?> result = interceptor.preSend(message, channel);

        // then
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertThat(resultAccessor.getUser()).isNull();
        then(jwtTokenProvider).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("CONNECT가 아닌 커맨드는 검증 없이 그대로 통과한다")
    void nonConnectCommand_passesThrough() {
        // given
        Message<byte[]> message = messageWithCommand(StompCommand.SEND);

        // when
        Message<?> result = interceptor.preSend(message, channel);

        // then
        assertThat(result).isSameAs(message);
        then(jwtTokenProvider).shouldHaveNoInteractions();
    }
}
