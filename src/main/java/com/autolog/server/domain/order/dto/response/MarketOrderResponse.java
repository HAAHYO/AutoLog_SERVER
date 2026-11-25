package com.autolog.server.domain.order.dto.response;

import com.autolog.server.domain.order.entity.FxOrder;
import com.autolog.server.domain.order.entity.OrderStatus;
import com.autolog.server.domain.order.entity.OrderType;
import com.autolog.server.global.money.CurrencyCode;
import com.autolog.server.global.money.FxSide;
import java.math.BigDecimal;
import java.time.Instant;

/** 시장가 주문 체결 결과. 금액·환율은 JSON 문자열로 나간다. */
public record MarketOrderResponse(
		Long orderId,
		OrderType orderType,
		FxSide side,
		OrderStatus status,
		CurrencyCode targetCurrency,
		BigDecimal krwAmount,
		BigDecimal foreignAmount,
		BigDecimal baseRate,
		BigDecimal appliedRate,
		BigDecimal spreadRate,
		BigDecimal preferentialRate,
		String rateSource,
		Instant rateObservedAt,
		Instant executedAt,
		BigDecimal krwBalance,
		BigDecimal foreignBalance
) {

	public static MarketOrderResponse of(FxOrder order, BigDecimal krwBalance, BigDecimal foreignBalance) {
		CurrencyCode currency = order.getTargetCurrency();
		return new MarketOrderResponse(
				order.getId(),
				order.getOrderType(),
				order.getSide(),
				order.getStatus(),
				currency,
				order.getKrwAmount().setScale(CurrencyCode.KRW.getScale()),
				order.getForeignAmount().setScale(currency.getScale()),
				order.getBaseRate(),
				order.getAppliedRate(),
				order.getSpreadRate(),
				order.getPreferentialRate(),
				order.getRateSource(),
				order.getRateObservedAt(),
				order.getExecutedAt(),
				krwBalance.setScale(CurrencyCode.KRW.getScale()),
				foreignBalance.setScale(currency.getScale()));
	}
}
