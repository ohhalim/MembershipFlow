package com.membershipflow.collect.collector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 주소 문자열에서 시/도 축약 지역명을 추출한다 (#141).
 * "경기도 용인시 ..." → "경기", "충청북도 청주시 ..." → "충북"
 * 매칭되는 시/도가 없으면 null.
 */
public final class RegionExtractor {

    private RegionExtractor() {}

    // 주소 접두어 → 축약 지역명. 긴 접두어(충청북 등)를 먼저 검사
    private static final Map<String, String> PREFIX_TO_REGION = new LinkedHashMap<>();
    static {
        PREFIX_TO_REGION.put("충청북", "충북");
        PREFIX_TO_REGION.put("충청남", "충남");
        PREFIX_TO_REGION.put("전라북", "전북");
        PREFIX_TO_REGION.put("전라남", "전남");
        PREFIX_TO_REGION.put("경상북", "경북");
        PREFIX_TO_REGION.put("경상남", "경남");
        PREFIX_TO_REGION.put("서울", "서울");
        PREFIX_TO_REGION.put("경기", "경기");
        PREFIX_TO_REGION.put("인천", "인천");
        PREFIX_TO_REGION.put("강원", "강원");
        PREFIX_TO_REGION.put("충북", "충북");
        PREFIX_TO_REGION.put("충남", "충남");
        PREFIX_TO_REGION.put("대전", "대전");
        PREFIX_TO_REGION.put("세종", "세종");
        PREFIX_TO_REGION.put("전북", "전북");
        PREFIX_TO_REGION.put("전남", "전남");
        PREFIX_TO_REGION.put("광주", "광주");
        PREFIX_TO_REGION.put("경북", "경북");
        PREFIX_TO_REGION.put("경남", "경남");
        PREFIX_TO_REGION.put("대구", "대구");
        PREFIX_TO_REGION.put("울산", "울산");
        PREFIX_TO_REGION.put("부산", "부산");
        PREFIX_TO_REGION.put("제주", "제주");
    }

    public static String extract(String address) {
        if (address == null || address.isBlank()) return null;
        String trimmed = address.trim();
        for (Map.Entry<String, String> e : PREFIX_TO_REGION.entrySet()) {
            if (trimmed.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
