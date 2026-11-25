package com.autolog.server.domain.account.dto.response;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;

public record AccountResponse(Long accountId, CurrencyCode currency, BigDecimal balance) {

	public static AccountResponse from(Account account) {
		return new AccountResponse(account.getId(), account.getCurrency(),
				account.getBalance().setScale(account.getCurrency().getScale()));
	}
}
