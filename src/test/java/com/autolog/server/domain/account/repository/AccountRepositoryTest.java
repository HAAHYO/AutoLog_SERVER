package com.autolog.server.domain.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.global.config.ClockConfig;
import com.autolog.server.global.config.JpaAuditingConfig;
import com.autolog.server.global.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import({JpaAuditingConfig.class, ClockConfig.class})
@ActiveProfiles("test")
class AccountRepositoryTest {

	@Autowired
	private AccountRepository accountRepository;

	@Test
	void 같은_종류와_통화의_시스템_계좌는_두_개_만들_수_없다() {
		accountRepository.saveAndFlush(Account.openSystemAccount(AccountType.SYSTEM_SPREAD_REVENUE, CurrencyCode.KRW));

		assertThatThrownBy(() -> accountRepository.saveAndFlush(
				Account.openSystemAccount(AccountType.SYSTEM_SPREAD_REVENUE, CurrencyCode.KRW)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 같은_사용자의_같은_통화_계좌는_두_개_만들_수_없다() {
		accountRepository.saveAndFlush(Account.openUserAccount(1L, CurrencyCode.USD));

		assertThatThrownBy(() -> accountRepository.saveAndFlush(Account.openUserAccount(1L, CurrencyCode.USD)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 사용자가_다르거나_통화가_다르면_만들_수_있다() {
		accountRepository.saveAndFlush(Account.openUserAccount(1L, CurrencyCode.USD));
		accountRepository.saveAndFlush(Account.openUserAccount(2L, CurrencyCode.USD));
		accountRepository.saveAndFlush(Account.openUserAccount(1L, CurrencyCode.JPY));

		assertThat(accountRepository.findByUserIdAndCurrencyAndType(2L, CurrencyCode.USD, AccountType.USER)).isPresent();
	}

	@Test
	void 시스템_계좌를_종류와_통화로_찾는다() {
		Account saved = accountRepository.saveAndFlush(
				Account.openSystemAccount(AccountType.SYSTEM_FX_POSITION, CurrencyCode.USD));

		assertThat(accountRepository.findByUserIdIsNullAndTypeAndCurrency(AccountType.SYSTEM_FX_POSITION, CurrencyCode.USD))
				.get()
				.extracting(Account::getId)
				.isEqualTo(saved.getId());
	}
}
