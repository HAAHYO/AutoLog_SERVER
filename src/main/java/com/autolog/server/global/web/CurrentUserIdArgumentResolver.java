package com.autolog.server.global.web;

import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 인증 전 임시 구현: {@code X-User-Id} 헤더를 양의 정수 userId로 읽는다. */
@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

	public static final String HEADER = "X-User-Id";

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentUserId.class) && parameter.getParameterType() == Long.class;
	}

	@Override
	public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		String raw = webRequest.getHeader(HEADER);
		if (raw == null || raw.isBlank()) {
			throw new BusinessException(ErrorCode.USER_ID_HEADER_INVALID);
		}
		try {
			long userId = Long.parseLong(raw.trim());
			if (userId <= 0) {
				throw new BusinessException(ErrorCode.USER_ID_HEADER_INVALID);
			}
			return userId;
		} catch (NumberFormatException e) {
			throw new BusinessException(ErrorCode.USER_ID_HEADER_INVALID);
		}
	}
}
