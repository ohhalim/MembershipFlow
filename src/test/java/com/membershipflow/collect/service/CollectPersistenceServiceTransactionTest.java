package com.membershipflow.collect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.entity.CrawlType;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.collect.repository.CourseAliasRepository;
import com.membershipflow.collect.repository.CourseSourceMappingRepository;
import com.membershipflow.course.repository.CourseInfoRepository;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.repository.PriceHistoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringJUnitConfig(CollectPersistenceServiceTransactionTest.Config.class)
class CollectPersistenceServiceTransactionTest {

    @Autowired CollectPersistenceService persistenceService;
    @Autowired CourseAliasRepository courseAliasRepository;
    @Autowired CollectRunRepository collectRunRepository;

    @Test
    void saveCollectedPrices_runsInsideTransactionProxy() {
        CrawlSource source = CrawlSource.builder()
                .name("동부회원권")
                .baseUrl("https://example.com")
                .crawlType(CrawlType.JSOUP)
                .active(true)
                .build();
        CollectRun run = CollectRun.builder().source(source).parserVersion("1.0").build();

        given(courseAliasRepository.findAll()).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return List.of();
        });
        given(collectRunRepository.save(run)).willReturn(run);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        persistenceService.saveCollectedPrices(source, run, List.of());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        CollectPersistenceService collectPersistenceService(
                CourseAliasRepository courseAliasRepository,
                CollectRunRepository collectRunRepository,
                MembershipCourseRepository membershipCourseRepository,
                CourseInfoRepository courseInfoRepository,
                PriceHistoryRepository priceHistoryRepository,
                CourseSourceMappingRepository courseSourceMappingRepository) {
            return new CollectPersistenceService(
                    courseAliasRepository,
                    collectRunRepository,
                    membershipCourseRepository,
                    courseInfoRepository,
                    priceHistoryRepository,
                    courseSourceMappingRepository);
        }

        @Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean CourseAliasRepository courseAliasRepository() {
            return mock(CourseAliasRepository.class);
        }

        @Bean CollectRunRepository collectRunRepository() {
            return mock(CollectRunRepository.class);
        }

        @Bean MembershipCourseRepository membershipCourseRepository() {
            return mock(MembershipCourseRepository.class);
        }

        @Bean CourseInfoRepository courseInfoRepository() {
            return mock(CourseInfoRepository.class);
        }

        @Bean PriceHistoryRepository priceHistoryRepository() {
            return mock(PriceHistoryRepository.class);
        }

        @Bean CourseSourceMappingRepository courseSourceMappingRepository() {
            return mock(CourseSourceMappingRepository.class);
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
