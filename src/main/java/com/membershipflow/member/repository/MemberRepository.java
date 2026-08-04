package com.membershipflow.member.repository;

import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2Provider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);

    Optional<Member> findByProviderAndProviderId(OAuth2Provider provider, String providerId);

    Optional<Member> findByEmail(String email);

    // 관리자 알림 수신 대상 조회 (#159)
    List<Member> findAllByRole(MemberRole role);
}
