package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.MembershipType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipTypeMapperTest {

    @ParameterizedTest(name = "''{0}'' → {1}")
    @CsvSource({
            "일반,         REGULAR",
            "일반 정회원,  REGULAR",
            "정회원,       REGULAR",
            "주중,         WEEKDAY",
            "주말,         WEEKEND",
            "가족,         FAMILY",
            "부부,         FAMILY",
            "개인,         INDIVIDUAL",
            "개인회원,     INDIVIDUAL",
            "법인,         CORPORATE",
            "주주,         SHAREHOLDER",
            "우대,         PREFERRED",
            "남자,         MALE",
            "남성,         MALE",
            "여자,         FEMALE",
            "여성,         FEMALE",
    })
    @DisplayName("알려진 type2 문자열은 대응 MembershipType으로 매핑된다")
    void knownRaw_mapsToExpected(String raw, MembershipType expected) {
        // when
        MembershipType result = MembershipTypeMapper.map(raw.trim());

        // then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "''{0}'' → REGULAR 폴백")
    @ValueSource(strings = {"알수없음", "특별회원", "", "  "})
    @DisplayName("알 수 없는 값은 REGULAR로 폴백한다")
    void unknownRaw_fallsBackToRegular(String raw) {
        // when
        MembershipType result = MembershipTypeMapper.map(raw);

        // then
        assertThat(result).isEqualTo(MembershipType.REGULAR);
    }

    @NullSource
    @ParameterizedTest
    @DisplayName("null 입력은 REGULAR를 반환한다")
    void nullRaw_returnsRegular(String raw) {
        // when
        MembershipType result = MembershipTypeMapper.map(raw);

        // then
        assertThat(result).isEqualTo(MembershipType.REGULAR);
    }
}
