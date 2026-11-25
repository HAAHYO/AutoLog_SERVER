package com.autolog.server.domain.account.service;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemAccountService {

	private final AccountRepository accountRepository;

	/** 시스템 계좌의 id. 시드({@link SystemAccountInitializer})가 만들어 두므로 없으면 설정 오류(500)다. */
	public Long getId(AccountType type, CurrencyCode currency) {
		return accountRepository.findByUserIdIsNullAndTypeAndCurrency(type, currency)
				.map(Account::getId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ACCOUNT_MISSING,
						"시스템 계좌가 없습니다: " + type + "/" + currency));
	}
}
