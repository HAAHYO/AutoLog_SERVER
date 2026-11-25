package com.autolog.server.domain.ledger.entity;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.global.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원장 엔트리. append-only이므로 생성 뒤 바꾸는 메서드를 두지 않는다.
 * 정정은 반대 방향의 새 tx_group(역분개)으로 한다.
 */
@Entity
@Table(
		name = "ledger_entries",
		indexes = {
				@Index(name = "idx_ledger_entries_account_id_id", columnList = "account_id, id"),
				@Index(name = "idx_ledger_entries_tx_group_id", columnList = "tx_group_id")
		}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "tx_group_id", nullable = false, updatable = false, length = 36)
	private String txGroupId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, updatable = false)
	private Account account;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 3)
	private CurrencyCode currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false, updatable = false, length = 6)
	private EntryType entryType;

	@Column(nullable = false, updatable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	/** 반영 후 계좌 잔액. 잔액을 캐시하지 않는 시스템 계좌는 null */
	@Column(name = "balance_after", updatable = false, precision = 19, scale = 4)
	private BigDecimal balanceAfter;

	/** 환전 엔트리의 적용환율. 환전이 아닌 거래는 null */
	@Column(name = "applied_rate", updatable = false, precision = 19, scale = 8)
	private BigDecimal appliedRate;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private LedgerEntry(String txGroupId, Account account, CurrencyCode currency, EntryType entryType,
			BigDecimal amount, BigDecimal balanceAfter, BigDecimal appliedRate, Instant createdAt) {
		this.txGroupId = txGroupId;
		this.account = account;
		this.currency = currency;
		this.entryType = entryType;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.appliedRate = appliedRate;
		this.createdAt = createdAt;
	}

	public static LedgerEntry record(String txGroupId, Account account, EntryType entryType, BigDecimal amount,
			BigDecimal balanceAfter, BigDecimal appliedRate, Instant now) {
		return new LedgerEntry(txGroupId, account, account.getCurrency(), entryType, amount, balanceAfter, appliedRate, now);
	}
}
