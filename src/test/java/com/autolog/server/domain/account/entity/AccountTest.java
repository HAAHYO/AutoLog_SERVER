package com.autolog.server.domain.account.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountTest {

	@Test
	void 사용자_계좌는_입금과_출금을_잔액에_반영한다() {
		Account account = Account.openUserAccount(1L, CurrencyCode.KRW);

		assertThat(account.credit(new BigDecimal("10000"))).isEqualByComparingTo("10000");
		assertThat(account.debit(new BigDecimal("3000"))).isEqualByComparingTo("7000");
		assertThat(account.getBalance()).isEqualByComparingTo("7000");
	}

	@Test
	void 사용자_계좌의_잔액이_부족하면_출금을_거부하고_잔액은_그대로다() {
		Account account = Account.openUserAccount(1L, CurrencyCode.KRW);
		account.credit(new BigDecimal("1000"));

		assertThatThrownBy(() -> account.debit(new BigDecimal("1001")))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
		assertThat(account.getBalance()).isEqualByComparingTo("1000");
	}

	@Test
	void 잔액과_같은_금액은_출금할_수_있다() {
		Account account = Account.openUserAccount(1L, CurrencyCode.USD);
		account.credit(new BigDecimal("100.00"));

		assertThat(account.debit(new BigDecimal("100"))).isEqualByComparingTo("0");
	}

	@Test
	void 시스템_계좌는_잔액을_캐시하지_않고_출금을_막지_않는다() {
		Account position = Account.openSystemAccount(AccountType.SYSTEM_FX_POSITION, CurrencyCode.USD);

		assertThat(position.debit(new BigDecimal("100"))).isNull();
		assertThat(position.credit(new BigDecimal("50"))).isNull();
		assertThat(position.getBalance()).isEqualByComparingTo("0");
		assertThat(position.getUserId()).isNull();
	}

	@Test
	void 금액이_0_이하이면_거부한다() {
		Account account = Account.openUserAccount(1L, CurrencyCode.KRW);

		assertThatThrownBy(() -> account.credit(BigDecimal.ZERO))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_INVALID_ENTRY);
		assertThatThrownBy(() -> account.debit(new BigDecimal("-1")))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 계좌_종류와_소유자가_맞지_않으면_개설을_거부한다() {
		assertThatThrownBy(() -> Account.openUserAccount(null, CurrencyCode.KRW))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> Account.openSystemAccount(AccountType.USER, CurrencyCode.KRW))
				.isInstanceOf(BusinessException.class);
	}
}
