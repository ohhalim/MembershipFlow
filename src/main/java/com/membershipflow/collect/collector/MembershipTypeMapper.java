package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.MembershipType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MembershipTypeMapper {

    private MembershipTypeMapper() {}

    public static MembershipType map(String raw) {
        if (raw == null) return MembershipType.REGULAR;
        return switch (raw.trim()) {
            case "일반", "일반 정회원", "정회원", "일반회원" -> MembershipType.REGULAR;
            case "주중"                               -> MembershipType.WEEKDAY;
            case "주말"                               -> MembershipType.WEEKEND;
            case "가족", "부부", "가족회원"              -> MembershipType.FAMILY;
            case "개인", "개인회원"                    -> MembershipType.INDIVIDUAL;
            case "법인", "법인회원"                    -> MembershipType.CORPORATE;
            case "주주", "주주회원"                    -> MembershipType.SHAREHOLDER;
            case "우대", "우대회원"                    -> MembershipType.PREFERRED;
            case "남자", "남성"                        -> MembershipType.MALE;
            case "여자", "여성"                        -> MembershipType.FEMALE;
            default -> {
                log.warn("알 수 없는 MembershipType 값: '{}' → REGULAR 폴백", raw);
                yield MembershipType.REGULAR;
            }
        };
    }
}
