package com.membershipflow.collect.collector;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class DongaCollector implements PriceCollector {

    private static final String SOURCE_NAME = "동아골프";
    private static final String URL         = "https://www.dongagolf.co.kr/membership/sise/";
    // 헤더: [회원권명][금일시세][전일시세][등락][시세추이][상담]
    // class="list_even"이지만 class="price-data-list"(TOP10, 급등락)은 제외
    private static final String ROW_SELECTOR = "table.list_even:not(.price-data-list) tbody tr";

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<CollectedPrice> collect() {
        Document doc;
        try {
            doc = Jsoup.connect(URL)
                    .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                    .timeout(15_000)
                    .get();
        } catch (IOException e) {
            throw new CollectException(SOURCE_NAME + " HTML 요청 실패", e);
        }

        Elements rows = doc.select(ROW_SELECTOR);
        if (rows.isEmpty()) {
            throw new CollectException(SOURCE_NAME + " 파싱 실패: 행 없음 (selector=" + ROW_SELECTOR + ")");
        }

        List<CollectedPrice> result = new ArrayList<>();
        for (Element row : rows) {
            Elements tds = row.select("td");
            if (tds.size() < 2) continue;

            // 첫 번째 셀: 골프장명 (링크 텍스트)
            Element nameCell = tds.get(0);
            String courseName = nameCell.text().trim();
            if (courseName.isBlank()) continue;

            // 두 번째 셀: 금일시세 (만원 단위)
            String priceText = tds.get(1).text().trim();
            if (priceText.isBlank() || priceText.equals("-")) continue;

            long price;
            try {
                price = parsePrice(priceText);
            } catch (CollectException e) {
                log.warn("[{}] 가격 파싱 실패 - 종목: {}, 값: {}", SOURCE_NAME, courseName, priceText);
                continue;
            }

            // 동아는 코스명에 회원 종류가 내포됨 (예: "가야-주중", "가야 우대")
            MembershipType membershipType = extractMembershipType(courseName);

            result.add(new CollectedPrice(
                    courseName, null, CourseType.GOLF,
                    membershipType, null, price, SOURCE_NAME));
        }

        log.info("[{}] 수집 완료: {}건", SOURCE_NAME, result.size());
        return result;
    }

    // "43,800" (만원) → 438,000,000 (원)
    private long parsePrice(String text) {
        String cleaned = text.replaceAll("[^0-9]", "");
        if (cleaned.isBlank()) throw new CollectException("가격 파싱 실패: " + text);
        return Long.parseLong(cleaned) * 10_000L;
    }

    // 동아는 코스명 자체에 "주중", "주말", "우대" 등이 포함된 경우가 있음
    private MembershipType extractMembershipType(String courseName) {
        if (courseName.contains("주중")) return MembershipType.WEEKDAY;
        if (courseName.contains("주말")) return MembershipType.WEEKEND;
        if (courseName.contains("우대")) return MembershipType.PREFERRED;
        if (courseName.contains("법인")) return MembershipType.CORPORATE;
        if (courseName.contains("가족") || courseName.contains("부부")) return MembershipType.FAMILY;
        if (courseName.contains("개인")) return MembershipType.INDIVIDUAL;
        return MembershipType.REGULAR;
    }
}
