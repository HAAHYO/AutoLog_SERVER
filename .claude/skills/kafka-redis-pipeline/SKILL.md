---
name: kafka-redis-pipeline
description: "AutoLog의 비동기·분산 처리 규칙 — Kafka 토픽(fx-rate-topic, fx-order-matched, DLQ) 프로듀서/컨슈머, at-least-once 전제의 멱등 컨슈머, 파티션 키와 순서 보장, Redis 최신 시세 캐시, ZSet 지정가 매칭(score 정밀도, 원자적 선점), Redisson 분산 락과 트랜잭션 커밋 순서, @Version 이중 락, DLQ·재시도·FAILED 보상, SSE 전송, Mock Market. Kafka·Redis·Redisson·SSE·스케줄러 코드를 작성·리뷰할 때, 중복 체결·유실·순서 꼬임·락 문제를 디버깅할 때 사용한다."
---

# Kafka·Redis·분산 락 파이프라인 규칙

이 스킬은 `money-ledger`와 짝을 이룹니다. money-ledger가 "**한 트랜잭션 안에서** 돈이 맞는가"를 다룬다면, 이 스킬은 "**여러 프로세스와 메시지가 얽혀도** 한 번만 처리되는가"를 다룹니다.

> 아직 Kafka, Redis, Redisson 의존성이 없습니다. 이 스킬은 도입할 때의 규칙입니다. 의존성을 추가하는 것은 사용자의 결정이 필요한 사항이므로 먼저 제안하세요. Boot 4.2를 유지하기로 했으므로(PRODUCT Q1), Spring Kafka와 Redisson은 Boot 4와 호환되는 버전을 문서로 확인한 뒤 고릅니다.

## 기본 전제: 모든 것은 최소 한 번(at-least-once)

- Kafka 컨슈머는 **같은 메시지를 두 번 이상 받을 수 있습니다.** 리밸런싱, 커밋 전 장애, 재시도 때문입니다.
- 그러므로 **모든 컨슈머는 멱등해야 합니다.** "정확히 한 번"은 Kafka 설정이 아니라 **DB 상태 확인과 제약**으로 만듭니다.
  - 체결 컨슈머: 주문이 이미 `PENDING`이 아니면 처리하지 않고 넘깁니다. 상태 전이에 `@Version`이나 조건부 UPDATE를 씁니다.
  - 원장: `tx_group_id`, 주문 id + 회차 같은 자연키에 **UNIQUE 제약**을 겁니다.
- Kafka 트랜잭션(EOS)은 Kafka 안에서 일어나는 일에만 해당합니다. MySQL 커밋과 Kafka 커밋을 원자적으로 묶어 주지 않습니다. 이 점에 기대지 마세요.

## 토픽

| 토픽 | 프로듀서 | 컨슈머 | 키 | 비고 |
|------|----------|--------|-----|------|
| `fx-rate-topic` | Collector / Mock Market | Rate Consumer, Matching Engine | 통화 코드 (`USD`) | 통화별 순서 보장 |
| `fx-order-matched` | Matching Engine | Execution Consumer | **accountId** | 같은 계좌의 체결이 순서대로, 같은 파티션에서 처리되도록 |
| `fx-order-matched.DLT` | 에러 핸들러 | 재처리 또는 FAILED 처리기 | 원본과 같음 | Spring Kafka 기본 접미사 `.DLT`. 이름은 처음 구현할 때 확정 |

- 메시지는 JSON으로 보내며, **이벤트 id(UUID)와 발생 시각(`Instant`)**을 반드시 넣습니다.
- 금액과 환율은 **문자열**로 직렬화합니다(`"1350.25"`). JSON 숫자로 보내면 double을 거치면서 정밀도가 깨질 수 있습니다.
- 토픽 이름은 상수 클래스나 설정 프로퍼티 한곳에서 관리합니다. 문자열 리터럴을 흩어 두지 않습니다.

## 프로듀서

- `acks=all`, `enable.idempotence=true`를 설정합니다. 이는 프로듀서 쪽 중복 발행을 막을 뿐, 컨슈머 쪽 중복은 막지 못합니다.
- **DB 커밋 뒤에 발행해야 하는 이벤트**는 `@TransactionalEventListener(AFTER_COMMIT)`에서 발행합니다. 트랜잭션 안에서 발행하면, 롤백되어도 메시지는 이미 나간 상태가 됩니다.
- 커밋은 됐는데 발행이 실패하는 경우를 반드시 막아야 할 때가 오면 **Transactional Outbox** 패턴을 검토합니다. MVP에서는 선택입니다.

## 컨슈머

- `@KafkaListener` 메서드 안에서 비즈니스 로직을 직접 짜지 않습니다. 서비스를 호출하는 얇은 어댑터로 둡니다(컨트롤러와 같은 원칙).
- 오프셋 커밋은 처리가 성공한 **뒤에** 합니다(Spring Kafka 기본값인 `AckMode.BATCH` 또는 `RECORD`).
- 재시도와 DLQ는 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `FixedBackOff`/`ExponentialBackOff`로 구성합니다.
  - **재시도할 예외와 하지 않을 예외를 구분합니다.**

    | 구분 | 예외 | 처리 |
    |------|------|------|
    | 재시도할 것 | 락 획득 실패, `OptimisticLockException`, 일시적인 DB·네트워크 오류 | 재시도 |
    | 재시도하지 않을 것 | 잔액 부족 같은 비즈니스 실패 | 바로 FAILED 처리 |
    | 재시도하지 않을 것 | 역직렬화 실패 | 바로 DLQ |

  - 잔액 부족을 재시도하면 같은 실패를 반복할 뿐입니다.
- **최종 실패 시 보상 처리**: 주문을 `FAILED(reason)`로 바꾸고 알림 이벤트를 발행합니다. 이 처리 자체도 멱등해야 합니다. 이미 COMPLETED인 주문을 FAILED로 덮어쓰면 안 됩니다.

## Redis 최신 시세

- Redis는 **캐시이자 매칭 인덱스**일 뿐, 진실의 원천이 아닙니다. 원장, 잔액, 주문 상태의 진실은 MySQL에 있습니다.
- 시세 값에는 `observedAt`과 `source`를 함께 저장합니다. 체결할 때 신선도를 검사합니다(PRODUCT Q11).
- 오래된 틱이 늦게 도착할 수 있습니다. `observedAt`이 현재 저장값보다 과거라면 덮어쓰지 않습니다. 비교와 쓰기를 원자적으로 하려면 Lua 스크립트를 씁니다.

## ZSet 지정가 매칭

### score 정밀도 함정

ZSet의 score는 **double**입니다. `1350.12345678`을 score로 넣으면 근사값이 됩니다.

- ZSet은 **후보를 거르는 데만** 씁니다. 경계에 걸친 후보를 놓치지 않도록 조회 범위를 약간 넓게(epsilon) 잡을 수 있습니다.
- **체결 직전에 DB의 `BigDecimal target_rate`와 현재 적용환율을 `compareTo`로 다시 비교합니다.** 최종 판정은 항상 DB 값으로 합니다.

### 방향

| 주문 | 체결 조건 | 조회 |
|------|-----------|------|
| 매수 (KRW → 외화) | 현재환율 ≤ target_rate | `ZRANGEBYSCORE buy:{CUR} {현재} +inf` |
| 매도 (외화 → KRW) | 현재환율 ≥ target_rate | `ZRANGEBYSCORE sell:{CUR} -inf {현재}` |

방향이 뒤집히는 버그가 가장 흔합니다. 경계값(같을 때 체결할지) 테스트를 반드시 작성합니다.

### 원자적 선점

여러 매칭 인스턴스나 스레드가 같은 후보를 동시에 뽑을 수 있습니다.

- `ZRANGEBYSCORE`로 후보를 조회한 뒤 멤버마다 `ZREM`을 실행하고, **반환값이 1인 쪽만** 체결 이벤트를 발행합니다. 조회와 제거를 Lua 스크립트로 묶어도 됩니다.
- 선점했는데 체결이 실패했다면 정책을 정해야 합니다. 일시 오류라면 재시도하고, 비즈니스 실패라면 FAILED로 둡니다. 재등록하지 않으면 그 주문은 영원히 매칭되지 않으므로, 정책을 명시합니다.

### DB와 ZSet의 동기화

- 주문을 등록할 때: DB 커밋 → (AFTER_COMMIT) → `ZADD`. 순서가 반대이면 롤백된 주문이 ZSet에 남습니다.
- 취소, 만료, 체결될 때: DB 상태 전이 → `ZREM`
- Redis가 재시작되면 ZSet이 비었을 수 있습니다. 애플리케이션이 뜰 때 **DB의 PENDING 주문으로 ZSet을 재구축**하는 로직이나 관리 API를 둡니다.
- 정합성 점검 쿼리를 둡니다: "DB에는 PENDING인데 ZSet에 없는 주문", "ZSet에는 있는데 DB에서 종결된 주문"

## 분산 락 (Redisson) + `@Version`

### 가장 흔한 함정: 커밋 전에 락 해제

```java
// X — @Transactional 메서드 안에서 락을 잡고 풀면, 락이 풀린 "뒤"에 커밋됨
@Transactional
public void execute(Long accountId) {
	RLock lock = redisson.getLock("lock:account:" + accountId);
	lock.lock();
	try { ... } finally { lock.unlock(); }   // ← 아직 커밋 전. 다른 스레드가 커밋 전 데이터를 읽음
}

// O — 락이 트랜잭션을 감싸도록, 락은 바깥 빈에서 잡음
public void execute(Long accountId) {               // 트랜잭션 없음 (Facade)
	RLock lock = redisson.getLock("lock:account:" + accountId);
	if (!lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS)) {
		throw new BusinessException(ErrorCode.ACCOUNT_LOCK_TIMEOUT);
	}
	try {
		executionTxService.executeInTx(accountId);   // 다른 빈의 @Transactional → 여기서 커밋 완료
	} finally {
		if (lock.isHeldByCurrentThread()) lock.unlock();
	}
}
```

- 구조는 **Facade(락) → TxService(@Transactional)** 형태로 고정합니다. 같은 클래스 안에서 호출(self-invocation)하면 트랜잭션이 적용되지 않습니다.
- `tryLock`의 waitTime과 leaseTime을 명시합니다.
  - leaseTime이 처리 시간보다 짧으면 처리 도중 락이 풀립니다.
  - leaseTime을 -1로 두면 watchdog이 동작합니다. 둘 중 하나를 의식적으로 고르고, 그 결정을 기록합니다.
- 여러 계좌의 락을 함께 잡을 때는(KRW 계좌와 USD 계좌) **accountId 오름차순**으로 잡아 데드락을 피합니다. 계좌 단위가 아니라 사용자 단위로 락을 걸면 락이 하나로 줄어듭니다. 단위는 처음 구현할 때 정하고 기록합니다.
- `@Version`은 락이 뚫렸을 때의 **2차 방어선**입니다. 예를 들어 leaseTime이 만료됐거나 Redis 장애로 락이 무력화된 경우입니다. `OptimisticLockException`이 나면 재시도 대상으로 처리합니다.

## 멱등성 키 (HTTP)

흐름은 다음과 같습니다.

1. `Idempotency-Key` 헤더를 받습니다.
2. DB(`idempotency_keys`)에 `IN_PROGRESS`로 INSERT합니다. PK 충돌이 나면 기존 행을 조회합니다.
3. 조회한 기존 행에 따라 처리합니다.
   - `COMPLETED`이고 request_hash가 같으면 → 저장해 둔 응답을 그대로 반환합니다.
   - request_hash가 다르면 → 422를 반환합니다.
   - `IN_PROGRESS`이면 → 409를 반환합니다(처리 중).
4. 비즈니스 처리를 합니다.
5. 같은 트랜잭션 안에서 응답을 저장하고 `COMPLETED`로 바꿉니다.

주의할 점이 있습니다.

- 최종 판정은 **DB PK 제약**으로 합니다. Redis(`SETNX`)는 빠른 경로로만 씁니다. Redis만 쓰면 Redis 장애나 키 만료 때 중복이 생깁니다.
- 비즈니스 처리가 실패해 롤백되면 `IN_PROGRESS` 행도 함께 롤백되도록, 같은 트랜잭션으로 묶을지 판단하고 기록합니다.
- 구현 위치는 컨트롤러가 아니라 인터셉터나 AOP, 또는 서비스 진입부입니다. 처음 구현할 때 정합니다.

## SSE

- `SseEmitter`에는 타임아웃을 명시하고, `onCompletion`, `onTimeout`, `onError`에서 **emitter를 레지스트리에서 제거**합니다. 제거하지 않으면 메모리 누수가 생깁니다.
- 연결 직후에 현재 시세를 한 번 보내고, 주기적으로 heartbeat 코멘트를 보냅니다. 프록시가 유휴 연결을 끊는 것을 막기 위해서입니다.
- 전송 실패(`IOException`)는 해당 emitter만 제거하고, 다른 클라이언트 전송에 영향을 주지 않게 합니다.
- 인스턴스가 여러 개라면 각 인스턴스가 Kafka에서 시세를 받아 자신에게 연결된 클라이언트에게 보냅니다. **컨슈머 그룹을 인스턴스마다 다르게** 두어 브로드캐스트되게 합니다.
- Spring MVC의 SSE는 연결마다 스레드나 커넥션 자원을 씁니다. 동시 접속 목표치는 k6로 측정합니다.

## Collector와 Mock Market

- 외부 API를 쓸지 Mock을 쓸지는 프로퍼티(`fx.collector.mode: external|mock`)나 프로필로 전환합니다. 매칭과 체결 코드는 시세의 출처를 몰라야 합니다.
- 외부 API 호출에는 **connect와 read 타임아웃**을 설정하고, 실패하면 직전 값을 유지한 채 경고 로그를 남깁니다. 0, 음수, 급변값은 버립니다.
- 한국수출입은행 API는 일 단위 고시라 실시간이 아닙니다. 초 단위 변동은 Mock Market으로 만듭니다.
- Mock Market은 시드를 고정할 수 있게 만들어, 테스트를 재현할 수 있게 합니다.
- `@Scheduled`를 쓸 때 인스턴스가 여러 개라면 중복 수집이 생깁니다. 단일 인스턴스를 전제로 하거나, ShedLock이나 Redis 락을 씁니다.

## 테스트

- 순수 로직(적용환율 계산, 매칭 방향, 상태 전이)은 단위 테스트로 작성합니다.
- Kafka, Redis, Redisson 통합 테스트는 **Testcontainers** 또는 Docker Compose 환경에서 합니다. Embedded Kafka와 embedded Redis는 실제 동작과 다른 부분이 있으므로, 통과하더라도 한계를 명시합니다.
- **동시성 테스트**: 같은 계좌에서 즉시 환전과 지정가 체결을 N개 스레드로 동시에 실행했을 때 다음을 확인합니다.
  - 잔액이 0 이상인지
  - 원장 합계와 잔액이 일치하는지
  - 체결이 정확히 한 번인지
- k6 부하 테스트 스크립트는 `k6/` 디렉터리에 둡니다(가정). 결과 수치는 PR이나 docs에 기록합니다.
