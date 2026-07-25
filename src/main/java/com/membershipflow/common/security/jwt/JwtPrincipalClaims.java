package com.membershipflow.common.security.jwt;

import com.membershipflow.member.entity.MemberRole;

public record JwtPrincipalClaims(Long memberId, String email, String name, MemberRole role) {
}
