package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.MembershipType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MembershipTypeMapper {

    private MembershipTypeMapper() {}

    public static MembershipType map(String raw) {
        if (raw == null) return MembershipType.REGULAR;
        String s = raw.trim();

        // 주중 계열 (주중개인, 주중가족, 주중(남자), 주중(분2500) 등)
        if (s.startsWith("주중")) return MembershipType.WEEKDAY;

        // 주말 계열
        if (s.startsWith("주말")) return MembershipType.WEEKEND;

        // 가족 계열 (가족분담금 등)
        if (s.startsWith("가족") || s.equals("부부") || s.equals("가족회원")) return MembershipType.FAMILY;

        // VIP / 로얄 → PREFERRED
        if (s.startsWith("VIP") || s.equals("로얄") || s.equals("우대") || s.equals("우대회원"))
            return MembershipType.PREFERRED;

        // 주주 / 주권 / 주식 → SHAREHOLDER
        if (s.equals("주주") || s.equals("주주회원") || s.equals("주권") || s.equals("주식"))
            return MembershipType.SHAREHOLDER;

        // 법인 계열
        if (s.startsWith("법인")) return MembershipType.CORPORATE;

        // 남성/여성 ("개인 남자" 등 접두 표기 포함 — 개인 계열보다 먼저 판별)
        if (s.equals("남자") || s.equals("남성") || s.endsWith(" 남자")) return MembershipType.MALE;
        if (s.equals("여자") || s.equals("여성") || s.endsWith(" 여자")) return MembershipType.FEMALE;

        // 개인 계열 (단독 사용 시) — 개인 정회원은 일반과 동일 상품으로 취급
        // (88cc·기흥 등 동아 REGULAR 코스와 거래소 간 통합 목적)
        if (s.equals("개인") || s.equals("개인회원")) return MembershipType.REGULAR;

        // 일반 계열 (일반(2500), 일반(분1억), 정회원(분2억), 중부 일반 등)
        if (s.contains("일반") || s.contains("정회원") || s.equals("일반회원") || s.equals("일반 정회원"))
            return MembershipType.REGULAR;

        // 나머지 (분담금 단독 표기, 무기명, 1차·2차, 골프텔, 플러스 등) → REGULAR
        return MembershipType.REGULAR;
    }
}
