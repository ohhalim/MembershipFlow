package com.membershipflow.price.service;

import com.membershipflow.collect.entity.CourseSourceMapping;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.repository.CourseSourceMappingRepository;
import com.membershipflow.course.entity.MembershipCourse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * course_source_mapping(course_id+source_id+source_key)을 실제 원본 페이지 URL로 변환한다 (#144).
 *
 * <p>동아골프/에이스회원권은 종목별 상세 페이지가 있어 source_key로 URL을 조립하고,
 * 매핑이 없거나(동부회원권/시세닷컴은 개별 링크가 없어 매핑을 만들지 않음) 알 수 없는
 * 소스면 {@code crawl_source.base_url}로 폴백한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceUrlResolver {

    private static final String DONGA_SOURCE_NAME = "동아골프";
    private static final String ACE_SOURCE_NAME   = "에이스회원권";

    // source_key = "custid:code"
    private static final String DONGA_URL_FORMAT = "https://www.dongagolf.co.kr/membership/info?custid=%s&code=%s";
    // source_key = "code:m_id"
    private static final String ACE_URL_FORMAT = "https://www.acegolf.com/membership/golf_detail_info.php?code=%s&m_id=%s";

    private final CourseSourceMappingRepository courseSourceMappingRepository;

    public String resolve(MembershipCourse course, CrawlSource source) {
        return courseSourceMappingRepository.findByCourseAndSource(course, source)
                .map(mapping -> buildUrl(source, mapping))
                .orElse(source.getBaseUrl());
    }

    private String buildUrl(CrawlSource source, CourseSourceMapping mapping) {
        String[] parts = mapping.getSourceKey().split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            log.warn("[URL리졸버] source_key 형식 이상 - source={}, sourceKey={}",
                    source.getName(), mapping.getSourceKey());
            return source.getBaseUrl();
        }

        return switch (source.getName()) {
            case DONGA_SOURCE_NAME -> String.format(DONGA_URL_FORMAT, parts[0], parts[1]);
            case ACE_SOURCE_NAME   -> String.format(ACE_URL_FORMAT, parts[0], parts[1]);
            default -> source.getBaseUrl();
        };
    }
}
