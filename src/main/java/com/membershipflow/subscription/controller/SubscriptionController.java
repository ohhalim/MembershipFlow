package com.membershipflow.subscription.controller;

import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.subscription.dto.*;
import com.membershipflow.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    /** 플랜 목록 */
    @GetMapping("/plans")
    public ResponseEntity<List<SubscriptionPlanResponse>> getPlans() {
        return ResponseEntity.ok(subscriptionService.getPlans());
    }

    /** 빌링 준비 (customerKey + clientKey 발급) */
    @PostMapping("/prepare")
    public ResponseEntity<BillingPrepareResponse> prepare(
            @AuthenticationPrincipal OAuth2UserPrincipal principal,
            @RequestParam Long planId) {
        return ResponseEntity.ok(subscriptionService.prepare(principal.getMember().getId(), planId));
    }

    /** 카드 등록 콜백 처리 */
    @GetMapping("/callback")
    public ResponseEntity<SubscriptionResponse> callback(
            @RequestParam String customerKey,
            @RequestParam String authKey) {
        return ResponseEntity.ok(subscriptionService.handleCallback(customerKey, authKey));
    }

    /** 내 구독 조회 */
    @GetMapping("/me")
    public ResponseEntity<SubscriptionResponse> getMySubscription(
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {
        return ResponseEntity.ok(subscriptionService.getMySubscription(principal.getMember().getId()));
    }

    /** 구독 해지 */
    @DeleteMapping("/me")
    public ResponseEntity<CancelResponse> cancel(
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {
        return ResponseEntity.ok(subscriptionService.cancel(principal.getMember().getId()));
    }

    /** 결제 내역 조회 */
    @GetMapping("/me/payments")
    public ResponseEntity<List<PaymentHistoryResponse>> getPaymentHistory(
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {
        return ResponseEntity.ok(subscriptionService.getPaymentHistory(principal.getMember().getId()));
    }
}
