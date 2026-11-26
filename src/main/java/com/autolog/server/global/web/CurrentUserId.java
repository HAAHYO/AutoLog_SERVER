package com.autolog.server.global.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 요청한 사용자의 id를 주입한다.
 *
 * <p><b>임시 방식:</b> 인증(PRODUCT Q8)이 생기기 전까지는 {@code X-User-Id} 헤더에서 읽는다.
 * 인증을 붙일 때는 {@link CurrentUserIdArgumentResolver}만 바꾸면 된다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
