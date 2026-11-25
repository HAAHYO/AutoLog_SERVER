package com.autolog.server.domain.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 거래 묶음 헤더. tx_group_id를 기본키로 두어, 같은 거래가 동시에 두 번 기록되는 것을 DB에서 막는다.
 *
 * <p>id를 직접 지정하는 엔티티라서, {@link Persistable#isNew()}를 항상 true로 두어 Spring Data가
 * merge(SELECT 후 UPDATE) 대신 persist(INSERT)를 하도록 한다. 그래야 중복 시 기본키 위반이 난다.
 */
@Entity
@Table(name = "ledger_tx_groups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerTxGroup implements Persistable<String> {

	@Id
	@Column(name = "tx_group_id", length = 36)
	private String txGroupId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = true;

	private LedgerTxGroup(String txGroupId, Instant createdAt) {
		this.txGroupId = txGroupId;
		this.createdAt = createdAt;
	}

	public static LedgerTxGroup open(String txGroupId, Instant now) {
		return new LedgerTxGroup(txGroupId, now);
	}

	@Override
	public String getId() {
		return txGroupId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}
}
