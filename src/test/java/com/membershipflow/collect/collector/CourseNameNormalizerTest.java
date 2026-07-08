package com.membershipflow.collect.collector;

import com.membershipflow.collect.collector.CourseNameNormalizer.NormalizedCourse;
import com.membershipflow.course.entity.MembershipType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CourseNameNormalizerTest {

    @ParameterizedTest(name = "''{0}'' → ({1}, {2})")
    @CsvSource({
            // 이름 끝 구분 토큰 추출
            "가야일반,       가야,       REGULAR",
            "가야우대,       가야,       PREFERRED",
            "경주신라주주,   경주신라,   SHAREHOLDER",
            "창원남자,       창원,       MALE",
            "창원여자,       창원,       FEMALE",
            "코리아주주,     코리아,     SHAREHOLDER",
            "파미힐스주주,   파미힐스,   SHAREHOLDER",
            "김포여자,       김포,       FEMALE",
            "안성일반,       안성,       REGULAR",
            // 하이픈 제거 후 토큰 추출
            "가야-주중,      가야,       WEEKDAY",
            "동래베네스트-남자, 동래베네스트, MALE",
            "덕유산-일반,    덕유산,     REGULAR",
            "발리오스-VIP,   발리오스,   PREFERRED",
            "강동디아너스-VIP, 강동디아너스, PREFERRED",
            // 괄호 안 구분 토큰 단독 → 추출 후 괄호 제거
            "금강(일반),     금강,       REGULAR",
            "울산(여자),     울산,       FEMALE",
            "울산(남자),     울산,       MALE",
            "서울(일반),     서울,       REGULAR",
            "팔공(일반),     팔공,       REGULAR",
    })
    @DisplayName("이름 끝·괄호 단독 구분 토큰을 추출하고 이름을 정규화한다")
    void normalize_extractsTrailingToken(String raw, String expectedName, MembershipType expectedType) {
        NormalizedCourse result = CourseNameNormalizer.normalize(raw);

        assertThat(result.name()).isEqualTo(expectedName);
        assertThat(result.type()).isEqualTo(expectedType);
    }

    @ParameterizedTest(name = "''{0}'' → ''{1}''")
    @CsvSource({
            // 끝의 cc/CC/씨씨 제거
            "88cc,           88",
            "블루원용인cc,   블루원용인",
            "오라cc,         오라",
            "비에이비스타CC, 비에이비스타",
            "가야씨씨,       가야",
            // 공백·하이픈 제거 (괄호 밖)
            "롯데스카이힐 제주,     롯데스카이힐제주",
            "레이크우드-프리빌리지, 레이크우드프리빌리지",
            "핀크스-1차,            핀크스1차",
    })
    @DisplayName("공백·하이픈·끝의 cc를 제거하며 구분 추출은 없으면 null")
    void normalize_cleansNameWithoutType(String raw, String expectedName) {
        NormalizedCourse result = CourseNameNormalizer.normalize(raw);

        assertThat(result.name()).isEqualTo(expectedName);
        assertThat(result.type()).isNull();
    }

    @ParameterizedTest(name = "''{0}'' 유지")
    @ValueSource(strings = {
            // 숫자 괄호(분담금 tier)는 다른 상품이므로 유지
            "강동디아너스(18000)",
            "가평베네스트(50000)",
            "렉스필드(50000)",
            // 구분 토큰이 아닌 괄호는 보수적으로 유지 (별칭 테이블에서 처리)
            "88(팔팔)",
            "안성베네스트(구.나다)",
            "블랙스톤(제주)",
            "여주주식(有)",
            // VVIP는 VIP 토큰과 다른 단어
            "용원(VVIP)",
            "동부산VVIP(30000)",
    })
    @DisplayName("숫자 tier·기타 괄호·VVIP는 이름에 유지된다")
    void normalize_keepsParenthesesConservatively(String raw) {
        NormalizedCourse result = CourseNameNormalizer.normalize(raw);

        assertThat(result.name()).isEqualTo(raw);
        assertThat(result.type()).isNull();
    }

    @Test
    @DisplayName("괄호 안의 공백·하이픈은 보존된다")
    void normalize_preservesSeparatorsInsideParentheses() {
        assertThat(CourseNameNormalizer.normalize("설악썬밸리(7600-32평)").name())
                .isEqualTo("설악썬밸리(7600-32평)");
        assertThat(CourseNameNormalizer.normalize("4WELL(포웰-20000)").name())
                .isEqualTo("4WELL(포웰-20000)");
        assertThat(CourseNameNormalizer.normalize("에이치원(H1)-일반(舊.덕평)").name())
                .isEqualTo("에이치원(H1)일반(舊.덕평)");
    }

    @Test
    @DisplayName("토큰이 이름 끝이 아니면 추출하지 않는다 (tier 괄호 뒤)")
    void normalize_tokenNotAtEnd_isKept() {
        NormalizedCourse result = CourseNameNormalizer.normalize("안성베네스트우대(13000)");

        assertThat(result.name()).isEqualTo("안성베네스트우대(13000)");
        assertThat(result.type()).isNull();
    }

    @Test
    @DisplayName("토큰 추출 후 이름이 비면 원래 이름을 유지한다")
    void normalize_tokenOnlyName_keepsOriginal() {
        NormalizedCourse result = CourseNameNormalizer.normalize("일반");

        assertThat(result.name()).isEqualTo("일반");
        assertThat(result.type()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("null·빈 문자열은 그대로 반환한다")
    void normalize_nullOrBlank_returnsAsIs(String raw) {
        NormalizedCourse result = CourseNameNormalizer.normalize(raw);

        assertThat(result.name()).isEqualTo(raw);
        assertThat(result.type()).isNull();
    }

    @ParameterizedTest(name = "''{0}'' → {1}")
    @CsvSource({
            "골드레이크주중(3000),   WEEKDAY",
            "레이크주말,             WEEKEND",
            "안성베네스트우대(13000), PREFERRED",
            "기흥법인,               CORPORATE",
            "지산하나로가족(6000),   FAMILY",
            "지산하나로부부(10000),  FAMILY",
            "캐슬렉스서울개인분담금, INDIVIDUAL",
    })
    @DisplayName("extractEmbeddedType은 이름 중간의 구분 키워드를 추출한다")
    void extractEmbeddedType_knownKeyword(String raw, MembershipType expected) {
        assertThat(CourseNameNormalizer.extractEmbeddedType(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"가야일반", "경주신라주주", "창원남자", "골드"})
    @DisplayName("extractEmbeddedType은 판별 불가 시 null을 반환한다")
    void extractEmbeddedType_unknown_returnsNull(String raw) {
        assertThat(CourseNameNormalizer.extractEmbeddedType(raw)).isNull();
    }
}
