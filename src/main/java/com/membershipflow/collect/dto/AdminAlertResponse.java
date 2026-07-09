package com.membershipflow.collect.dto;

/**
 * 크롤러 이상 탐지 관리자 알림 페이로드 (#159).
 * WebSocket {@code /queue/admin-alert} destination으로 전송된다.
 *
 * @param type       "COLLECT_DROP" (수집량 급감) | "PRICE_OUTLIER" (거래소간 가격 이상치)
 * @param message    요약 메시지
 * @param courseId   가격 이상치일 때만 값 존재, 수집량 급감은 소스 단위라 null
 * @param courseName courseId와 짝을 이루는 코스명, 수집량 급감이면 null
 * @param detail     상세 수치 (이전/현재 건수 또는 median/편차 등)
 */
public record AdminAlertResponse(
        String type,
        String message,
        Long courseId,
        String courseName,
        String detail) {
}
