package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.MembershipType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 크롤링한 원본 코스명을 정규화하고 이름에 붙은 구분(일반/우대/주주 등)을 추출한다.
 *
 * <p>동아골프는 구분이 코스명에 붙은 채로 내려오고("가야일반", "경주신라주주"),
 * 동부회원권은 구분 컬럼이 분리되어 있어("가야|우대") 같은 회원권이 서로 다른
 * 코스로 저장되는 문제를 해결하기 위한 공통 정규화 규칙.
 *
 * <p>규칙:
 * <ul>
 *   <li>괄호 밖의 공백·하이픈 제거, 이름 끝의 cc/CC/씨씨 제거</li>
 *   <li>이름 끝 구분 토큰(일반/우대/주주/남자/여자/주중/법인/VIP) 추출</li>
 *   <li>괄호 안이 구분 토큰 단독이면 추출 후 괄호 제거: "금강(일반)" → (금강, REGULAR)</li>
 *   <li>숫자가 든 괄호(분담금 tier)는 이름에 유지: "강동디아너스(18000)" 그대로
 *       (tier가 다르면 다른 상품)</li>
 *   <li>그 외 괄호((구.XX), (팔팔) 등)는 보수적으로 유지 — course_alias 테이블에서 처리</li>
 *   <li>토큰 추출 후 이름이 비면 원래 이름 유지</li>
 * </ul>
 */
public final class CourseNameNormalizer {

    private CourseNameNormalizer() {}

    /** 정규화 결과. type은 이름에서 구분을 추출하지 못하면 null */
    public record NormalizedCourse(String name, MembershipType type) {}

    // 이름 끝(또는 끝 괄호 단독)에 붙는 구분 토큰. 순서 유지(긴 토큰 우선 불필요 — 겹침 없음)
    private static final Map<String, MembershipType> TRAILING_TOKENS = new LinkedHashMap<>();
    static {
        TRAILING_TOKENS.put("일반", MembershipType.REGULAR);
        TRAILING_TOKENS.put("우대", MembershipType.PREFERRED);
        TRAILING_TOKENS.put("주주", MembershipType.SHAREHOLDER);
        TRAILING_TOKENS.put("남자", MembershipType.MALE);
        TRAILING_TOKENS.put("여자", MembershipType.FEMALE);
        TRAILING_TOKENS.put("주중", MembershipType.WEEKDAY);
        TRAILING_TOKENS.put("법인", MembershipType.CORPORATE);
        TRAILING_TOKENS.put("VIP", MembershipType.PREFERRED);
    }

    private static final Pattern TRAILING_CC    = Pattern.compile("(?i)(cc|씨씨)$");
    // 이름 끝의 중첩 없는 괄호 그룹: "서울(여자)" → group1="서울", group2="여자"
    private static final Pattern TRAILING_PAREN = Pattern.compile("^(.*)\\(([^()]*)\\)$");

    public static NormalizedCourse normalize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return new NormalizedCourse(rawName, null);
        }

        String name = stripSeparatorsOutsideParens(rawName.trim());
        name = TRAILING_CC.matcher(name).replaceFirst("");
        if (name.isBlank()) {
            return new NormalizedCourse(rawName.trim(), null);
        }

        // 1) 이름 끝 bare 토큰: "가야일반" → (가야, REGULAR)
        for (Map.Entry<String, MembershipType> e : TRAILING_TOKENS.entrySet()) {
            String token = e.getKey();
            if (endsWithToken(name, token)) {
                String stripped = name.substring(0, name.length() - token.length());
                if (stripped.isBlank()) {
                    // 토큰 추출 후 이름이 비면 원래 이름 유지
                    return new NormalizedCourse(name, null);
                }
                return new NormalizedCourse(stripped, e.getValue());
            }
        }

        // 2) 이름 끝 괄호 안이 토큰 단독: "금강(일반)" → (금강, REGULAR)
        //    숫자 tier·기타 괄호는 exact match 실패로 자연히 유지됨
        Matcher m = TRAILING_PAREN.matcher(name);
        if (m.matches()) {
            MembershipType type = TRAILING_TOKENS.get(m.group(2).trim());
            if (type != null && !m.group(1).isBlank()) {
                return new NormalizedCourse(m.group(1), type);
            }
        }

        return new NormalizedCourse(name, null);
    }

    /**
     * 코스명 중간에 포함된 구분 키워드로 회원권 종류를 추정한다 (동아골프 계열 공용).
     * "골드레이크주중(3000)"처럼 토큰이 이름 끝이 아닌 경우를 보완하며,
     * 판별 불가 시 null을 반환해 normalize() 추출값이 적용되도록 한다.
     */
    public static MembershipType extractEmbeddedType(String rawName) {
        if (rawName == null) return null;
        if (rawName.contains("주중")) return MembershipType.WEEKDAY;
        if (rawName.contains("주말")) return MembershipType.WEEKEND;
        if (rawName.contains("우대")) return MembershipType.PREFERRED;
        if (rawName.contains("법인")) return MembershipType.CORPORATE;
        if (rawName.contains("가족") || rawName.contains("부부")) return MembershipType.FAMILY;
        if (rawName.contains("개인")) return MembershipType.INDIVIDUAL;
        return null;
    }

    // 괄호 밖의 공백·하이픈만 제거 — 괄호 안("7600-32평", "포웰-20000" 등)은 의미가 있어 보존
    private static String stripSeparatorsOutsideParens(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            if (depth == 0 && (Character.isWhitespace(c) || c == '-')) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    // 라틴 토큰(VIP)은 앞 글자가 라틴 문자면 다른 단어의 일부("VVIP")로 보고 제외
    private static boolean endsWithToken(String name, String token) {
        if (!name.endsWith(token)) return false;
        int boundary = name.length() - token.length();
        if (boundary == 0) return true;
        char before = name.charAt(boundary - 1);
        if (Character.UnicodeBlock.of(token.charAt(0)) == Character.UnicodeBlock.BASIC_LATIN
                && Character.isLetter(before)
                && Character.UnicodeBlock.of(before) == Character.UnicodeBlock.BASIC_LATIN) {
            return false;
        }
        return true;
    }
}
