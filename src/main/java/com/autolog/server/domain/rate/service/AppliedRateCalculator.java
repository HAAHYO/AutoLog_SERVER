package com.autolog.server.domain.rate.service;

import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.FxRoundingPolicy;
import com.autolog.server.global.money.FxSide;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 매매기준율에 스프레드와 우대율을 적용해 고객 적용환율을 계산한다.
 *
 * <pre>
 * 우대 후 스프레드 = 기준율 × 스프레드율 × (1 − 우대율)
 * 살 때 = 기준율 + 우대 후 스프레드  (올림)
 * 팔 때 = 기준율 − 우대 후 스프레드  (내림)
 * </pre>
 */
@Component
public class AppliedRateCalculator {

	public BigDecimal calculate(BigDecimal baseRate, BigDecimal spreadRate, BigDecimal preferentialRate, FxSide side) {
		validate(baseRate, spreadRate, preferentialRate, side);

		BigDecimal spread = baseRate
				.multiply(spreadRate)
				.multiply(BigDecimal.ONE.subtract(preferentialRate));
		BigDecimal raw = side == FxSide.BUY ? baseRate.add(spread) : baseRate.subtract(spread);
		BigDecimal applied = FxRoundingPolicy.roundRate(raw, side);

		if (applied.signum() <= 0) {
			throw new BusinessException(ErrorCode.INVALID_RATE_PARAMETER, "적용환율이 0 이하입니다.");
		}
		return applied;
	}

	private void validate(BigDecimal baseRate, BigDecimal spreadRate, BigDecimal preferentialRate, FxSide side) {
		if (baseRate == null || spreadRate == null || preferentialRate == null || side == null) {
			throw new BusinessException(ErrorCode.INVALID_RATE_PARAMETER, "환율 계산 값이 비어 있습니다.");
		}
		if (baseRate.signum() <= 0) {
			throw new BusinessException(ErrorCode.INVALID_RATE_PARAMETER, "기준율은 0보다 커야 합니다.");
		}
		if (spreadRate.signum() < 0 || spreadRate.compareTo(BigDecimal.ONE) >= 0) {
			throw new BusinessException(ErrorCode.INVALID_RATE_PARAMETER, "스프레드율은 0 이상 1 미만이어야 합니다.");
		}
		if (preferentialRate.signum() < 0 || preferentialRate.compareTo(BigDecimal.ONE) > 0) {
			throw new BusinessException(ErrorCode.INVALID_RATE_PARAMETER, "우대율은 0 이상 1 이하여야 합니다.");
		}
	}
}
