package com.membershipflow.subscription.controller;

import com.membershipflow.common.config.SecurityConfig;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.common.security.jwt.JwtAuthenticationEntryPoint;
import com.membershipflow.common.security.jwt.JwtAuthenticationFilter;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.subscription.dto.*;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import com.membershipflow.subscription.service.SubscriptionService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SubscriptionController.class)
@Import(SecurityConfig.class)
class SubscriptionControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean SubscriptionService subscriptionService;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockitoBean com.membershipflow.common.security.oauth.CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationFailureHandler oAuth2FailureHandler;

    private static final Long MEMBER_ID = 1L;

    @BeforeEach
    void configureMocks() throws Exception {
        willAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).given(jwtAuthenticationFilter).doFilter(any(), any(), any());

        Member member = Member.builder()
                .id(MEMBER_ID)
                .email("test@test.com")
                .role(MemberRole.USER)
                .build();
        OAuth2UserPrincipal principal = new OAuth2UserPrincipal(member, Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    SubscriptionPlanResponse samplePlan() {
        return new SubscriptionPlanResponse(1L, "INDIVIDUAL", "개인 플랜", 49_000, "개인 구독자용 플랜");
    }

    SubscriptionResponse sampleSubscription() {
        return new SubscriptionResponse(
                10L,
                new SubscriptionResponse.PlanDto(1L, "INDIVIDUAL", "개인 플랜", 49_000),
                SubscriptionStatus.ACTIVE,
                LocalDateTime.of(2026, 6, 24, 0, 0),
                LocalDateTime.of(2026, 7, 24, 0, 0),
                "123456789012",
                "신한카드",
                null);
    }

    @Test
    @DisplayName("GET /api/v1/subscriptions/plans — 플랜 목록을 반환한다")
    void getPlans_returnsList() throws Exception {
        // given
        given(subscriptionService.getPlans()).willReturn(List.of(samplePlan()));

        // when / then
        mockMvc.perform(get("/api/v1/subscriptions/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("INDIVIDUAL"))
                .andExpect(jsonPath("$[0].price").value(49_000));
    }

    @Test
    @DisplayName("POST /api/v1/subscriptions/prepare — 빌링 준비 정보를 반환한다")
    void prepare_returnsBillingPrepare() throws Exception {
        // given
        given(subscriptionService.prepare(eq(MEMBER_ID), eq(1L)))
                .willReturn(new BillingPrepareResponse("customer-uuid", "test_ck_dummy", 1L));

        // when / then
        mockMvc.perform(post("/api/v1/subscriptions/prepare")
                        .param("planId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerKey").value("customer-uuid"))
                .andExpect(jsonPath("$.planId").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/subscriptions/prepare — 이미 구독 중이면 409를 반환한다")
    void prepare_alreadySubscribed_returns409() throws Exception {
        // given
        given(subscriptionService.prepare(eq(MEMBER_ID), anyLong()))
                .willThrow(new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS));

        // when / then
        mockMvc.perform(post("/api/v1/subscriptions/prepare")
                        .param("planId", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("GET /api/v1/subscriptions/callback — 콜백 처리 후 구독 정보를 반환한다")
    void callback_returnsSubscription() throws Exception {
        // given
        given(subscriptionService.handleCallback(eq("customer-uuid"), eq("auth-key")))
                .willReturn(sampleSubscription());

        // when / then
        mockMvc.perform(get("/api/v1/subscriptions/callback")
                        .param("customerKey", "customer-uuid")
                        .param("authKey", "auth-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.code").value("INDIVIDUAL"));
    }

    @Test
    @DisplayName("GET /api/v1/subscriptions/me — 내 구독 정보를 반환한다")
    void getMySubscription_returnsSubscription() throws Exception {
        // given
        given(subscriptionService.getMySubscription(MEMBER_ID)).willReturn(sampleSubscription());

        // when / then
        mockMvc.perform(get("/api/v1/subscriptions/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardCompany").value("신한카드"))
                .andExpect(jsonPath("$.nextBillingAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/subscriptions/me — 구독이 없으면 404를 반환한다")
    void getMySubscription_notFound_returns404() throws Exception {
        // given
        given(subscriptionService.getMySubscription(MEMBER_ID))
                .willThrow(new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        // when / then
        mockMvc.perform(get("/api/v1/subscriptions/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE /api/v1/subscriptions/me — 구독 해지 후 취소 정보를 반환한다")
    void cancel_returnsCancelResponse() throws Exception {
        // given
        CancelResponse cancelResp = new CancelResponse(
                10L,
                SubscriptionStatus.CANCELLED,
                LocalDateTime.of(2026, 6, 24, 12, 0),
                LocalDateTime.of(2026, 7, 24, 0, 0));
        given(subscriptionService.cancel(MEMBER_ID)).willReturn(cancelResp);

        // when / then
        mockMvc.perform(delete("/api/v1/subscriptions/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.serviceEndsAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/subscriptions/me/payments — 결제 내역을 반환한다")
    void getPaymentHistory_returnsList() throws Exception {
        // given
        PaymentHistoryResponse history = new PaymentHistoryResponse(
                1L, 49_000,
                com.membershipflow.subscription.entity.PaymentStatus.SUCCESS,
                LocalDateTime.of(2026, 6, 24, 0, 0),
                null, "개인 플랜");
        given(subscriptionService.getPaymentHistory(MEMBER_ID)).willReturn(List.of(history));

        // when / then
        mockMvc.perform(get("/api/v1/subscriptions/me/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(49_000))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].planName").value("개인 플랜"));
    }
}
