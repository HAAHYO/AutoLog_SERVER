package com.autolog.server.domain.order.facade;

import com.autolog.server.domain.account.service.AccountService;
import com.autolog.server.domain.idempotency.service.IdempotencyService;
import com.autolog.server.domain.order.dto.request.MarketBuyRequest;
import com.autolog.server.domain.order.dto.response.MarketOrderResponse;
import com.autolog.server.domain.order.service.MarketOrderService;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 트랜잭션 밖에서 KRW·외화 계좌를 준비(없으면 개설)한 뒤, 매수 트랜잭션을 실행한다.
 * 계좌 단위 분산 락(Redisson)은 feat/limit-order에서 이 Facade에 추가한다. 지금은 {@code @Version}으로만 보호한다.
 */
@Component
@RequiredArgsConstructor
public class MarketOrderFacade {

	private final AccountService accountService;
	private final MarketOrderService marketOrderService;
	private final IdempotencyService idempotencyService;

	public MarketOrderResponse buy(Long userId, String idemKey, MarketBuyRequest request) {
		if (request.targetCurrency() == CurrencyCode.KRW) {
			throw new BusinessException(ErrorCode.ORDER_CURRENCY_NOT_SUPPORTED, "KRW로 KRW를 살 수 없습니다.");
		}
		idempotencyService.validateKey(idemKey);
		Long krwAccountId = accountService.getOrOpenUserAccountId(userId, CurrencyCode.KRW);
		Long foreignAccountId = accountService.getOrOpenUserAccountId(userId, request.targetCurrency());
		return marketOrderService.buy(userId, krwAccountId, foreignAccountId, idemKey, request);
	}
}
