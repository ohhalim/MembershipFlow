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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시세닷컴(m-sise.com) 골프회원권 시세 수집기.
 *
 * <p>목록 페이지({@code /page/siseGolfInfo.php})는 4개 구분(scate)으로 나뉘어 있고
 * (개인/법인/주중/무기명), 각 구분은 페이지네이션(약 20건/페이지)으로 제공된다.
 * 골프 외 콘도/휘트니스는 별도 URL({@code siseCondoInfo.php}, {@code siseFitnessInfo.php})을
 * 쓰므로 이 페이지에는 골프만 나오지만, 방어적으로 각 행의 {@code data-cate1} 속성이
 * "G"(골프)인지 확인해 필터링한다.
 */
@Slf4j
@Component
public class MsiseCollector implements PriceCollector {

    private static final String SOURCE_NAME = "시세닷컴";
    private static final String BASE_URL    = "http://www.m-sise.com/page/siseGolfInfo.php";

    // scate: P=개인(기본), C=법인, W=주중, UW=무기명 — 4개 구분 전체를 순회해야 전량 수집됨
    private static final String[] SCATES = {"P", "C", "W", "UW"};

    private static final String ROW_SELECTOR   = "table.regtable tbody tr";
    private static final String GOLF_CATEGORY  = "G";
    private static final int    MAX_PAGES_GUARD = 30; // 페이지네이션 무한루프 방지 안전장치

    private static final Pattern PAGE_NUM = Pattern.compile("page=(\\d+)");

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<CollectedPrice> collect() {
        List<CollectedPrice> result = new ArrayList<>();
        for (String scate : SCATES) {
            result.addAll(collectScate(scate));
        }
        log.info("[{}] 전체 파싱 완료: {}건", SOURCE_NAME, result.size());
        return result;
    }

    private List<CollectedPrice> collectScate(String scate) {
        List<CollectedPrice> result = new ArrayList<>();

        Document firstPage = fetch(scate, 1);
        result.addAll(parse(firstPage));

        int totalPages = Math.min(detectTotalPages(firstPage), MAX_PAGES_GUARD);
        for (int page = 2; page <= totalPages; page++) {
            Document doc = fetch(scate, page);
            result.addAll(parse(doc));
        }
        return result;
    }

    private Document fetch(String scate, int page) {
        String url = BASE_URL + "?scate=" + scate + "&page=" + page;
        try {
            return Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                    .timeout(15_000)
                    .get();
        } catch (IOException e) {
            throw new CollectException(SOURCE_NAME + " HTML 요청 실패 (scate=" + scate + ", page=" + page + ")", e);
        }
    }

    // 페이지네이션 nav에서 최대 page 번호 추출. nav가 없으면(결과 1페이지뿐) 1 반환
    int detectTotalPages(Document doc) {
        int max = 1;
        for (Element a : doc.select("nav.pg_wrap a.pg_page")) {
            Matcher m = PAGE_NUM.matcher(a.attr("href"));
            if (m.find()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max;
    }

    // 헤더: [골프장][구분][홀수][전주시세][금주시세][등락]
    List<CollectedPrice> parse(Document doc) {
        Elements rows = doc.select(ROW_SELECTOR);

        List<CollectedPrice> result = new ArrayList<>();
        for (Element row : rows) {
            Elements tds = row.select("td");
            if (tds.size() < 5) continue;

            Element nameCell = tds.get(0);
            Elements anchors  = nameCell.select("a");
            if (!anchors.isEmpty()) {
                String category = anchors.first().attr("data-cate1");
                if (!category.isBlank() && !GOLF_CATEGORY.equals(category)) {
                    continue; // 골프 외 종목(콘도/휘트니스 등) 방어적 제외
                }
            }

            String courseName    = nameCell.text().trim();
            String membershipRaw = tds.get(1).text().trim();  // 구분 (일반/우대/주주 등)
            String holesText     = tds.get(2).text().trim();  // 홀수
            String priceText     = tds.get(4).text().trim();  // 금주시세 (td[3]은 전주)

            if (courseName.isBlank() || priceText.isBlank() || priceText.equals("-")) continue;

            long price;
            try {
                price = parsePrice(priceText);
            } catch (CollectException e) {
                log.warn("[{}] 가격 파싱 실패 - 종목: {}, 값: {}", SOURCE_NAME, courseName, priceText);
                continue;
            }

            Integer holes = parseHoles(holesText);
            MembershipType membershipType = MembershipTypeMapper.map(membershipRaw);

            result.add(new CollectedPrice(
                    courseName, null, CourseType.GOLF,
                    membershipType, holes, price, SOURCE_NAME));
        }

        log.info("[{}] 파싱 완료: {}건", SOURCE_NAME, result.size());
        return result;
    }

    private Integer parseHoles(String text) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(text.trim());
        if (!m.find()) return null;
        try {
            int v = Integer.parseInt(m.group(1));
            return (v > 0 && v <= 255) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // "39,000" (만원) → 390,000,000 (원)
    private long parsePrice(String text) {
        String cleaned = text.replaceAll("[^0-9]", "");
        if (cleaned.isBlank()) throw new CollectException("가격 파싱 실패: " + text);
        return Long.parseLong(cleaned) * 10_000L;
    }
}
