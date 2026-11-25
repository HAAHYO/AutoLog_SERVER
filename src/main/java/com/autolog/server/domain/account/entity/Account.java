package com.autolog.server.domain.account.entity;

import com.autolog.server.global.entity.BaseTimeEntity;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
		name = "accounts",
		uniqueConstraints = @UniqueConstraint(name = "uk_accounts_owner_currency_type", columnNames = {"owner_key", "currency", "type"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseTimeEntity {

	private static final String SYSTEM_OWNER_KEY = "SYSTEM";
	private static final String USER_OWNER_KEY_PREFIX = "USER:";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 시스템 계좌는 null */
	@Column(name = "user_id")
	private Long userId;

	/**
	 * 유니크 제약용 소유자 키. 사용자 계좌는 {@code USER:<userId>}, 시스템 계좌는 {@code SYSTEM}.
	 * user_id만으로는 시스템 계좌(NULL)의 중복을 막을 수 없어서 둔다(MySQL 유니크 키는 NULL끼리 다르다고 본다).
	 */
	@Column(name = "owner_key", nullable = false, updatable = false, length = 40)
	private String ownerKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 3)
	private CurrencyCode currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private AccountType type;

	/** 원장 잔액의 캐시. 사용자 계좌만 갱신한다. */
	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal balance;

	@Version
	private Long version;

	private Account(Long userId, CurrencyCode currency, AccountType type) {
		this.userId = userId;
		this.ownerKey = userId == null ? SYSTEM_OWNER_KEY : USER_OWNER_KEY_PREFIX + userId;
		this.currency = currency;
		this.type = type;
		this.balance = BigDecimal.ZERO;
	}

	public static Account openUserAccount(Long userId, CurrencyCode currency) {
		if (userId == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "사용자 계좌에는 userId가 필요합니다.");
		}
		return new Account(userId, currency, AccountType.USER);
	}

	public static Account openSystemAccount(AccountType type, CurrencyCode currency) {
		if (type.isUser()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "시스템 계좌 종류가 아닙니다: " + type);
		}
		return new Account(null, currency, type);
	}

	/**
	 * 출금(은행 관점의 DEBIT). 사용자 계좌는 잔액을 줄이고 음수를 막는다.
	 *
	 * @return 반영 후 잔액. 시스템 계좌는 잔액을 캐시하지 않으므로 null
	 */
	public BigDecimal debit(BigDecimal amount) {
		requirePositive(amount);
		if (!type.isUser()) {
			return null;
		}
		if (balance.compareTo(amount) < 0) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
		}
		balance = balance.subtract(amount);
		return balance;
	}

	/**
	 * 입금(은행 관점의 CREDIT).
	 *
	 * @return 반영 후 잔액. 시스템 계좌는 잔액을 캐시하지 않으므로 null
	 */
	public BigDecimal credit(BigDecimal amount) {
		requirePositive(amount);
		if (!type.isUser()) {
			return null;
		}
		balance = balance.add(amount);
		return balance;
	}

	private void requirePositive(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw new BusinessException(ErrorCode.LEDGER_INVALID_ENTRY, "금액은 0보다 커야 합니다: " + amount);
		}
	}
}
