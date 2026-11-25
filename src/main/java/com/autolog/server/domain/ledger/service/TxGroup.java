package com.autolog.server.domain.ledger.service;

import com.autolog.server.domain.ledger.entity.EntryType;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import com.autolog.server.global.money.FxRoundingPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 하나의 경제적 사건(입금, 환전 등)을 구성하는 원장 라인 묶음.
 *
 * <p>{@link Builder#build()} 시점에 불변식을 검증한다(원장 모델 B).
 * <ul>
 *   <li>라인은 2개 이상, 금액은 0보다 크고 통화의 최종 금액 자릿수 이내</li>
 *   <li>적용환율이 있으면 0보다 크고 소수 8자리 이내 (DB에서 조용히 반올림되지 않도록)</li>
 *   <li>통화별로 Σ DEBIT = Σ CREDIT (허용 오차 없음)</li>
 * </ul>
 */
public final class TxGroup {

	private final String txGroupId;
	private final List<Line> lines;

	private TxGroup(String txGroupId, List<Line> lines) {
		this.txGroupId = txGroupId;
		this.lines = List.copyOf(lines);
	}

	public static Builder builder(String txGroupId) {
		return new Builder(txGroupId);
	}

	public String txGroupId() {
		return txGroupId;
	}

	public List<Line> lines() {
		return lines;
	}

	public record Line(Long accountId, CurrencyCode currency, EntryType entryType, BigDecimal amount, BigDecimal appliedRate) {
	}

	public static final class Builder {

		private final String txGroupId;
		private final List<Line> lines = new ArrayList<>();

		private Builder(String txGroupId) {
			this.txGroupId = txGroupId;
		}

		public Builder debit(Long accountId, CurrencyCode currency, BigDecimal amount) {
			return debit(accountId, currency, amount, null);
		}

		public Builder debit(Long accountId, CurrencyCode currency, BigDecimal amount, BigDecimal appliedRate) {
			lines.add(new Line(accountId, currency, EntryType.DEBIT, amount, appliedRate));
			return this;
		}

		public Builder credit(Long accountId, CurrencyCode currency, BigDecimal amount) {
			return credit(accountId, currency, amount, null);
		}

		public Builder credit(Long accountId, CurrencyCode currency, BigDecimal amount, BigDecimal appliedRate) {
			lines.add(new Line(accountId, currency, EntryType.CREDIT, amount, appliedRate));
			return this;
		}

		public TxGroup build() {
			if (txGroupId == null || txGroupId.isBlank()) {
				throw invalid("txGroupId가 비어 있습니다.");
			}
			if (lines.size() < 2) {
				throw invalid("원장 라인은 2개 이상이어야 합니다: " + lines.size());
			}
			Map<CurrencyCode, BigDecimal> net = new EnumMap<>(CurrencyCode.class);
			for (Line line : lines) {
				validateLine(line);
				BigDecimal signed = line.entryType() == EntryType.CREDIT ? line.amount() : line.amount().negate();
				net.merge(line.currency(), signed, BigDecimal::add);
			}
			net.forEach((currency, sum) -> {
				if (sum.signum() != 0) {
					throw new BusinessException(ErrorCode.LEDGER_UNBALANCED,
							"통화별 차대 불일치 txGroupId=" + txGroupId + ", currency=" + currency + ", CREDIT-DEBIT=" + sum);
				}
			});
			return new TxGroup(txGroupId, lines);
		}

		private void validateLine(Line line) {
			if (line.accountId() == null || line.currency() == null || line.entryType() == null || line.amount() == null) {
				throw invalid("원장 라인에 빈 값이 있습니다: " + line);
			}
			if (line.amount().signum() <= 0) {
				throw invalid("원장 금액은 0보다 커야 합니다: " + line);
			}
			if (!line.currency().fitsScale(line.amount())) {
				throw invalid("통화 자릿수를 넘는 금액입니다: " + line);
			}
			BigDecimal rate = line.appliedRate();
			if (rate != null && (rate.signum() <= 0 || rate.stripTrailingZeros().scale() > FxRoundingPolicy.RATE_SCALE)) {
				throw invalid("적용환율은 0보다 크고 소수 " + FxRoundingPolicy.RATE_SCALE + "자리 이내여야 합니다: " + line);
			}
		}

		private BusinessException invalid(String detail) {
			return new BusinessException(ErrorCode.LEDGER_INVALID_ENTRY, detail);
		}
	}
}
