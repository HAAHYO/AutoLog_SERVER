package com.autolog.server.domain.ledger.repository;

import com.autolog.server.domain.ledger.entity.LedgerTxGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTxGroupRepository extends JpaRepository<LedgerTxGroup, String> {
}
