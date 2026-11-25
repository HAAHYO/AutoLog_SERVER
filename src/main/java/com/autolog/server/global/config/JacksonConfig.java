package com.autolog.server.global.config;

import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * 금액·환율({@link BigDecimal})을 JSON 문자열로 내보낸다(#13).
 * JS 클라이언트가 숫자를 double로 읽으며 정밀도가 깨지는 것을 막고, 지수 표기 없이 plain string으로 쓴다.
 */
@Configuration
public class JacksonConfig {

	@Bean
	public JsonMapperBuilderCustomizer bigDecimalAsStringCustomizer() {
		SimpleModule module = new SimpleModule("BigDecimalAsPlainString");
		module.addSerializer(BigDecimal.class, new BigDecimalPlainStringSerializer());
		return builder -> builder.addModule(module);
	}

	static class BigDecimalPlainStringSerializer extends StdSerializer<BigDecimal> {

		BigDecimalPlainStringSerializer() {
			super(BigDecimal.class);
		}

		@Override
		public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
			gen.writeString(value.toPlainString());
		}
	}
}
