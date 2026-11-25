package com.autolog.server.domain.order.service;

import com.autolog.server.domain.rate.service.AppliedRateCalculator;
import com.autolog.server.domain.rate.service.RateQuote;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import com.autolog.server.global.money.FxRoundingPolicy;
import com.autolog.server.global.money.FxSide;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 시장가 매수(KRW → 외화) 금액 계산. 단수 차이가 항상 회사(스프레드 수익) 쪽에 남도록 한다.
 *
 * <pre>
 * 외화 수령액   = krwAmount × rateUnit / 적용환율       (고객이 받는 금액 → 내림)
 * 포지션 KRW    = 외화 수령액 × 기준율 / rateUnit        (내림)
 * 스프레드 수익 = krwAmount − 포지션 KRW               (≥ 0: 적용환율 ≥ 기준율이고 두 번 모두 내림이므로)
 * </pre>
 *
 * 원장 라인: 사용자 KRW DEBIT krwAmount = 포지션 KRW CREDIT + 스프레드 수익 CREDIT,
 * 포지션 외화 DEBIT 외화 수령액 = 사용자 외화 CREDIT 외화 수령액.
 */
@Component
@RequiredArgsConstructor
public class MarketBuyCalculator {

	private static final int INTERMEDIATE_SCALE = 12;

	private final AppliedRateCalculator appliedRateCalculator;

	public Result calculate(BigDecimal krwAmount, RateQuote quote, BigDecimal preferentialRate) {
		CurrencyCode currency = quote.currency();
		BigDecimal unit = currency.getRateUnit();
		BigDecimal appliedRate = appliedRateCalculator.calculate(quote.baseRate(), quote.spreadRate(), preferentialRate, FxSide.BUY);

		BigDecimal foreignAmount = FxRoundingPolicy.roundPayout(
				krwAmount.multiply(unit).divide(appliedRate, INTERMEDIATE_SCALE, RoundingMode.DOWN), currency);
		if (foreignAmount.signum() <= 0) {
			throw new BusinessException(ErrorCode.ORDER_AMOUNT_TOO_SMALL,
					"받을 외화가 0입니다: krwAmount=" + krwAmount + ", appliedRate=" + appliedRate);
		}
		BigDecimal positionKrw = FxRoundingPolicy.roundPayout(
				foreignAmount.multiply(quote.baseRate()).divide(unit, INTERMEDIATE_SCALE, RoundingMode.DOWN), CurrencyCode.KRW);
		BigDecimal spreadKrw = krwAmount.subtract(positionKrw);
		if (spreadKrw.signum() < 0) {
			// 적용환율 ≥ 기준율이면 일어날 수 없다. 일어났다면 계산 버그다.
			throw new BusinessException(ErrorCode.LEDGER_UNBALANCED, "스프레드 수익이 음수입니다: " + spreadKrw);
		}
		return new Result(appliedRate, foreignAmount, positionKrw, spreadKrw);
	}

	public record Result(BigDecimal appliedRate, BigDecimal foreignAmount, BigDecimal positionKrw, BigDecimal spreadKrw) {
	}
}
