package com.membershipflow.watchlist.service;

import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.repository.PriceHistoryRepository;
import com.membershipflow.watchlist.dto.AlertResponse;
import com.membershipflow.watchlist.entity.AlertLog;
import com.membershipflow.watchlist.entity.Watchlist;
import com.membershipflow.watchlist.repository.AlertLogRepository;
import com.membershipflow.watchlist.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final WatchlistRepository    watchlistRepository;
    private final AlertLogRepository     alertLogRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final SimpMessagingTemplate  messagingTemplate;

    /**
     * 수집 완료 후 호출 — 목표가 이하 종목에 대해 WebSocket 알림 발송.
     * 24시간 내 동일 watchlist에 중복 발송하지 않는다.
     */
    @Transactional
    public void checkAndNotify() {
        List<Watchlist> targets = watchlistRepository.findAllAlertEnabled();
        if (targets.isEmpty()) return;

        List<Long> courseIds = targets.stream()
                .map(w -> w.getCourse().getId())
                .distinct()
                .toList();

        // 과목별 최신가 맵 (source별 최신가 중 최저가 PriceHistory 선택)
        Map<Long, PriceHistory> lowestPhByCourse = priceHistoryRepository.findLatestByCourseIds(courseIds)
                .stream()
                .collect(Collectors.toMap(
                        ph -> ph.getCourse().getId(),
                        ph -> ph,
                        (a, b) -> a.getPrice() <= b.getPrice() ? a : b));

        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);

        for (Watchlist watchlist : targets) {
            PriceHistory triggerPh = lowestPhByCourse.get(watchlist.getCourse().getId());
            if (triggerPh == null) continue;
            if (triggerPh.getPrice() > watchlist.getTargetPrice()) continue;
            if (alertLogRepository.existsByWatchlistIdAndSentAtAfter(watchlist.getId(), cutoff)) continue;

            AlertLog alertLog = alertLogRepository.save(
                    AlertLog.builder()
                            .watchlist(watchlist)
                            .triggeredPrice(triggerPh.getPrice())
                            .source(triggerPh.getSource())
                            .build());

            sendWebSocketAlert(watchlist.getMember().getId(), AlertResponse.from(alertLog));

            log.info("[알림] memberId={} courseId={} price={}",
                    watchlist.getMember().getId(),
                    watchlist.getCourse().getId(),
                    triggerPh.getPrice());
        }
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> getAlerts(Long memberId) {
        return alertLogRepository.findByMemberIdOrderBySentAtDesc(memberId)
                .stream()
                .map(AlertResponse::from)
                .toList();
    }

    @Transactional
    public void markRead(Long alertId, Long memberId) {
        AlertLog log = alertLogRepository.findById(alertId)
                .filter(a -> a.getWatchlist().getMember().getId().equals(memberId))
                .orElseThrow(() -> new com.membershipflow.common.exception.BusinessException(
                        com.membershipflow.common.exception.ErrorCode.WATCHLIST_NOT_FOUND));
        log.markRead();
    }

    private void sendWebSocketAlert(Long memberId, AlertResponse response) {
        messagingTemplate.convertAndSendToUser(
                memberId.toString(),
                "/queue/alert",
                response);
    }
}
