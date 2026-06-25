package com.membershipflow.price.controller;

import com.membershipflow.price.dto.LatestSourcePriceResponse;
import com.membershipflow.price.dto.PriceChartResponse;
import com.membershipflow.price.service.PriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/courses/{courseId}/prices")
@RequiredArgsConstructor
public class PriceController {

    private final PriceService priceService;

    @GetMapping
    public ResponseEntity<PriceChartResponse> chart(
            @PathVariable Long courseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") String interval) {

        // Phase 4에서 JWT 추출 구독 여부 체크 추가. 현재는 전원 비구독 처리
        boolean isSubscriber = false;
        return ResponseEntity.ok(priceService.getChart(courseId, from, to, interval, isSubscriber));
    }

    @GetMapping("/latest")
    public ResponseEntity<List<LatestSourcePriceResponse>> latest(@PathVariable Long courseId) {
        return ResponseEntity.ok(priceService.getLatestBySource(courseId));
    }
}
