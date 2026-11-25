# 도메인 모델

> **상태:** `accounts`, `ledger_entries`는 **구현됨**(2026-11-25, 아래 표는 실제 엔티티와 같음). 나머지는 설계 초안입니다. 이 문서와 코드가 다르면 **코드가 진실**이며, 문서를 고칩니다.

## 도메인 패키지

| 패키지 | 엔티티 / 컴포넌트 | 테이블 | 저장소 |
|--------|-------------------|--------|--------|
| `rate` | `ExchangeRate`(시세 스냅샷), `RateCollector`, `MockMarket`, SSE 발행 | `exchange_rates` (이력, 선택) | Redis (최신 시세), Kafka `fx-rate-topic` |
| `account` | `Account` (사용자 × 통화) | `accounts` | MySQL |
| `ledger` | `LedgerEntry`, (`TxGroup`) | `ledger_entries` | MySQL |
| `order` | `FxOrder`, 매칭 엔진 | `fx_orders` | MySQL + Redis ZSet + Kafka `fx-order-matched` |
| `idempotency` | `IdempotencyKey` | `idempotency_keys` | MySQL (+ Redis 캐시) |
| `user` | `User` (등급·우대율 포함 가정) | `users` | MySQL — 인증 방식 미결정 (Q8) |

`order`는 SQL 예약어이므로 엔티티는 `FxOrder`, 테이블은 `fx_orders`로 이름을 정했습니다(기획 원안과 같음).

## accounts

| 컬럼 | 타입 | 비고 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT NULL | 시스템 계좌면 NULL |
| owner_key | VARCHAR(40) | `USER:<userId>` 또는 `SYSTEM`. 유니크 제약용 (NULL 문제 회피) |
| currency | VARCHAR(3) | `CurrencyCode` enum: KRW, USD, JPY, EUR |
| balance | DECIMAL(19,4) | 원장 잔액의 **캐시**. 진실의 원천은 ledger_entries |
| version | BIGINT | `@Version` 낙관적 락 |
| type | VARCHAR(30) | USER / SYSTEM_FX_POSITION / SYSTEM_SPREAD_REVENUE / SYSTEM_EXTERNAL_CLEARING(충전·출금) |
| created_at, updated_at | DATETIME(6) | `BaseTimeEntity` (UTC `Instant`) |

- UNIQUE 제약: `(owner_key, currency, type)`. `user_id`로 걸면 시스템 계좌(NULL)의 중복을 막지 못해서(MySQL은 NULL끼리 다르다고 봄), NULL이 아닌 `owner_key`를 둡니다.
- 잔액은 엔티티 메서드 `debit(amount)`와 `credit(amount)`로만 바꿉니다. 사용자 계좌에서 `debit` 결과가 음수가 되면 `BusinessException(INSUFFICIENT_BALANCE)`(422)를 던집니다.
- **시스템 계좌는 잔액을 캐시하지 않습니다.** `balance`는 0으로 유지되고, 잔액은 원장 합계로만 계산합니다(체결마다 같은 행을 갱신하는 락 경합을 피하기 위해). 음수 포지션도 허용합니다.

## ledger_tx_groups — 거래 묶음 헤더

| 컬럼 | 타입 | 비고 |
|------|------|------|
| tx_group_id | VARCHAR(36) PK | 같은 거래의 동시 중복 기록을 기본키 위반으로 막음 |
| created_at | DATETIME(6) | UTC |

`LedgerService.post()`가 엔트리보다 먼저 INSERT합니다. 중복이면 `LEDGER_DUPLICATE_TX_GROUP`(409)입니다.

## ledger_entries — 핵심 테이블

| 컬럼 | 타입 | 비고 |
|------|------|------|
| id | BIGINT PK | |
| tx_group_id | VARCHAR(36) | 경제적 사건 하나 = 그룹 하나. 호출부가 정함(예: UUID, `order:{id}:execute`) |
| account_id | BIGINT | |
| currency | VARCHAR(3) | 원안에 없던 컬럼(추가함). accounts를 조인하지 않고도 통화별 합계와 대사를 할 수 있음 |
| entry_type | VARCHAR(6) | DEBIT / CREDIT |
| amount | DECIMAL(19,4) | 항상 > 0 |
| balance_after | DECIMAL(19,4) NULL | 이 엔트리를 반영한 뒤의 계좌 잔액. 시스템 계좌는 NULL |
| applied_rate | DECIMAL(19,8) NULL | 적용환율. 환전이 아닌 거래는 NULL |
| created_at | DATETIME(6) | UTC |

- **append-only**입니다. 엔티티에 변경 메서드를 두지 않고(모든 컬럼 `updatable = false`), 리포지토리에서 delete를 호출하지 않습니다.
- 기록은 `TxGroup.builder(...).debit(...).credit(...).build()`로 묶음을 만들고 `LedgerService.post()`로 전기합니다. `build()`에서 통화별 균형을 검증하고, `post()`에서 계좌 통화 일치를 확인하며 사용자 잔액과 `balance_after`를 같은 트랜잭션에서 갱신합니다.
- `post()`는 계좌 UPDATE를 엔트리 INSERT보다 먼저 flush합니다(외래키 공유 락으로 인한 MySQL 데드락 방지, `hibernate.order_updates=true`로 id 순서). 같은 계좌를 동시에 갱신하면 늦은 쪽이 `@Version` 충돌로 실패하고, 이는 `CONCURRENT_UPDATE`(409, 재시도 가능)로 응답합니다.
- 잔액 부족 검사는 거래 묶음의 순변화가 아니라 **라인 순서대로** 합니다. 한 묶음 안에서도 잔액이 한순간이라도 음수가 되는 순서를 허용하지 않는 보수적인 설계입니다. 같은 계좌를 출금 후 입금하는 묶음이 필요하면 입금 라인을 먼저 둡니다.
- 대사: `LedgerReconciliationService.reconcileAccount()`(사용자 계좌의 원장 합계 = balance = 마지막 balance_after, 시스템 계좌는 `matched = null`로 비교 불가), `verifyCurrencyBalances()`(통화별 전체 Σ DEBIT = Σ CREDIT)
- 인덱스: `(account_id, id)`(계좌별 내역 cursor 페이지네이션), `(tx_group_id)`

### Q2: 원장의 엄격도 — **B로 결정 (2026-11-25)**

아래 두 안을 비교한 뒤 **(B)**를 택했습니다. (A)는 비교 기록으로 남겨 둡니다.

**USD 100 매수(적용환율 1,365, 기준율 1,350, 스프레드 수익 1,500원) 예시**

**(A) 기획 원안: 사용자 계좌 쌍 — 채택하지 않음**

| account | currency | type | amount |
|---------|----------|------|--------|
| 사용자 KRW | KRW | DEBIT(출금) | 136,500 |
| 사용자 USD | USD | CREDIT(입금) | 100.00 |

- 단순하고 원안과 같습니다.
- 대신 통화가 다른 두 줄이라, **같은 통화 안에서 차변 = 대변 검증이 성립하지 않습니다.** 스프레드 수익이 어디에도 기록되지 않습니다.
- 대사는 "Σ(KRW 엔트리) = KRW 잔액 합계" 수준까지만 가능합니다.

**(B) 채택: 시스템 계정을 포함한 통화별 균형**

| account | currency | type | amount |
|---------|----------|------|--------|
| 사용자 KRW | KRW | DEBIT | 136,500 |
| 시스템 FX 포지션 KRW | KRW | CREDIT | 135,000 |
| 시스템 스프레드 수익 KRW | KRW | CREDIT | 1,500 |
| 시스템 FX 포지션 USD | USD | DEBIT | 100.00 |
| 사용자 USD | USD | CREDIT | 100.00 |

- tx_group 안에서 **통화별로 Σ DEBIT = Σ CREDIT**이 성립합니다. KRW는 136,500 = 135,000 + 1,500, USD는 100 = 100입니다.
- 스프레드 수익이 원장에 남습니다.
- "모든 돈이 어디서 와서 어디로 갔는지"를 원장만 보고 재구성할 수 있습니다. 면접에서 "복식부기"를 설명하기에 가장 설득력 있는 형태입니다.
- 비용은 엔트리 수 증가(2개 → 5개)와 시스템 계정 락 경합입니다. 시스템 계정 잔액은 캐시하지 않고 원장 합계로만 관리하면 경합을 피할 수 있습니다.

> 위 표에서 "사용자 계정은 DEBIT이 출금"이라는 부호 관례는 기획 원안(원화 출금 = DEBIT)을 따랐습니다. 회계상 자산 관점과는 반대이므로, 구현할 때 관례를 하나로 확정하고 여기에 적습니다.

## fx_orders

| 컬럼 | 타입 | 비고 |
|------|------|------|
| id | BIGINT PK | |
| account_id | BIGINT | 출금 계좌(KRW) |
| target_currency | VARCHAR(3) | 원안에는 없음 — **추가 필요** (어떤 외화를 살지) |
| order_type | VARCHAR(10) | MARKET / LIMIT |
| side | VARCHAR(4) | BUY / SELL — Q7 (매도를 지원하지 않으면 생략) |
| target_rate | DECIMAL(19,8) | LIMIT일 때만 값이 있음 (MARKET이면 NULL) |
| krw_amount | DECIMAL(19,4) | |
| status | VARCHAR(20) | PENDING / COMPLETED / FAILED (+ CANCELLED / EXPIRED는 Q5) |
| failure_reason | VARCHAR(50) | FAILED일 때 사유 (INSUFFICIENT_BALANCE 등) |
| executed_rate, executed_at, tx_group_id | | 체결 결과 기록 |
| version | BIGINT | `@Version` |
| idempotency_key | VARCHAR(64) | UNIQUE |

### 상태 머신

```
MARKET: 생성 즉시 체결 시도 → COMPLETED | FAILED

LIMIT:  PENDING ──(목표환율 도달, 체결 성공)──▶ COMPLETED
           ├────(체결 최종 실패: 잔액 부족 등)──▶ FAILED
           ├────(사용자 취소)─────────────────▶ CANCELLED   ← Q5
           └────(만료)────────────────────────▶ EXPIRED     ← Q5
```

- 종결 상태에서는 전이를 허용하지 않습니다(409).
- 전이는 엔티티 메서드로만 합니다: `complete(rate, txGroupId, now)`, `fail(reason, now)`, `cancel(now)`
- **분할 매수(Q3)**: 분할을 도입하면 `fx_orders` 1건 아래에 `fx_order_tranches`(회차별 목표환율, 금액, 상태) 테이블을 두고, **ZSet 멤버와 체결의 단위를 회차(tranche)로** 하는 구조를 권장합니다. 주문의 상태는 회차 상태들을 모아 결정합니다(PARTIALLY_COMPLETED 추가 검토).

## idempotency_keys

| 컬럼 | 타입 | 비고 |
|------|------|------|
| idem_key | VARCHAR(64) PK | 클라이언트가 보낸 UUID (사용자 범위로 둘지는 Q10) |
| request_hash | CHAR(64) | 요청 본문의 SHA-256. **같은 키에 다른 본문**이 오면 422로 거부 |
| response_status, response_body | INT, JSON/TEXT | 처음 응답을 그대로 재전송하기 위해 저장 |
| status | VARCHAR(20) | IN_PROGRESS / COMPLETED — 처리 중에 같은 키가 또 오면 409 |
| created_at | DATETIME(6) | TTL 정리 기준 |

## Redis 키 설계 (초안)

| 키 | 타입 | 내용 |
|----|------|------|
| `fx:rate:{CUR}` | String / Hash | 최신 기준율, observedAt, source |
| `fx:orders:buy:{CUR}` | ZSet | member = orderId(또는 trancheId), score = target_rate. **매수 조건: 현재환율 ≤ target** → `ZRANGEBYSCORE key {현재환율} +inf` |
| `fx:orders:sell:{CUR}` | ZSet | **매도 조건: 현재환율 ≥ target** → `ZRANGEBYSCORE key -inf {현재환율}` (Q7) |
| `lock:account:{accountId}` | Redisson lock | 계좌 단위 분산 락 |
| `idem:{key}` | String (TTL) | 멱등성 빠른 경로 (DB가 최종 판정) |

> ZSet의 score는 double입니다. 목표환율을 score로 쓰면 비교 정밀도가 double로 떨어지므로, **ZSet은 후보를 거르는 용도로만 쓰고, 체결 직전에 DB의 `BigDecimal` target_rate로 다시 비교**합니다. 자세한 내용은 `kafka-redis-pipeline` 스킬을 참고하세요.

## 체결 흐름

```
1. 시세 틱 (fx-rate-topic) 수신
2. 신선도 검사 → 오래된 틱이면 무시
3. ZRANGEBYSCORE로 후보 추출 → ZREM으로 원자적 선점 (ZREM 반환값이 1인 쪽만 진행)
4. fx-order-matched 발행 (key = accountId → 같은 계좌의 이벤트는 순서가 보장됨)
5. Execution Consumer:
     a. 분산 락 획득 (lock:account:{id}, waitTime/leaseTime 명시)
     b. 트랜잭션 시작
        - 주문 조회: 이미 PENDING이 아니면 skip (멱등 컨슈머)
        - DB target_rate와 현재 적용환율을 BigDecimal로 재검증
        - 계좌 잔액 검사 → debit/credit (@Version)
        - ledger_entries 기록 (tx_group, 균형 검증)
        - 주문 COMPLETED
     c. 커밋
     d. 락 해제   ← 반드시 커밋 "후"
6. 일시 오류 → 재시도 → DLQ. 최종 실패 → 주문 FAILED + 알림
   (선점했는데 체결하지 못한 주문은 ZSet에 재등록할지, FAILED로 둘지 정책으로 정함)
```
