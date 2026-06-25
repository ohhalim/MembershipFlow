package com.membershipflow.collect.controller;

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

    @PostMapping
    public ResponseEntity<String> triggerCollect() {
        collectService.collectAll();
        return ResponseEntity.ok("수집 완료");
    }

    @PostMapping("/history")
    public ResponseEntity<String> triggerHistoryCollect() {
        int saved = collectService.collectHistory();
        return ResponseEntity.ok("히스토리 수집 완료: " + saved + "건");
    }
}
