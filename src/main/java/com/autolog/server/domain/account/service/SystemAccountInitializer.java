package com.autolog.server.domain.account.service;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.domain.account.repository.AccountRepository;
import com.autolog.server.global.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션이 시작할 때 모든 통화 × 시스템 계좌 종류를 만들어 둔다. 이미 있으면 건너뛴다.
 * 모든 싱글턴 빈이 준비된 직후(웹 서버가 요청을 받기 전)에 실행해서, 시드 전에 들어온 요청이 실패하지 않게 한다.
 * 여러 인스턴스가 동시에 시작해도 owner_key 유니크 제약이 중복을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemAccountInitializer implements SmartInitializingSingleton {

	private final AccountRepository accountRepository;

	@Override
	public void afterSingletonsInstantiated() {
		for (AccountType type : AccountType.values()) {
			if (type.isUser()) {
				continue;
			}
			for (CurrencyCode currency : CurrencyCode.values()) {
				ensure(type, currency);
			}
		}
	}

	private void ensure(AccountType type, CurrencyCode currency) {
		if (accountRepository.findByUserIdIsNullAndTypeAndCurrency(type, currency).isPresent()) {
			return;
		}
		try {
			accountRepository.saveAndFlush(Account.openSystemAccount(type, currency));
			log.info("System account created: {}/{}", type, currency);
		} catch (DataIntegrityViolationException e) {
			log.info("System account already created by another instance: {}/{}", type, currency);
		}
	}
}
