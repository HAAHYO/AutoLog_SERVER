package com.autolog.server.domain.ledger.repository;

import com.autolog.server.domain.ledger.entity.EntryType;
import com.autolog.server.global.money.CurrencyCode;
import java.math.BigDecimal;

public record CurrencyTotal(CurrencyCode currency, EntryType entryType, BigDecimal total) {
}
