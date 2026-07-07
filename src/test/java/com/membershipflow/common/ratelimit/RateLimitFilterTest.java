package com.membershipflow.common.ratelimit;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private MockHttpServletResponse request(RateLimitFilter filter, String ip, String uri)
            throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    @DisplayName("한도 이내 요청은 통과한다")
    void underLimit_passes() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 5);

        for (int i = 0; i < 5; i++) {
            assertThat(request(filter, "1.2.3.4", "/api/v1/courses").getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("한도 초과 시 429를 반환한다")
    void overLimit_returns429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 3);

        for (int i = 0; i < 3; i++) {
            request(filter, "1.2.3.4", "/api/v1/courses");
        }
        MockHttpServletResponse res = request(filter, "1.2.3.4", "/api/v1/courses");

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    @DisplayName("IP별로 독립적으로 카운트한다")
    void countsPerIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 2);

        request(filter, "1.1.1.1", "/api/v1/courses");
        request(filter, "1.1.1.1", "/api/v1/courses");
        // 1.1.1.1은 한도 도달, 2.2.2.2는 영향 없음
        assertThat(request(filter, "1.1.1.1", "/api/v1/courses").getStatus()).isEqualTo(429);
        assertThat(request(filter, "2.2.2.2", "/api/v1/courses").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("actuator 경로는 제한하지 않는다")
    void actuator_isExcluded() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1);

        for (int i = 0; i < 10; i++) {
            assertThat(request(filter, "1.2.3.4", "/actuator/health").getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("비활성화 시 제한하지 않는다")
    void disabled_passesAll() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(false, 1);

        for (int i = 0; i < 10; i++) {
            assertThat(request(filter, "1.2.3.4", "/api/v1/courses").getStatus()).isEqualTo(200);
        }
    }
}
