package com.membershipflow.collect.collector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.Security;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DongaHistoryCollector {

    @PostConstruct
    void init() {
        // 동아골프 서버가 512-bit DH 키를 사용하므로 해당 제한만 제거
        String current = Security.getProperty("jdk.tls.disabledAlgorithms");
        if (current != null) {
            String updated = Arrays.stream(current.split(","))
                    .map(String::trim)
                    .filter(s -> !s.startsWith("DH keySize"))
                    .collect(Collectors.joining(", "));
            Security.setProperty("jdk.tls.disabledAlgorithms", updated);
            log.info("[동아히스토리] DH keySize 제한 해제 완료");
        }
    }

    private static final String LISTING_URL   = "https://www.dongagolf.co.kr/membership/sise/";
    // 상세 페이지 차트가 내부적으로 쓰는 JSON API. type=m(월간) 응답은 약 2일 간격 포인트,
    // previousDay 필드로 과거 월로 페이지네이션 가능
    private static final String CHART_API_URL = "https://www.dongagolf.co.kr/api/chart.php?id=%s&code=%s&type=m&start=%s";
    private static final Pattern CUSTID_CODE_URL = Pattern.compile("custid=(\\d+)&code=(\\d+)");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yy/MM/dd");

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 종목당 과거로 조회할 개월 수 (서비스 차트 최대 기간인 1년에 맞춤)
    @Value("${app.collect.donga-history-months:12}")
    private int monthsBack;

    public record HistoricalPrice(
            String courseName,
            CourseType courseType,
            MembershipType membershipType,
            long price,
            LocalDateTime collectedAt
    ) {}

    public List<HistoricalPrice> collectAll() {
        List<CourseLink> links = fetchCourseLinks();
        log.info("[동아히스토리] 종목 {}개 수집 시작", links.size());

        List<HistoricalPrice> result = new ArrayList<>();
        int success = 0, fail = 0;

        for (CourseLink link : links) {
            try {
                result.addAll(fetchHistory(link));
                success++;
                Thread.sleep(300); // rate limit
            } catch (Exception e) {
                log.warn("[동아히스토리] 실패: {} - {}", link.name(), e.getMessage());
                fail++;
            }
        }

        log.info("[동아히스토리] 완료: 성공={}, 실패={}", success, fail);
        return result;
    }

    private List<CourseLink> fetchCourseLinks() {
        try {
            Document doc = Jsoup.connect(LISTING_URL)
                    .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                    .timeout(15_000)
                    .get();

            List<CourseLink> links = new ArrayList<>();
            // .html()은 &를 &amp;로 인코딩해 regex 미매칭 → Jsoup 선택자 + .attr()로 대체
            for (var a : doc.select("a[href*=/membership/info]")) {
                String href = a.attr("href");
                Matcher m = CUSTID_CODE_URL.matcher(href);
                if (m.find()) {
                    String name = a.text().trim();
                    if (!name.isBlank()) {
                        links.add(new CourseLink(m.group(1), m.group(2), name));
                    }
                }
            }
            return links;
        } catch (IOException e) {
            throw new CollectException("동아 목록 페이지 요청 실패", e);
        }
    }

    // 월간 차트 API를 previousDay로 과거 페이지네이션하며 monthsBack개월치 수집
    private List<HistoricalPrice> fetchHistory(CourseLink link) throws IOException, InterruptedException {
        // 이름 중간에 포함된 구분만 우선 추출 (null이면 CollectService에서
        // alias/CourseNameNormalizer 추출값 → REGULAR 순으로 결정)
        MembershipType membershipType = CourseNameNormalizer.extractEmbeddedType(link.name());
        List<HistoricalPrice> result = new ArrayList<>();

        String start = "";
        for (int month = 0; month < monthsBack; month++) {
            ChartResponse res = fetchChartPage(link, start);
            if (res == null || res.data() == null || res.data().isEmpty()) break;

            String courseName = res.data().get(0).name() != null && !res.data().get(0).name().isBlank()
                    ? res.data().get(0).name() : link.name();

            for (ChartPoint point : res.data()) {
                try {
                    LocalDate date = LocalDate.parse(point.date().trim(), FMT);
                    result.add(new HistoricalPrice(
                            courseName, CourseType.GOLF, membershipType,
                            point.price() * 10_000L, date.atTime(7, 0)));
                } catch (Exception e) {
                    log.debug("[동아히스토리] 포인트 파싱 실패: {} date={}", link.name(), point.date());
                }
            }

            if (res.previousDay() == null || res.previousDay().isBlank()) break;
            start = res.previousDay();
            Thread.sleep(200); // 같은 종목 내 페이지 간 rate limit
        }

        return result;
    }

    private ChartResponse fetchChartPage(CourseLink link, String start) throws IOException {
        String url = String.format(CHART_API_URL, link.custid(), link.code(), start);
        String body = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; MembershipFlowBot/1.0)")
                .ignoreContentType(true)
                .timeout(15_000)
                .execute()
                .body();
        return objectMapper.readValue(body, ChartResponse.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartResponse(List<ChartPoint> data, String previousDay, String nextDay) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartPoint(long price, String date, String name) {}

    private record CourseLink(String custid, String code, String name) {}
}
