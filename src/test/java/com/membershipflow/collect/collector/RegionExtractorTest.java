package com.membershipflow.collect.collector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RegionExtractorTest {

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("주소 접두어에서 시/도 축약 지역명을 추출한다")
    @CsvSource({
            "서울특별시 강남구 테헤란로 1,          서울",
            "경기도 용인시 기흥구 석성로521번길 169, 경기",
            "인천광역시 중구 공항로 1,              인천",
            "강원특별자치도 춘천시 남산면 1,        강원",
            "강원도 원주시 지정면 1,                강원",
            "충청북도 청주시 상당구 1,              충북",
            "충청남도 천안시 동남구 1,              충남",
            "대전광역시 유성구 1,                   대전",
            "세종특별자치시 연기면 1,               세종",
            "전북특별자치도 군산시 1,               전북",
            "전라북도 익산시 1,                     전북",
            "전라남도 순천시 1,                     전남",
            "광주광역시 북구 1,                     광주",
            "경상북도 경주시 1,                     경북",
            "경상남도 김해시 1,                     경남",
            "대구광역시 수성구 1,                   대구",
            "울산광역시 울주군 1,                   울산",
            "부산광역시 기장군 1,                   부산",
            "제주특별자치도 서귀포시 1,             제주",
    })
    void extract_knownPrefixes_returnsShortRegion(String address, String expected) {
        assertThat(RegionExtractor.extract(address)).isEqualTo(expected);
    }

    @Test
    @DisplayName("시/도로 시작하지 않는 주소는 null을 반환한다")
    void extract_unknownPrefix_returnsNull() {
        assertThat(RegionExtractor.extract("용인시 기흥구 어딘가")).isNull();
    }

    @Test
    @DisplayName("null·빈 주소는 null을 반환한다")
    void extract_nullOrBlank_returnsNull() {
        assertThat(RegionExtractor.extract(null)).isNull();
        assertThat(RegionExtractor.extract("  ")).isNull();
    }
}
