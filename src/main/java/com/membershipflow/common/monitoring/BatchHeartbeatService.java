package com.membershipflow.common.monitoring;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatchHeartbeatService {

    public static final String COLLECT_BATCH = "collect";
    public static final String BILLING_BATCH = "billing";

    private final BatchHeartbeatRepository repository;

    public BatchHeartbeatService(BatchHeartbeatRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public long lastSuccessEpochSeconds(String batchName) {
        return repository.findById(batchName)
                .map(BatchHeartbeat::getLastSuccessEpochSeconds)
                .orElse(0L);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String batchName, long epochSeconds) {
        BatchHeartbeat heartbeat = repository.findById(batchName)
                .orElseGet(() -> new BatchHeartbeat(batchName, epochSeconds));
        heartbeat.update(epochSeconds);
        repository.save(heartbeat);
    }
}
