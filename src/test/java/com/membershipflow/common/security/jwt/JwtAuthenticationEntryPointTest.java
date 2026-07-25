package com.membershipflow.common.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;

class JwtAuthenticationEntryPointTest {

    @Test
    void commence_returnsCommonErrorResponseSchema() throws Exception {
        var entryPoint = new JwtAuthenticationEntryPoint(new ObjectMapper());
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        entryPoint.commence(request, response, mock(AuthenticationException.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}");
    }
}
