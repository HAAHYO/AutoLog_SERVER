package com.autolog.server.domain.order.entity;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.global.entity.BaseTimeEntity;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import com.autolog.server.global.money.FxSide;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 환전 주문(fx_orders). 이번 묶음에서는 시장가 매수(KRW → 외화)만 만든다.
 * 체결 결과(시세, 적용환율, 우대율, 금액, tx_group)는 나중에 "왜 이 금액인가"를 재현할 수 있도록 함께 남긴다.
 */
@Entity
@Table(
		name = "fx_orders",
		uniqueConstraints = @UniqueConstraint(name = "uk_fx_orders_tx_group_id", columnNames = "tx_group_id"),
		indexes = @Index(name = "idx_fx_orders_user_id_id", columnList = "user_id, id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FxOrder extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	/** 출금 계좌 (매수면 KRW 계좌) */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, updatable = false)
	private Account account;

	@Enumerated(EnumType.STRING)
	@Column(name = "order_type", nullable = false, updatable = false, length = 10)
	private OrderType orderType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 4)
	private FxSide side;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_currency", nullable = false, updatable = false, length = 3)
	private CurrencyCode targetCurrency;

	/** 지정가 주문의 목표환율. 시장가는 null */
	@Column(name = "target_rate", updatable = false, precision = 19, scale = 8)
	private BigDecimal targetRate;

	@Column(name = "krw_amount", nullable = false, updatable = false, precision = 19, scale = 4)
	private BigDecimal krwAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OrderStatus status;

	@Column(name = "failure_reason", length = 50)
	private String failureReason;

	@Column(name = "base_rate", precision = 19, scale = 8)
	private BigDecimal baseRate;

	@Column(name = "applied_rate", precision = 19, scale = 8)
	private BigDecimal appliedRate;

	@Column(name = "spread_rate", precision = 7, scale = 6)
	private BigDecimal spreadRate;

	@Column(name = "preferential_rate", precision = 7, scale = 6)
	private BigDecimal preferentialRate;

	@Column(name = "rate_source", length = 30)
	private String rateSource;

	@Column(name = "rate_observed_at")
	private Instant rateObservedAt;

	@Column(name = "foreign_amount", precision = 19, scale = 4)
	private BigDecimal foreignAmount;

	@Column(name = "tx_group_id", length = 36)
	private String txGroupId;

	@Column(name = "executed_at")
	private Instant executedAt;

	@Version
	private Long version;

	private FxOrder(Long userId, Account account, OrderType orderType, FxSide side, CurrencyCode targetCurrency,
			BigDecimal targetRate, BigDecimal krwAmount) {
		this.userId = userId;
		this.account = account;
		this.orderType = orderType;
		this.side = side;
		this.targetCurrency = targetCurrency;
		this.targetRate = targetRate;
		this.krwAmount = krwAmount;
		this.status = OrderStatus.PENDING;
	}

	public static FxOrder marketBuy(Long userId, Account krwAccount, CurrencyCode targetCurrency, BigDecimal krwAmount) {
		return new FxOrder(userId, krwAccount, OrderType.MARKET, FxSide.BUY, targetCurrency, null, krwAmount);
	}

	/** 체결. 상태와 체결 결과를 함께 기록한다. */
	public void complete(Execution execution, Instant now) {
		requirePending();
		this.status = OrderStatus.COMPLETED;
		this.baseRate = execution.baseRate();
		this.appliedRate = execution.appliedRate();
		this.spreadRate = execution.spreadRate();
		this.preferentialRate = execution.preferentialRate();
		this.rateSource = execution.rateSource();
		this.rateObservedAt = execution.rateObservedAt();
		this.foreignAmount = execution.foreignAmount();
		this.txGroupId = execution.txGroupId();
		this.executedAt = now;
	}

	private void requirePending() {
		if (status.isTerminal()) {
			throw new BusinessException(ErrorCode.ORDER_NOT_PENDING);
		}
	}

	public record Execution(BigDecimal baseRate, BigDecimal appliedRate, BigDecimal spreadRate,
			BigDecimal preferentialRate, String rateSource, Instant rateObservedAt, BigDecimal foreignAmount,
			String txGroupId) {
	}
}
