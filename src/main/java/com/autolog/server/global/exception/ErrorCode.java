package com.autolog.server.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C002", "요청한 리소스를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "지원하지 않는 HTTP 메서드입니다."),
	CONCURRENT_UPDATE(HttpStatus.CONFLICT, "C004", "다른 요청과 동시에 처리되어 실패했습니다. 다시 시도해 주세요."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "서버 내부 오류가 발생했습니다."),

	ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "A001", "계좌를 찾을 수 없습니다."),
	INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT, "A002", "잔액이 부족합니다."),

	LEDGER_UNBALANCED(HttpStatus.INTERNAL_SERVER_ERROR, "L001", "원장 처리 중 오류가 발생했습니다."),
	LEDGER_INVALID_ENTRY(HttpStatus.INTERNAL_SERVER_ERROR, "L002", "원장 처리 중 오류가 발생했습니다."),
	LEDGER_DUPLICATE_TX_GROUP(HttpStatus.CONFLICT, "L003", "이미 처리된 거래입니다."),

	INVALID_RATE_PARAMETER(HttpStatus.BAD_REQUEST, "R001", "환율 계산 값이 올바르지 않습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
