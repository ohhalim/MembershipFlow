package com.membershipflow.collect.controller;

import com.membershipflow.collect.service.CollectAsyncService;
import com.membershipflow.collect.service.CollectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/collect")
@RequiredArgsConstructor
public class CollectAdminController {

    private final CollectService collectService;
    private final CollectAsyncService collectAsyncService;

    @PostMapping
    public ResponseEntity<String> triggerCollect() {
        collectService.collectAll();
        return ResponseEntity.ok("수집 완료");
    }

    @PostMapping("/history")
    public ResponseEntity<String> triggerHistoryCollect() {
        collectAsyncService.collectHistoryAsync();
        return ResponseEntity.accepted().body("히스토리 수집 시작 (백그라운드 실행 중)");
    }

    @PostMapping("/info")
    public ResponseEntity<String> triggerCourseInfoCollect() {
        collectAsyncService.collectCourseInfoAsync();
        return ResponseEntity.accepted().body("골프장 부가정보 수집 시작 (백그라운드 실행 중)");
    }
}
