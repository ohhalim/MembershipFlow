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
public class DongbuCollector implements PriceCollector {

    private static final String SOURCE_NAME = "동부회원권";
    private static final String URL =
            "http://www.dbm-market.co.kr/동부회원권/골프회원권/시세";
    private static final String ROW_SELECTOR = "table.regtable tbody tr";

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

    // 헤더: [골프장][구분][홀수][전주시세][금주시세][등락]
    List<CollectedPrice> parse(Document doc) {
        Elements rows = doc.select(ROW_SELECTOR);
        if (rows.isEmpty()) {
            throw new CollectException(SOURCE_NAME + " 파싱 실패: 행 없음 (selector=" + ROW_SELECTOR + ")");
        }

        List<CollectedPrice> result = new ArrayList<>();
        for (Element row : rows) {
            Elements tds = row.select("td");
            if (tds.size() < 5) continue;

            String courseName    = tds.get(0).text().trim();
            String membershipRaw = tds.get(1).text().trim();  // 구분 (일반/주중/주주 등)
            String holesText     = tds.get(2).text().trim();  // 홀수
            String priceText     = tds.get(4).text().trim();  // 금주시세 (td[3]은 전주)

            if (courseName.isBlank() || priceText.isBlank() || priceText.equals("-")) continue;

            Integer holes = parseHoles(holesText);
            long    price = parsePrice(priceText);
            MembershipType membershipType = MembershipTypeMapper.map(membershipRaw);

            result.add(new CollectedPrice(
                    courseName, null, CourseType.GOLF,
                    membershipType, holes, price, SOURCE_NAME));
        }

        log.info("[{}] 파싱 완료: {}건", SOURCE_NAME, result.size());
        return result;
    }

    private Integer parseHoles(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)").matcher(text.trim());
        if (!m.find()) return null;
        try {
            int v = Integer.parseInt(m.group(1));
            return (v > 0 && v <= 255) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // "43,800" (만원) → 438,000,000 (원)
    private long parsePrice(String text) {
        String cleaned = text.replaceAll("[^0-9]", "");
        if (cleaned.isBlank()) throw new CollectException("가격 파싱 실패: " + text);
        return Long.parseLong(cleaned) * 10_000L;
    }
}
