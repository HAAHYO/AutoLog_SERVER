package com.autolog.server.domain.account.service;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import com.autolog.server.global.money.CurrencyCode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemAccountService {

	private final AccountRepository accountRepository;

	/** 시스템 계좌 id는 시드 이후 바뀌지 않으므로 한 번 조회한 값을 캐시한다. */
	private final Map<String, Long> idCache = new ConcurrentHashMap<>();

	/** 시스템 계좌의 id. 시드({@link SystemAccountInitializer})가 만들어 두므로 없으면 설정 오류(500)다. */
	public Long getId(AccountType type, CurrencyCode currency) {
		String key = type + "/" + currency;
		Long cached = idCache.get(key);
		if (cached != null) {
			return cached;
		}
		Long id = accountRepository.findByUserIdIsNullAndTypeAndCurrency(type, currency)
				.map(Account::getId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ACCOUNT_MISSING,
						"시스템 계좌가 없습니다: " + key));
		idCache.put(key, id);
		return id;
	}
}
