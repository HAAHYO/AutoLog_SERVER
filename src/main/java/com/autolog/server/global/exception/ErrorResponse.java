package com.autolog.server.global.exception;

import java.util.List;
import org.springframework.validation.BindingResult;

public record ErrorResponse(String code, String message, List<FieldError> errors) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), List.of());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.getCode(), message, List.of());
	}

	public static ErrorResponse of(ErrorCode errorCode, BindingResult bindingResult) {
		List<FieldError> errors = bindingResult.getFieldErrors().stream()
				.map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
				.toList();
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), errors);
	}

	public record FieldError(String field, String reason) {
	}
}
