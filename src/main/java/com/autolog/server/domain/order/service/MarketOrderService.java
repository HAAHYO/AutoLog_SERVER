package com.autolog.server.domain.order.service;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.domain.account.service.SystemAccountService;
import com.autolog.server.domain.idempotency.service.IdempotencyService;
import com.autolog.server.domain.ledger.service.LedgerService;
import com.autolog.server.domain.ledger.service.TxGroup;
import com.autolog.server.domain.order.dto.request.MarketBuyRequest;
import com.autolog.server.domain.order.dto.response.MarketOrderResponse;
import com.autolog.server.domain.order.entity.FxOrder;
import com.autolog.server.domain.order.repository.FxOrderRepository;
import com.autolog.server.domain.rate.service.CurrentRateProvider;
import com.autolog.server.domain.rate.service.FxRateProperties;
import com.autolog.server.domain.rate.service.RateQuote;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시장가 매수. 주문 생성, 원장 전기(모델 B, 5줄), 주문 체결 기록을 한 트랜잭션에서 한다.
 * 잔액이 부족하면 트랜잭션 전체가 롤백되고 주문도 남지 않는다(즉시 주문이므로 FAILED 행을 남기지 않음).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketOrderService {

	private final IdempotencyService idempotencyService;
	private final CurrentRateProvider rateProvider;
	private final FxRateProperties rateProperties;
	private final MarketBuyCalculator marketBuyCalculator;
	private final LedgerService ledgerService;
	private final SystemAccountService systemAccountService;
	private final AccountRepository accountRepository;
	private final FxOrderRepository fxOrderRepository;
	private final Clock clock;

	@Transactional
	public MarketOrderResponse buy(Long userId, Long krwAccountId, Long foreignAccountId, String idemKey,
			MarketBuyRequest request) {
		return idempotencyService.execute(userId, idemKey, request, MarketOrderResponse.class,
				() -> executeBuy(userId, krwAccountId, foreignAccountId, request));
	}

	private MarketOrderResponse executeBuy(Long userId, Long krwAccountId, Long foreignAccountId, MarketBuyRequest request) {
		CurrencyCode target = request.targetCurrency();
		BigDecimal preferentialRate = rateProperties.defaultPreferentialRate();
		RateQuote quote = rateProvider.getQuote(target);
		MarketBuyCalculator.Result result = marketBuyCalculator.calculate(request.krwAmount(), quote, preferentialRate);

		Account krwAccount = accountRepository.getReferenceById(krwAccountId);
		FxOrder order = fxOrderRepository.save(FxOrder.marketBuy(userId, krwAccount, target, request.krwAmount()));

		String txGroupId = UUID.randomUUID().toString();
		TxGroup.Builder lines = TxGroup.builder(txGroupId)
				.debit(krwAccountId, CurrencyCode.KRW, request.krwAmount(), result.appliedRate())
				.credit(systemAccountService.getId(AccountType.SYSTEM_FX_POSITION, CurrencyCode.KRW), CurrencyCode.KRW,
						result.positionKrw());
		if (result.spreadKrw().signum() > 0) {
			lines.credit(systemAccountService.getId(AccountType.SYSTEM_SPREAD_REVENUE, CurrencyCode.KRW), CurrencyCode.KRW,
					result.spreadKrw());
		}
		lines.debit(systemAccountService.getId(AccountType.SYSTEM_FX_POSITION, target), target, result.foreignAmount())
				.credit(foreignAccountId, target, result.foreignAmount(), result.appliedRate());
		ledgerService.post(lines.build());

		Instant now = Instant.now(clock);
		order.complete(new FxOrder.Execution(quote.baseRate(), result.appliedRate(), quote.spreadRate(), preferentialRate,
				quote.source(), quote.observedAt(), result.foreignAmount(), txGroupId), now);
		fxOrderRepository.flush();

		return MarketOrderResponse.of(order, balanceOf(krwAccountId), balanceOf(foreignAccountId));
	}

	private BigDecimal balanceOf(Long accountId) {
		return accountRepository.findById(accountId)
				.map(Account::getBalance)
				.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
	}
}
