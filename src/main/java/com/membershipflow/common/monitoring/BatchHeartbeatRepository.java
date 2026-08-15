package com.membershipflow.common.monitoring;

import org.springframework.data.jpa.repository.JpaRepository;

interface BatchHeartbeatRepository extends JpaRepository<BatchHeartbeat, String> {
}
