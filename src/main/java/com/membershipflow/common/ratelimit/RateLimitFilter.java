package com.membershipflow.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IP당 분당 요청 수를 제한하는 고정 윈도우 rate limiter (in-memory, 단일 인스턴스 전제).
 * 한도 초과 시 429 Too Many Requests.
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_IPS = 10_000;

    private final boolean enabled;
    private final int requestsPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.requests-per-minute:120}") int requestsPerMinute) {
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 헬스체크·메트릭 수집은 제한 대상에서 제외
        return !enabled || request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        long currentMinute = System.currentTimeMillis() / 60_000L;

        Window window = windows.compute(ip, (k, w) ->
                (w == null || w.minute != currentMinute) ? new Window(currentMinute) : w);

        if (window.count.incrementAndGet() > requestsPerMinute) {
            log.warn("[RateLimit] 한도 초과: ip={}, uri={}", ip, request.getRequestURI());
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"code\":\"RATE_LIMITED\",\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\",\"status\":429}");
            return;
        }

        purgeIfNeeded(currentMinute);
        filterChain.doFilter(request, response);
    }

    // 추적 IP가 과도하게 쌓이면 지난 윈도우 항목 제거 (무한 증가 방지)
    private void purgeIfNeeded(long currentMinute) {
        if (windows.size() <= MAX_TRACKED_IPS) return;
        for (Map.Entry<String, Window> e : windows.entrySet()) {
            if (e.getValue().minute < currentMinute) {
                windows.remove(e.getKey(), e.getValue());
            }
        }
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
