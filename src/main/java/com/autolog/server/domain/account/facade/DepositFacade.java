package com.autolog.server.domain.account.facade;

import com.autolog.server.domain.account.dto.request.DepositRequest;
import com.autolog.server.domain.account.dto.response.DepositResponse;
import com.autolog.server.domain.account.service.AccountService;
import com.autolog.server.domain.account.service.DepositService;
import com.autolog.server.global.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 트랜잭션 밖에서 계좌를 준비(없으면 개설)한 뒤, 충전 트랜잭션을 실행한다. */
@Component
@RequiredArgsConstructor
public class DepositFacade {

	private final AccountService accountService;
	private final DepositService depositService;

	public DepositResponse deposit(Long userId, String idemKey, DepositRequest request) {
		Long krwAccountId = accountService.getOrOpenUserAccountId(userId, CurrencyCode.KRW);
		return depositService.deposit(userId, krwAccountId, idemKey, request);
	}
}
