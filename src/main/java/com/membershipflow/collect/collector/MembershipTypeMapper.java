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

        // 개인 계열 (단독 사용 시)
        if (s.equals("개인") || s.equals("개인회원")) return MembershipType.INDIVIDUAL;

        // 남성/여성
        if (s.equals("남자") || s.equals("남성")) return MembershipType.MALE;
        if (s.equals("여자") || s.equals("여성")) return MembershipType.FEMALE;

        // 일반 계열 (일반(2500), 일반(분1억), 정회원(분2억), 중부 일반 등)
        if (s.contains("일반") || s.contains("정회원") || s.equals("일반회원") || s.equals("일반 정회원"))
            return MembershipType.REGULAR;

        // 나머지 (분담금 단독 표기, 무기명, 1차·2차, 골프텔, 플러스 등) → REGULAR
        return MembershipType.REGULAR;
    }
}
