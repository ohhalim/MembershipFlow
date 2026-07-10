package com.membershipflow.price.controller;

import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.price.dto.LatestSourcePriceResponse;
import com.membershipflow.price.dto.PriceChartResponse;
import com.membershipflow.price.service.PriceService;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/courses/{courseId}/prices")
@RequiredArgsConstructor
public class PriceController {

    private final PriceService priceService;
    private final SubscriptionRepository subscriptionRepository;

    @GetMapping
    public ResponseEntity<PriceChartResponse> chart(
            @PathVariable Long courseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") String interval,
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {

        // isActive(): 취소해도 nextBillingAt(이미 결제한 기간)까지는 구독자로 취급 (#180)
        boolean isSubscriber = principal != null && subscriptionRepository
                .findByMemberId(principal.getMemberId())
                .map(Subscription::isActive)
                .orElse(false);

        return ResponseEntity.ok(priceService.getChart(courseId, from, to, interval, isSubscriber));
    }

    @GetMapping("/latest")
    public ResponseEntity<List<LatestSourcePriceResponse>> latest(@PathVariable Long courseId) {
        return ResponseEntity.ok(priceService.getLatestBySource(courseId));
    }
}
