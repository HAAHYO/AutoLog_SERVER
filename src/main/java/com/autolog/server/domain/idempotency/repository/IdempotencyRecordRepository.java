package com.autolog.server.domain.idempotency.repository;

import com.autolog.server.domain.idempotency.entity.IdempotencyRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

	Optional<IdempotencyRecord> findByUserIdAndIdemKey(Long userId, String idemKey);
}
