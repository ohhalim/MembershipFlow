package com.membershipflow.member.entity;

/**
 * 지원하는 OAuth2 제공자.
 * 현재는 Google만 지원하며, 확장 시 enum 상수만 추가하면 된다.
 */
public enum OAuth2Provider {
    GOOGLE("google");

    private final String registrationId;

    OAuth2Provider(String registrationId) {
        this.registrationId = registrationId;
    }

    /** Spring Security registrationId (application.yml의 registration 키) */
    public String getRegistrationId() {
        return registrationId;
    }

    /**
     * registrationId(예: "google")로 enum을 찾는다.
     *
     * @throws IllegalArgumentException 지원하지 않는 provider인 경우
     */
    public static OAuth2Provider fromRegistrationId(String registrationId) {
        for (OAuth2Provider provider : values()) {
            if (provider.registrationId.equalsIgnoreCase(registrationId)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unsupported OAuth2 provider: " + registrationId);
    }
}
