package com.autolog.server.domain.account.repository;

import com.autolog.server.domain.account.entity.Account;
import com.autolog.server.domain.account.entity.AccountType;
import com.autolog.server.global.money.CurrencyCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByUserIdAndCurrencyAndType(Long userId, CurrencyCode currency, AccountType type);

	Optional<Account> findByUserIdIsNullAndTypeAndCurrency(AccountType type, CurrencyCode currency);

	List<Account> findAllByIdInOrderByIdAsc(List<Long> ids);

	List<Account> findAllByUserIdOrderByIdAsc(Long userId);
}
