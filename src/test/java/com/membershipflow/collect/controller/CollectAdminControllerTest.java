package com.membershipflow.collect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.membershipflow.collect.service.CollectAsyncService;
import com.membershipflow.collect.service.CollectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CollectAdminControllerTest {

    private final CollectService collectService = mock(CollectService.class);
    private final CollectAsyncService collectAsyncService = mock(CollectAsyncService.class);
    private final CollectAdminController controller =
            new CollectAdminController(collectService, collectAsyncService);

    @Test
    void triggerCollect_returnsMessageDto() {
        var response = controller.triggerCollect();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("수집 완료");
        then(collectService).should().collectAll();
    }

    @Test
    void triggerHistoryCollect_returnsAcceptedMessageDto() {
        var response = controller.triggerHistoryCollect();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("히스토리 수집 시작");
        then(collectAsyncService).should().collectHistoryAsync();
    }

    @Test
    void triggerCourseInfoCollect_returnsAcceptedMessageDto() {
        var response = controller.triggerCourseInfoCollect();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("골프장 부가정보 수집 시작");
        then(collectAsyncService).should().collectCourseInfoAsync();
    }
}
