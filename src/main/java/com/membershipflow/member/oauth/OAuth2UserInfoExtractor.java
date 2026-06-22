package com.membershipflow.member.oauth;

import com.membershipflow.member.entity.OAuth2Provider;
import java.util.Map;

/**
 * OAuth2 Provider별 사용자 정보 추출 인터페이스.
 * provider마다 attribute 키가 다르므로 구현체로 분리한다.
 */
public interface OAuth2UserInfoExtractor {

    OAuth2Provider getSupportedProvider();

    String extractProviderId(Map<String, Object> attributes);

    String extractEmail(Map<String, Object> attributes);

    String extractName(Map<String, Object> attributes);

    String extractProfileImageUrl(Map<String, Object> attributes);

    default boolean validateRequiredFields(Map<String, Object> attributes) {
        return extractProviderId(attributes) != null
                && extractEmail(attributes) != null
                && extractName(attributes) != null;
    }
}
