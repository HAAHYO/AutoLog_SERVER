package com.autolog.server.domain.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TxGroupTest {

	private static final long USER_KRW = 1L;
	private static final long POSITION_KRW = 2L;
	private static final long SPREAD_KRW = 3L;
	private static final long POSITION_USD = 4L;
	private static final long USER_USD = 5L;

	@Test
	void 통화별로_차변과_대변이_같으면_생성된다() {
		// USD 100 매수: 적용환율 1,365, 기준율 1,350, 스프레드 수익 1,500원 (docs/DOMAIN-MODEL.md 예시)
		TxGroup txGroup = TxGroup.builder("tx-1")
				.debit(USER_KRW, CurrencyCode.KRW, new BigDecimal("136500"), new BigDecimal("1365"))
				.credit(POSITION_KRW, CurrencyCode.KRW, new BigDecimal("135000"))
				.credit(SPREAD_KRW, CurrencyCode.KRW, new BigDecimal("1500"))
				.debit(POSITION_USD, CurrencyCode.USD, new BigDecimal("100.00"))
				.credit(USER_USD, CurrencyCode.USD, new BigDecimal("100.00"), new BigDecimal("1365"))
				.build();

		assertThat(txGroup.lines()).hasSize(5);
		assertThat(txGroup.txGroupId()).isEqualTo("tx-1");
	}

	@Test
	void scale만_다르고_값이_같으면_균형으로_본다() {
		TxGroup txGroup = TxGroup.builder("tx-1")
				.debit(POSITION_USD, CurrencyCode.USD, new BigDecimal("100"))
				.credit(USER_USD, CurrencyCode.USD, new BigDecimal("100.00"))
				.build();

		assertThat(txGroup.lines()).hasSize(2);
	}

	@Test
	void 한_통화라도_차대가_맞지_않으면_거부한다() {
		assertThatThrownBy(() -> TxGroup.builder("tx-1")
				.debit(USER_KRW, CurrencyCode.KRW, new BigDecimal("136500"))
				.credit(POSITION_KRW, CurrencyCode.KRW, new BigDecimal("135000"))
				.credit(SPREAD_KRW, CurrencyCode.KRW, new BigDecimal("1499"))
				.debit(POSITION_USD, CurrencyCode.USD, new BigDecimal("100.00"))
				.credit(USER_USD, CurrencyCode.USD, new BigDecimal("100.00"))
				.build())
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_UNBALANCED);
	}

	@Test
	void 통화가_다른_두_줄만으로는_균형이_성립하지_않는다() {
		// 원장 모델 A(사용자 계좌 쌍)는 채택하지 않았으므로 거부되어야 한다
		assertThatThrownBy(() -> TxGroup.builder("tx-1")
				.debit(USER_KRW, CurrencyCode.KRW, new BigDecimal("136500"))
				.credit(USER_USD, CurrencyCode.USD, new BigDecimal("100.00"))
				.build())
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_UNBALANCED);
	}

	@Test
	void 라인이_하나뿐이면_거부한다() {
		assertThatThrownBy(() -> TxGroup.builder("tx-1")
				.credit(USER_KRW, CurrencyCode.KRW, new BigDecimal("1000"))
				.build())
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_INVALID_ENTRY);
	}

	@Test
	void 금액이_0_이하이거나_통화_자릿수를_넘으면_거부한다() {
		assertThatThrownBy(() -> TxGroup.builder("tx-1")
				.debit(USER_KRW, CurrencyCode.KRW, BigDecimal.ZERO)
				.credit(POSITION_KRW, CurrencyCode.KRW, BigDecimal.ZERO)
				.build())
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_INVALID_ENTRY);

		assertThatThrownBy(() -> TxGroup.builder("tx-1")
				.debit(USER_KRW, CurrencyCode.KRW, new BigDecimal("0.5"))
				.credit(POSITION_KRW, CurrencyCode.KRW, new BigDecimal("0.5"))
				.build())
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_INVALID_ENTRY);
	}

	@Test
	void txGroupId가_비어_있으면_거부한다() {
		assertThatThrownBy(() -> TxGroup.builder(" ")
				.debit(POSITION_KRW, CurrencyCode.KRW, BigDecimal.ONE)
				.credit(USER_KRW, CurrencyCode.KRW, BigDecimal.ONE)
				.build())
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode")
				.isEqualTo(ErrorCode.LEDGER_INVALID_ENTRY);
	}
}
