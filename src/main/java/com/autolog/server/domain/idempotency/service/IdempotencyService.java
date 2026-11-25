package com.autolog.server.domain.idempotency.service;

import com.autolog.server.domain.idempotency.entity.IdempotencyRecord;
import com.autolog.server.domain.idempotency.repository.IdempotencyRecordRepository;
import com.autolog.server.global.exception.BusinessException;
import com.autolog.server.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency-Key 처리. 반드시 비즈니스 트랜잭션 안에서 호출한다(MANDATORY).
 *
 * <ol>
 *   <li>같은 사용자·키의 레코드가 있으면: 요청 해시가 같으면 저장된 응답을 그대로 돌려주고, 다르면 422</li>
 *   <li>없으면 레코드를 먼저 INSERT(flush)한 뒤 실제 처리를 한다. 같은 키가 동시에 들어오면 늦은 쪽은
 *       먼저 온 트랜잭션이 끝날 때까지 기다렸다가 유니크 위반으로 실패하고 409(처리 중)를 받는다.</li>
 *   <li>처리 결과를 JSON으로 레코드에 저장한다. 같은 트랜잭션이므로 처리가 롤백되면 레코드도 사라진다.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

	private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	private final IdempotencyRecordRepository repository;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	@Transactional(propagation = Propagation.MANDATORY)
	public <T> T execute(Long userId, String idemKey, Object request, Class<T> responseType, Supplier<T> action) {
		validateKey(idemKey);
		String requestHash = sha256(jsonMapper.writeValueAsString(request));

		Optional<IdempotencyRecord> existing = repository.findByUserIdAndIdemKey(userId, idemKey);
		if (existing.isPresent()) {
			return replay(existing.get(), requestHash, responseType);
		}

		IdempotencyRecord record;
		try {
			record = repository.saveAndFlush(IdempotencyRecord.start(userId, idemKey, requestHash, Instant.now(clock)));
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
		}

		T response = action.get();
		record.complete(jsonMapper.writeValueAsString(response));
		return response;
	}

	private <T> T replay(IdempotencyRecord record, String requestHash, Class<T> responseType) {
		if (!record.matches(requestHash)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
		}
		if (record.getResponseBody() == null) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
		}
		return jsonMapper.readValue(record.getResponseBody(), responseType);
	}

	/** 키 형식 검사. 계좌 개설처럼 되돌리기 어려운 준비 작업보다 먼저 호출할 수 있게 공개한다. */
	public void validateKey(String idemKey) {
		if (idemKey == null || !KEY_PATTERN.matcher(idemKey).matches()) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISSING);
		}
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "SHA-256을 사용할 수 없습니다.");
		}
	}
}
