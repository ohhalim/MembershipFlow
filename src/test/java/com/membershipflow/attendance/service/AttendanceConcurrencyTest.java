package com.membershipflow.attendance.service;

import com.membershipflow.attendance.controller.AttendanceController;
import com.membershipflow.attendance.repository.AttendanceRepository;
import com.membershipflow.attendance.repository.CouponRepository;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.member.entity.OAuth2Provider;
import com.membershipflow.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "attendance.event.start-date=2000-01-01",
        "attendance.event.end-date=2999-12-31"
})
@Testcontainers(disabledWithoutDocker = true)
class AttendanceConcurrencyTest {
    private static final int THREADS = 10;

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("membershipflow");

    @Autowired
    AttendanceController attendanceController;
    @Autowired
    AttendanceRepository attendanceRepository;
    @Autowired
    CouponRepository couponRepository;
    @Autowired
    MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        couponRepository.deleteAll();
        attendanceRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 회원이 동시에 출석해도 예외 없이 기록은 1개만 남는다")
    void attendConcurrentlyKeepsOneRowWithoutError() throws Exception {
        // given
        Member member = memberRepository.save(Member.builder()
                .email("race@test.com")
                .provider(OAuth2Provider.GOOGLE)
                .providerId("race")
                .build());
        OAuth2UserPrincipal principal = new OAuth2UserPrincipal(
                member.getId(), member.getEmail(), member.getName(), MemberRole.USER);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            try {
                attendanceController.attend(principal);
            } catch (Throwable e) {
                errors.add(e);
            }
            return null;
        };

        // when
        List<Future<Void>> futures = IntStream.range(0, THREADS)
                .mapToObj(i -> executor.submit(task))
                .toList();
        ready.await();
        start.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();

        // then
        assertThat(attendanceRepository.count()).isEqualTo(1);
        assertThat(errors).isEmpty();
    }
}
