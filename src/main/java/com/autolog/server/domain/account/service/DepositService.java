package com.autolog.server.domain.account.service;

import com.autolog.server.domain.account.dto.request.DepositRequest;
import com.autolog.server.domain.account.dto.response.DepositResponse;
import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.domain.idempotency.service.IdempotencyService;
import com.autolog.server.domain.ledger.service.LedgerService;
import com.autolog.server.domain.ledger.service.TxGroup;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모의 충전(PRODUCT Q9: 실제 결제 없음). 외부 청산 시스템 계좌에서 사용자 KRW 계좌로 입금한다.
 * 계좌 개설은 트랜잭션 밖에서 해야 하므로 {@link com.autolog.server.domain.account.facade.DepositFacade}를 거쳐 호출한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DepositService {

	private static final CurrencyCode CURRENCY = CurrencyCode.KRW;

	private final IdempotencyService idempotencyService;
	private final LedgerService ledgerService;
	private final SystemAccountService systemAccountService;
	private final AccountRepository accountRepository;

	@Transactional
	public DepositResponse deposit(Long userId, Long userKrwAccountId, String idemKey, DepositRequest request) {
		return idempotencyService.execute(userId, idemKey, request, DepositResponse.class, () -> {
			String txGroupId = UUID.randomUUID().toString();
			ledgerService.post(TxGroup.builder(txGroupId)
					.debit(systemAccountService.getId(AccountType.SYSTEM_EXTERNAL_CLEARING, CURRENCY), CURRENCY, request.amount())
					.credit(userKrwAccountId, CURRENCY, request.amount())
					.build());
			Account account = accountRepository.findById(userKrwAccountId)
					.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
			return new DepositResponse(txGroupId, CURRENCY, request.amount().setScale(CURRENCY.getScale()),
					account.getBalance().setScale(CURRENCY.getScale()));
		});
	}
}
