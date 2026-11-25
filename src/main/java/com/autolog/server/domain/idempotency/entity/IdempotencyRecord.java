package com.autolog.server.domain.idempotency.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 처리한 요청의 멱등성 키와 처음 응답. 키는 사용자 범위에서 유일하다(user_id, idem_key).
 *
 * <p>레코드는 비즈니스 처리와 같은 트랜잭션에서 저장된다. 처리가 실패해 롤백되면 레코드도 사라지므로,
 * 실패한 요청(예: 잔액 부족)은 같은 키로 다시 시도할 수 있다.
 */
@Entity
@Table(
		name = "idempotency_keys",
		uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_keys_user_key", columnNames = {"user_id", "idem_key"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "idem_key", nullable = false, updatable = false, length = 64)
	private String idemKey;

	/** 요청 본문 JSON의 SHA-256 (hex) */
	@Column(name = "request_hash", nullable = false, updatable = false, length = 64)
	private String requestHash;

	@Lob
	@Column(name = "response_body")
	private String responseBody;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private IdempotencyRecord(Long userId, String idemKey, String requestHash, Instant createdAt) {
		this.userId = userId;
		this.idemKey = idemKey;
		this.requestHash = requestHash;
		this.createdAt = createdAt;
	}

	public static IdempotencyRecord start(Long userId, String idemKey, String requestHash, Instant now) {
		return new IdempotencyRecord(userId, idemKey, requestHash, now);
	}

	public void complete(String responseBody) {
		this.responseBody = responseBody;
	}

	public boolean matches(String requestHash) {
		return this.requestHash.equals(requestHash);
	}
}
