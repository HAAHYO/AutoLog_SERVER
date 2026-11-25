package com.autolog.server.global.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.autolog.server.global.config.ClockConfig;
import com.autolog.server.global.config.JpaAuditingConfig;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@Import({JpaAuditingConfig.class, ClockConfig.class})
@ActiveProfiles("test")
class BaseTimeEntityTest {

	@Autowired
	private TestEntityManager em;

	@Test
	void 저장하면_생성_수정_시각이_자동으로_기록된다() {
		Instant before = Instant.now();

		AuditedEntity entity = em.persistFlushFind(new AuditedEntity("first"));

		assertThat(entity.getCreatedAt()).isNotNull().isAfterOrEqualTo(before.minusSeconds(1));
		assertThat(entity.getUpdatedAt()).isNotNull().isAfterOrEqualTo(entity.getCreatedAt());
	}

	@Test
	void 수정하면_수정_시각만_갱신되고_생성_시각은_유지된다() {
		AuditedEntity entity = em.persistFlushFind(new AuditedEntity("first"));
		Instant createdAt = entity.getCreatedAt();
		Instant firstUpdatedAt = entity.getUpdatedAt();

		entity.rename("second");
		em.flush();
		em.clear();
		AuditedEntity reloaded = em.find(AuditedEntity.class, entity.getId());

		assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
		assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(firstUpdatedAt);
	}

	@Entity
	@Table(name = "audited_test_entity")
	@Getter
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	static class AuditedEntity extends BaseTimeEntity {

		@Id
		@GeneratedValue(strategy = GenerationType.IDENTITY)
		private Long id;

		private String name;

		AuditedEntity(String name) {
			this.name = name;
		}

		void rename(String name) {
			this.name = name;
		}
	}
}
