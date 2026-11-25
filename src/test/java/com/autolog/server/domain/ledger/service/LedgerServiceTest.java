package com.autolog.server.domain.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.domain.ledger.entity.EntryType;
import com.autolog.server.domain.ledger.entity.LedgerEntry;
import com.autolog.server.domain.ledger.repository.LedgerEntryRepository;
import com.autolog.server.global.config.ClockConfig;
import com.autolog.server.global.config.JpaAuditingConfig;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import({LedgerService.class, LedgerReconciliationService.class, JpaAuditingConfig.class, ClockConfig.class})
@ActiveProfiles("test")
class LedgerServiceTest {

	@Autowired
	private LedgerService ledgerService;

	@Autowired
	private LedgerReconciliationService reconciliationService;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private TestEntityManager em;

	private Account userKrw;
	private Account userUsd;
	private Account clearingKrw;
	private Account positionKrw;
	private Account spreadKrw;
	private Account positionUsd;

	@BeforeEach
	void setUp() {
		userKrw = em.persist(Account.openUserAccount(1L, CurrencyCode.KRW));
		userUsd = em.persist(Account.openUserAccount(1L, CurrencyCode.USD));
		clearingKrw = em.persist(Account.openSystemAccount(AccountType.SYSTEM_EXTERNAL_CLEARING, CurrencyCode.KRW));
		positionKrw = em.persist(Account.openSystemAccount(AccountType.SYSTEM_FX_POSITION, CurrencyCode.KRW));
		spreadKrw = em.persist(Account.openSystemAccount(AccountType.SYSTEM_SPREAD_REVENUE, CurrencyCode.KRW));
		positionUsd = em.persist(Account.openSystemAccount(AccountType.SYSTEM_FX_POSITION, CurrencyCode.USD));
		em.flush();
	}

	@Test
	void 충전_후_환전하면_사용자_잔액과_balance_after가_원장과_일치한다() {
		deposit("deposit-1", "200000");
		exchangeUsd100("exchange-1");
		em.flush();
		em.clear();

		List<LedgerEntry> exchange = ledgerEntryRepository.findAllByTxGroupIdOrderByIdAsc("exchange-1");
		assertThat(exchange).hasSize(5);
		assertThat(exchange.get(0).getEntryType()).isEqualTo(EntryType.DEBIT);
		assertThat(exchange.get(0).getBalanceAfter()).isEqualByComparingTo("63500");
		assertThat(exchange.get(0).getAppliedRate()).isEqualByComparingTo("1365");
		assertThat(exchange.get(1).getBalanceAfter()).isNull();
		assertThat(exchange.get(4).getBalanceAfter()).isEqualByComparingTo("100");
		assertThat(exchange).allSatisfy(entry -> assertThat(entry.getCreatedAt()).isNotNull());

		AccountReconciliation krw = reconciliationService.reconcileAccount(userKrw.getId());
		AccountReconciliation usd = reconciliationService.reconcileAccount(userUsd.getId());
		assertThat(krw.matched()).isTrue();
		assertThat(krw.ledgerBalance()).isEqualByComparingTo("63500");
		assertThat(krw.cachedBalance()).isEqualByComparingTo("63500");
		assertThat(usd.matched()).isTrue();
		assertThat(usd.ledgerBalance()).isEqualByComparingTo("100");
	}

	@Test
	void 시스템_계좌는_원장_합계로만_잔액을_보고한다() {
		deposit("deposit-1", "200000");
		exchangeUsd100("exchange-1");

		AccountReconciliation position = reconciliationService.reconcileAccount(positionUsd.getId());
		AccountReconciliation spread = reconciliationService.reconcileAccount(spreadKrw.getId());

		assertThat(position.cachedBalance()).isNull();
		assertThat(position.matched()).as("시스템 계좌는 비교 불가").isNull();
		assertThat(position.ledgerBalance()).isEqualByComparingTo("-100");
		assertThat(spread.ledgerBalance()).isEqualByComparingTo("1500");
	}

	@Test
	void 전체_원장은_통화별로_차변과_대변이_같다() {
		deposit("deposit-1", "200000");
		exchangeUsd100("exchange-1");

		List<CurrencyBalance> balances = reconciliationService.verifyCurrencyBalances();

		assertThat(balances).extracting(CurrencyBalance::currency).containsExactly(CurrencyCode.KRW, CurrencyCode.USD);
		assertThat(balances).allSatisfy(b -> assertThat(b.balanced()).isTrue());
		assertThat(balances.get(0).debitTotal()).isEqualByComparingTo("336500");
	}

	@Test
	void 잔액_캐시가_원장과_어긋나면_대사에서_불일치로_잡힌다() {
		deposit("deposit-1", "200000");
		em.flush();
		em.getEntityManager()
				.createQuery("update Account a set a.balance = :balance where a.id = :id")
				.setParameter("balance", new BigDecimal("199999"))
				.setParameter("id", userKrw.getId())
				.executeUpdate();
		em.clear();

		AccountReconciliation result = reconciliationService.reconcileAccount(userKrw.getId());

		assertThat(result.matched()).isFalse();
		assertThat(result.cachedBalance()).isEqualByComparingTo("199999");
		assertThat(result.ledgerBalance()).isEqualByComparingTo("200000");
	}

	@Test
	void 잔액이_부족하면_예외가_나고_해당_계좌_잔액은_바뀌지_않는다() {
		deposit("deposit-1", "100000");

		assertThatThrownBy(() -> exchangeUsd100("exchange-1"))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
		assertThat(userKrw.getBalance()).isEqualByComparingTo("100000");
	}

	@Test
	void 같은_txGroupId로_다시_기록하면_거부한다() {
		deposit("deposit-1", "1000");

		assertThatThrownBy(() -> deposit("deposit-1", "1000"))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_DUPLICATE_TX_GROUP);
	}

	@Test
	void 라인_통화와_계좌_통화가_다르면_거부한다() {
		TxGroup wrongCurrency = TxGroup.builder("tx-1")
				.debit(clearingKrw.getId(), CurrencyCode.USD, new BigDecimal("10"))
				.credit(userUsd.getId(), CurrencyCode.USD, new BigDecimal("10"))
				.build();

		assertThatThrownBy(() -> ledgerService.post(wrongCurrency))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_INVALID_ENTRY);
	}

	@Test
	void 없는_계좌가_포함되면_거부한다() {
		TxGroup missing = TxGroup.builder("tx-1")
				.debit(clearingKrw.getId(), CurrencyCode.KRW, new BigDecimal("10"))
				.credit(999_999L, CurrencyCode.KRW, new BigDecimal("10"))
				.build();

		assertThatThrownBy(() -> ledgerService.post(missing))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
	}

	private void deposit(String txGroupId, String amount) {
		ledgerService.post(TxGroup.builder(txGroupId)
				.debit(clearingKrw.getId(), CurrencyCode.KRW, new BigDecimal(amount))
				.credit(userKrw.getId(), CurrencyCode.KRW, new BigDecimal(amount))
				.build());
	}

	private void exchangeUsd100(String txGroupId) {
		BigDecimal appliedRate = new BigDecimal("1365");
		ledgerService.post(TxGroup.builder(txGroupId)
				.debit(userKrw.getId(), CurrencyCode.KRW, new BigDecimal("136500"), appliedRate)
				.credit(positionKrw.getId(), CurrencyCode.KRW, new BigDecimal("135000"))
				.credit(spreadKrw.getId(), CurrencyCode.KRW, new BigDecimal("1500"))
				.debit(positionUsd.getId(), CurrencyCode.USD, new BigDecimal("100.00"))
				.credit(userUsd.getId(), CurrencyCode.USD, new BigDecimal("100.00"), appliedRate)
				.build());
	}
}
