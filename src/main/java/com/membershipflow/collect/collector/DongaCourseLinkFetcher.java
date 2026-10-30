package com.membershipflow.collect.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 동아골프 시세 목록 페이지에서 종목별 상세 페이지 링크(custid/code)를 수집한다.
 * DongaHistoryCollector(시세 히스토리)와 DongaInfoCollector(부가정보)가 공용으로 사용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DongaCourseLinkFetcher {

    static final String LISTING_URL = "https://www.dongagolf.co.kr/membership/sise/";
    private static final Pattern CUSTID_CODE_URL = Pattern.compile("custid=(\\d+)&code=(\\d+)");

    private final DongaHttpClient httpClient;

    public record CourseLink(String custid, String code, String name) {}

    public List<CourseLink> fetchCourseLinks() {
        try {
            Document doc = httpClient.getDocument(LISTING_URL);
            return parseLinks(doc);
        } catch (IOException e) {
            throw new CollectException("동아 목록 페이지 요청 실패", e);
        }
    }

    List<CourseLink> parseLinks(Document doc) {
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
    }
}
