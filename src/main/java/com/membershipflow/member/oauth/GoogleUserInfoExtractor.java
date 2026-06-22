package com.membershipflow.member.oauth;

import com.membershipflow.member.entity.OAuth2Provider;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Google OAuth2(OpenID Connect) 사용자 정보 추출기.
 * Google userinfo는 고유 식별자로 "sub"를 제공한다.
 */
@Component
public class GoogleUserInfoExtractor implements OAuth2UserInfoExtractor {

    @Override
    public OAuth2Provider getSupportedProvider() {
        return OAuth2Provider.GOOGLE;
    }

    @Override
    public String extractProviderId(Map<String, Object> attributes) {
        return (String) attributes.get("sub");
    }

    @Override
    public String extractEmail(Map<String, Object> attributes) {
        return (String) attributes.get("email");
    }

    @Override
    public String extractName(Map<String, Object> attributes) {
        return (String) attributes.get("name");
    }

    @Override
    public String extractProfileImageUrl(Map<String, Object> attributes) {
        return (String) attributes.get("picture");
    }
}
