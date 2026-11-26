package com.autolog.server.domain.account.dto.response;

import java.util.List;

public record AccountListResponse(List<AccountResponse> accounts) {
}
