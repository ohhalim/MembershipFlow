package com.membershipflow.member.oauth;

import com.membershipflow.member.entity.OAuth2Provider;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Provider별 추출기를 등록/조회하는 팩토리.
 * 새 provider 추출기를 @Component로 추가하면 자동 등록된다.
 */
@Slf4j
@Component
public class OAuth2UserInfoExtractorFactory {

    private final Map<OAuth2Provider, OAuth2UserInfoExtractor> extractorMap;

    public OAuth2UserInfoExtractorFactory(List<OAuth2UserInfoExtractor> extractors) {
        this.extractorMap = extractors.stream()
                .collect(Collectors.toMap(
                        OAuth2UserInfoExtractor::getSupportedProvider,
                        Function.identity()));
        log.info("Registered OAuth2 user info extractors: {}", extractorMap.keySet());
    }

    public OAuth2UserInfoExtractor getExtractor(OAuth2Provider provider) {
        OAuth2UserInfoExtractor extractor = extractorMap.get(provider);
        if (extractor == null) {
            throw new IllegalArgumentException("Unsupported OAuth2 provider: " + provider);
        }
        return extractor;
    }
}
