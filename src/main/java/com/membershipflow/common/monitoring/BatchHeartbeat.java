package com.membershipflow.common.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "batch_heartbeat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatchHeartbeat {

    @Id
    @Column(name = "batch_name", length = 64)
    private String batchName;

    @Column(name = "last_success_epoch_seconds", nullable = false)
    private long lastSuccessEpochSeconds;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    BatchHeartbeat(String batchName, long lastSuccessEpochSeconds) {
        this.batchName = batchName;
        update(lastSuccessEpochSeconds);
    }

    void update(long lastSuccessEpochSeconds) {
        this.lastSuccessEpochSeconds = lastSuccessEpochSeconds;
        this.updatedAt = LocalDateTime.now();
    }
}
