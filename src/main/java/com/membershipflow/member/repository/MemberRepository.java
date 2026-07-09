package com.membershipflow.member.repository;

import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2Provider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByProviderAndProviderId(OAuth2Provider provider, String providerId);

    Optional<Member> findByEmail(String email);

    // 관리자 알림 수신 대상 조회 (#159)
    List<Member> findAllByRole(MemberRole role);
}
