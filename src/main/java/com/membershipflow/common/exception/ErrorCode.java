package com.membershipflow.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST,       "INVALID_REQUEST",        "요청 형식이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED,          "UNAUTHORIZED",           "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN,                "FORBIDDEN",              "권한이 없습니다."),

    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND,         "MEMBER_NOT_FOUND",       "회원을 찾을 수 없습니다."),
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND,         "COURSE_NOT_FOUND",       "해당 종목을 찾을 수 없습니다."),
    WATCHLIST_NOT_FOUND(HttpStatus.NOT_FOUND,      "WATCHLIST_NOT_FOUND",    "관심 종목을 찾을 수 없습니다."),
    WATCHLIST_ALREADY_EXISTS(HttpStatus.CONFLICT,  "WATCHLIST_ALREADY_EXISTS","이미 관심 등록한 종목입니다."),
    WATCHLIST_LIMIT_EXCEEDED(HttpStatus.FORBIDDEN, "WATCHLIST_LIMIT_EXCEEDED","비구독자 찜 한도를 초과했습니다."),

    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND,        "SUBSCRIPTION_NOT_FOUND",       "구독 정보를 찾을 수 없습니다."),
    SUBSCRIPTION_ALREADY_EXISTS(HttpStatus.CONFLICT,    "SUBSCRIPTION_ALREADY_EXISTS",  "이미 구독 중입니다."),
    SUBSCRIPTION_REQUIRED(HttpStatus.FORBIDDEN,         "SUBSCRIPTION_REQUIRED",        "구독이 필요한 기능입니다."),
    BILLING_KEY_ISSUE_FAILED(HttpStatus.BAD_GATEWAY,    "BILLING_KEY_ISSUE_FAILED",     "빌링 키 발급에 실패했습니다."),
    PAYMENT_FAILED_ERROR(HttpStatus.PAYMENT_REQUIRED,   "PAYMENT_FAILED",               "결제에 실패했습니다."),

    INVALID_PRICE_RANGE(HttpStatus.BAD_REQUEST,    "INVALID_PRICE_RANGE",    "유효하지 않은 가격 범위입니다."),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST,      "INVALID_DATE_RANGE",     "유효하지 않은 날짜 범위입니다."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",       "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
