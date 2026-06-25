package com.membershipflow.member.entity;

/**
 * 회원 역할. Spring Security와 호환되도록 ROLE_ 접두사를 가진 authority를 제공한다.
 */
public enum MemberRole {
    USER("ROLE_USER");

    private final String authority;

    MemberRole(String authority) {
        this.authority = authority;
    }

    public String getAuthority() {
        return authority;
    }
}
