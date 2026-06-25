package com.membershipflow.collect.scheduler;

import com.membershipflow.collect.service.CollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorScheduler {

    private final CollectService collectService;

    // 매일 오전 7시 (동부·동아 모두 주간 시세)
    @Scheduled(cron = "0 0 7 * * *")
    public void scheduledCollect() {
        log.info("수집 스케줄러 시작");
        collectService.collectAll();
        log.info("수집 스케줄러 완료");
    }
}
