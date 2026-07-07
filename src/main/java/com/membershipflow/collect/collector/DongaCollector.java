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
        return parse(doc);
    }

    // 헤더: [회원권명][금일시세][전일시세][등락][시세추이][상담]
    List<CollectedPrice> parse(Document doc) {
        Elements rows = doc.select(ROW_SELECTOR);
        if (rows.isEmpty()) {
            throw new CollectException(SOURCE_NAME + " 파싱 실패: 행 없음 (selector=" + ROW_SELECTOR + ")");
        }

        List<CollectedPrice> result = new ArrayList<>();
        for (Element row : rows) {
            Elements tds = row.select("td");
            if (tds.size() < 2) continue;

            String courseName = tds.get(0).text().trim();
            if (courseName.isBlank()) continue;

            String priceText = tds.get(1).text().trim();
            if (priceText.isBlank() || priceText.equals("-")) continue;

            long price;
            try {
                price = parsePrice(priceText);
            } catch (CollectException e) {
                log.warn("[{}] 가격 파싱 실패 - 종목: {}, 값: {}", SOURCE_NAME, courseName, priceText);
                continue;
            }

            // 이름 중간에 포함된 구분만 우선 추출 (null이면 CollectService에서
            // alias/CourseNameNormalizer 추출값 → REGULAR 순으로 결정)
            MembershipType membershipType = CourseNameNormalizer.extractEmbeddedType(courseName);

            result.add(new CollectedPrice(
                    courseName, null, CourseType.GOLF,
                    membershipType, null, price, SOURCE_NAME));
        }

        log.info("[{}] 파싱 완료: {}건", SOURCE_NAME, result.size());
        return result;
    }

    // "43,800" (만원) → 438,000,000 (원)
    private long parsePrice(String text) {
        String cleaned = text.replaceAll("[^0-9]", "");
        if (cleaned.isBlank()) throw new CollectException("가격 파싱 실패: " + text);
        return Long.parseLong(cleaned) * 10_000L;
    }
}
