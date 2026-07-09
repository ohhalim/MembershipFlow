package com.membershipflow.collect.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectAsyncService {

    private final CollectService collectService;

    @Async
    public void collectHistoryAsync() {
        try {
            int saved = collectService.collectHistory();
            log.info("[동아히스토리] 비동기 수집 완료: {}건", saved);
        } catch (Exception e) {
            log.error("[동아히스토리] 비동기 수집 실패: {}", e.getMessage(), e);
        }
    }

    @Async
    public void collectCourseInfoAsync() {
        try {
            int saved = collectService.collectCourseInfo();
            log.info("[동아부가정보] 비동기 수집 완료: {}건", saved);
        } catch (Exception e) {
            log.error("[동아부가정보] 비동기 수집 실패: {}", e.getMessage(), e);
        }
    }
}
