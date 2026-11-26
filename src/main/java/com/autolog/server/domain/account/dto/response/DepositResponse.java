package com.autolog.server.domain.account.dto.response;

import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;

public record DepositResponse(String txGroupId, CurrencyCode currency, BigDecimal amount, BigDecimal balance) {
}
