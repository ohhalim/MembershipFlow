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

/**
 * 에이스회원권(acegolf.com) 골프회원권 시세 수집기.
 *
 * <p>메인 시세 페이지({@code /membership/golf_info_money.php})는 로그인을 요구하지만,
 * 그 안의 iframe 소스({@code i_golf_info_money.html})는 로그인 없이 전체 시세를 반환한다.
 *
 * <p>응답 인코딩은 EUC-KR이며 HTTP Content-Type 헤더에는 charset이 없고
 * {@code <meta http-equiv="Content-Type" content="text/html; charset=euc-kr">}로만
 * 선언되어 있다 — Jsoup이 meta 태그로 자동 감지하므로 별도 처리는 불필요하다.
 *
 * <p>동아골프처럼 구분이 코스명에 붙은 채로 내려온다("가야 우대", "강남300 주중개인").
 * 공백은 CourseNameNormalizer가 제거하므로 raw 이름 그대로 CollectService의
 * alias → normalizer 경로를 태운다.
 */
@Slf4j
@Component
public class AceGolfCollector implements PriceCollector {

    private static final String SOURCE_NAME = "에이스회원권";
    private static final String URL         = "https://www.acegolf.com/membership/i_golf_info_money.html";
    // 헤더 행 없이 tbody에 데이터 행만 존재.
    // 컬럼: [회원권명(td.cc)][금일시세][등락][등락율][시가표준액][그래프][상담] — 단위 만원
    // 시가표준액(td[4])은 이번 스코프 제외, 후속 이슈에서 별도 처리 예정
    private static final String ROW_SELECTOR = "table.list tbody tr";

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

    // "45,000" (만원) → 450,000,000 (원)
    private long parsePrice(String text) {
        String cleaned = text.replaceAll("[^0-9]", "");
        if (cleaned.isBlank()) throw new CollectException("가격 파싱 실패: " + text);
        return Long.parseLong(cleaned) * 10_000L;
    }
}
