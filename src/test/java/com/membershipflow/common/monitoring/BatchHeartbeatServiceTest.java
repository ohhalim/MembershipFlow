package com.membershipflow.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BatchHeartbeatServiceTest {

    @Mock BatchHeartbeatRepository repository;

    @Test
    void lastSuccessEpochSeconds_returnsStoredTimestamp() {
        BatchHeartbeat heartbeat = new BatchHeartbeat("collect", 1_786_744_815L);
        given(repository.findById("collect")).willReturn(Optional.of(heartbeat));

        long result = new BatchHeartbeatService(repository).lastSuccessEpochSeconds("collect");

        assertThat(result).isEqualTo(1_786_744_815L);
    }

    @Test
    void recordSuccess_createsHeartbeatWhenMissing() {
        given(repository.findById("billing")).willReturn(Optional.empty());

        new BatchHeartbeatService(repository).recordSuccess("billing", 1_786_719_600L);

        ArgumentCaptor<BatchHeartbeat> captor = ArgumentCaptor.captor();
        then(repository).should().save(captor.capture());
        assertThat(captor.getValue().getBatchName()).isEqualTo("billing");
        assertThat(captor.getValue().getLastSuccessEpochSeconds())
                .isEqualTo(1_786_719_600L);
    }
}
