package com.membershipflow.common.security.oauth;

import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2Provider;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.member.oauth.OAuth2UserInfoExtractor;
import com.membershipflow.member.oauth.OAuth2UserInfoExtractorFactory;
import com.membershipflow.member.service.AuthService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구글에서 받은 사용자 정보를 회원으로 저장/갱신하고 Principal로 변환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final AuthService authService;
    private final OAuth2UserInfoExtractorFactory extractorFactory;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        log.info("OAuth2 login attempt with provider: {}", registrationId);
        try {
            return process(registrationId, oAuth2User);
        } catch (OAuth2AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing OAuth2 user: {}", e.getMessage(), e);
            throw new OAuth2AuthenticationException("OAuth2 user processing failed");
        }
    }

    private OAuth2User process(String registrationId, OAuth2User oAuth2User) {
        Map<String, Object> attributes = oAuth2User.getAttributes();

        OAuth2Provider provider;
        try {
            provider = OAuth2Provider.fromRegistrationId(registrationId);
        } catch (IllegalArgumentException e) {
            throw new OAuth2AuthenticationException("Unsupported OAuth2 provider: " + registrationId);
        }

        OAuth2UserInfoExtractor extractor = extractorFactory.getExtractor(provider);
        if (!extractor.validateRequiredFields(attributes)) {
            throw new OAuth2AuthenticationException("Missing required user info from " + provider);
        }

        Member incoming = Member.builder()
                .provider(provider)
                .providerId(extractor.extractProviderId(attributes))
                .email(extractor.extractEmail(attributes))
                .name(extractor.extractName(attributes))
                .profileImageUrl(extractor.extractProfileImageUrl(attributes))
                .role(MemberRole.USER)
                .build();

        Member member = authService.saveOrUpdateOAuth2Member(incoming);
        log.info("OAuth2 login successful: {} ({})", member.getEmail(), provider);
        return new OAuth2UserPrincipal(member, attributes);
    }
}
