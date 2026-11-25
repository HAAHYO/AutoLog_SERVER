package com.autolog.server.domain.ledger.repository;

import com.autolog.server.domain.ledger.entity.EntryType;
import com.autolog.server.domain.ledger.entity.LedgerEntry;
import com.autolog.server.domain.ledger.service.CurrencyTotal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	List<LedgerEntry> findAllByTxGroupIdOrderByIdAsc(String txGroupId);

	Optional<LedgerEntry> findTopByAccountIdOrderByIdDesc(Long accountId);

	/** 계좌의 원장 잔액 = Σ CREDIT − Σ DEBIT */
	@Query("""
			select coalesce(sum(case when e.entryType = :credit then e.amount else -e.amount end), 0)
			from LedgerEntry e
			where e.account.id = :accountId
			""")
	BigDecimal sumNetByAccountId(@Param("accountId") Long accountId, @Param("credit") EntryType credit);

	@Query("""
			select new com.autolog.server.domain.ledger.service.CurrencyTotal(e.currency, e.entryType, sum(e.amount))
			from LedgerEntry e
			group by e.currency, e.entryType
			order by e.currency, e.entryType
			""")
	List<CurrencyTotal> sumByCurrencyAndEntryType();
}
