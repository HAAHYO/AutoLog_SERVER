---
name: money-ledger
description: "AutoLog의 금융 핵심 규칙 — BigDecimal 금액·환율 처리(scale, RoundingMode, compareTo), DECIMAL 컬럼 정의, 스프레드·우대율 적용환율 계산, 복식부기 원장(accounts/ledger_entries, tx_group, append-only, 차대 균형, balance_after, 역분개, 대사), 잔액 캐시와 @Version, 주문 상태 전이(MARKET/LIMIT, PENDING/COMPLETED/FAILED)와 정확히-한-번 체결. 금액·환율·잔액·원장·주문·체결 코드를 작성하거나 리뷰할 때 반드시 사용한다. 빌드는 통과하는데 돈이 틀리는 버그를 막기 위한 스킬이다."
---

# 금액·원장·체결 규칙

이 서비스에서 가장 위험한 버그는 **컴파일도 되고 테스트도 통과하는데 잔액이 틀리는** 버그입니다.

- **한 트랜잭션 안의 정합성**은 이 스킬이 다룹니다.
- **여러 프로세스와 메시지 사이의 정합성**(Kafka, 분산 락, ZSet, 멱등성 키)은 `kafka-redis-pipeline` 스킬이 다룹니다.
- 테이블 설계는 `docs/DOMAIN-MODEL.md`를 따릅니다.

## 1. 타입과 컬럼

| 대상 | Java | DB 컬럼 |
|------|------|---------|
| 금액, 잔액 | `BigDecimal` | `DECIMAL(19, 4)` — `@Column(precision = 19, scale = 4)` (기획 원안) |
| 환율 (기준율, 적용환율, 목표환율) | `BigDecimal` | `DECIMAL(19, 8)` (초안) |
| 스프레드율, 우대율 | `BigDecimal` | `DECIMAL(7, 6)` (초안, 예: 0.017500, 0.800000) |
| 통화 | `enum CurrencyCode` — KRW(0), USD(2), JPY(0), EUR(2) | `VARCHAR(3)` + `EnumType.STRING` |

- `double`, `float`, `long`(최소 단위 정수)을 섞어 쓰지 않습니다. 이 프로젝트는 `BigDecimal`로 통일합니다.
- `new BigDecimal(0.1)`은 금지입니다. `new BigDecimal("0.1")`이나 `BigDecimal.valueOf(0.1)`을 씁니다.
- `CurrencyCode`의 괄호 안 숫자는 **사용자에게 지급·차감되는 최종 금액의 소수 자릿수**입니다. DB의 scale 4는 저장 정밀도이고, 실제 거래 금액은 통화 자릿수로 맞춥니다.
- 금액만 따로 다니는 경우가 많아지면 `Money(BigDecimal amount, CurrencyCode currency)` 값 객체를 도입합니다. 통화가 다른 금액을 더하는 실수를 타입으로 막을 수 있습니다.
- JPY는 시세가 보통 100엔 단위로 고시됩니다. **환율의 기준 단위(1엔당 또는 100엔당)를 통화 enum이나 시세 모델에 명시**합니다. 이걸 섞으면 금액이 100배 틀립니다.

## 2. BigDecimal 연산

```java
if (balance.compareTo(amount) < 0) { ... }        // O 비교는 compareTo
if (balance.equals(BigDecimal.ZERO)) { ... }       // X 0.0000은 ZERO와 equals가 false
if (balance.signum() == 0) { ... }                 // O

BigDecimal usd = krw.divide(rate, 8, RoundingMode.DOWN);      // O scale + RoundingMode
BigDecimal usd = krw.divide(rate);                            // X 무한소수면 ArithmeticException

BigDecimal payout = usd.setScale(CurrencyCode.USD.getScale(), RoundingMode.DOWN);
```

- 중간 계산은 높은 scale로 하고, **최종 금액에서 한 번만** 반올림합니다.
- **반올림 방향은 정책 클래스 한곳에 모읍니다**(예: `FxRoundingPolicy`). 원칙은 "고객이 받는 금액은 내림, 고객이 내는 금액은 올림"(가정, 결정 기록 참고)입니다. 코드 곳곳에 `HALF_UP`을 흩어 두지 않습니다.
- 반올림 때문에 생기는 단수 차이는 원장에 흔적을 남깁니다. 스프레드 수익 계정에 넣습니다. 반올림 차이가 흔적 없이 사라지면 대사가 맞지 않습니다.
- `BigDecimal`을 `HashMap`이나 `HashSet`의 키로 쓰지 않습니다. `equals`와 `hashCode`가 scale을 포함하기 때문입니다.
- 테스트에서 금액을 비교할 때는 `isEqualByComparingTo`를 씁니다(AssertJ). DB에서 읽은 값은 scale이 4입니다.

## 3. 적용환율 계산 (스프레드와 우대율)

```
스프레드 금액 = 기준율 × 스프레드율
우대 후 스프레드 = 스프레드 금액 × (1 − 우대율)
살 때 (KRW → 외화) 적용환율 = 기준율 + 우대 후 스프레드
팔 때 (외화 → KRW) 적용환율 = 기준율 − 우대 후 스프레드
```

이 공식은 **가정**입니다(PRODUCT Q4).

- 계산은 **순수 함수/도메인 서비스**(`AppliedRateCalculator` 등) 하나에 모으고, 경계값 단위 테스트를 작성합니다.
  - 우대율 0%, 80%, 100%
  - 스프레드율 0
  - 우대율이 1을 넘는 값은 거부
- 적용환율의 scale과 반올림 방향(살 때는 올림, 팔 때는 내림이 고객에게 불리한 쪽)을 정하고 결정 기록에 적습니다.
- 체결 결과에는 **기준율, 적용환율, 스프레드율, 우대율, 시세 시각**을 모두 남깁니다. 나중에 "왜 이 금액인가"를 재현할 수 있어야 합니다. 원장 `applied_rate`와 주문의 `executed_rate`에 저장합니다.

## 4. 복식부기 원장 — accounts / ledger_entries

### 기록 단위

- 환전 한 건은 **`tx_group_id` 하나**로 묶이며, 엔트리 2개 이상으로 구성됩니다.
- **한 트랜잭션** 안에서 다음이 함께 일어납니다: 계좌 잔액 변경(`accounts.balance`, `@Version`) + 엔트리 INSERT + 주문 상태 전이
- 엔트리는 `LedgerEntry`의 정적 팩토리나 `TxGroup` 빌더로만 만들고, 저장하기 직전에 **불변식을 검증**합니다.

### 불변식

1. **amount는 항상 0보다 큽니다.** 방향은 `entry_type`(DEBIT/CREDIT)으로 표현합니다.
2. **balance_after는 직전 잔액에 이 엔트리를 반영한 값과 같아야 합니다.** 잔액을 변경하는 바로 그 시점에 계산해 넣습니다. 따로 다시 조회해서 계산하면 동시성 때문에 틀릴 수 있습니다.
3. **append-only**: `LedgerEntry`에는 setter도 변경 메서드도 없습니다. 리포지토리에서 delete를 호출하지 않습니다. 정정은 **역분개**(반대 방향의 새 tx_group, `reference`로 원본을 가리킴)로 합니다.
4. **차대 균형** — 원장 모델 B(시스템 계정 포함)로 결정했습니다(PRODUCT Q2).
   - tx_group 안에서 **통화별로 Σ DEBIT = Σ CREDIT**을 정확히(`compareTo == 0`) 검증합니다. 허용 오차는 두지 않습니다.
   - 환전 1건의 구성: 사용자 출금 계좌, 시스템 FX 포지션(출금 통화), 시스템 스프레드 수익(출금 통화), 시스템 FX 포지션(입금 통화), 사용자 입금 계좌. 예시는 `docs/DOMAIN-MODEL.md`에 있습니다.
   - 스프레드와 반올림 차이는 시스템 스프레드 수익 계정으로 갑니다.
   - 시스템 계정의 잔액은 `accounts.balance`에 캐시하지 않고 원장 합계로만 관리하는 것을 기본으로 합니다. 모든 체결이 같은 시스템 계정 행을 갱신하면 락 경합이 생기기 때문입니다.
   - 검증에 실패하면 버그이므로 `LEDGER_UNBALANCED`(500)를 던지고 error 로그를 남깁니다.
5. **멱등**: 같은 주문(또는 회차)의 체결이 두 번 기록되지 않도록, 원장이나 주문 쪽 자연키에 **DB UNIQUE 제약**을 겁니다. 예: `fx_orders.tx_group_id`, `(order_id, tranche_no)`

### 잔액과 대사 (Reconciliation)

- 진실의 원천은 ledger_entries입니다. `accounts.balance`는 캐시입니다.
- 대사 쿼리를 둡니다. 계좌마다 `Σ(입금) − Σ(출금)`이 `accounts.balance`와 같아야 하고, 마지막 엔트리의 `balance_after`와도 같아야 합니다. 이 쿼리를 테스트나 관리 API로 실행할 수 있게 하는 것이 이 프로젝트의 어필 포인트입니다.
- 추가로 "통화별로 전체 원장의 Σ DEBIT = Σ CREDIT"이어야 합니다.
- **잔액 부족 검사와 차감은 같은 락 범위 안에서** 합니다. 락은 `kafka-redis-pipeline`의 분산 락과 `@Version`을 씁니다.

## 5. 주문 상태 전이

```
MARKET: 생성 → 즉시 체결 시도 → COMPLETED | FAILED
LIMIT:  PENDING → COMPLETED | FAILED (| CANCELLED | EXPIRED — Q5)
```

- 종결 상태(COMPLETED, FAILED, ...)에서는 어떤 전이도 허용하지 않습니다(409).
- 전이는 `FxOrder`의 메서드로만 합니다. 상태와 체결 결과 필드(`executedRate`, `executedAt`, `txGroupId` 또는 `failureReason`)를 **함께** 설정합니다.
- **주문 상태 전이 + 잔액 변경 + 원장 기록은 한 트랜잭션**으로 묶습니다. 셋 중 하나만 커밋되는 경로가 있으면 BLOCKER입니다.
- 지정가 조건(매수는 `현재 적용환율 ≤ target_rate`, 매도는 `≥`)은 **체결 트랜잭션 안에서 DB 값으로 다시 판정**합니다. ZSet은 후보 추출용일 뿐입니다.
- 지정가를 기준율과 비교할지, 적용환율과 비교할지 정해야 합니다. 사용자가 보는 목표환율은 보통 **적용환율 기준**입니다(가정). 결정하면 기록합니다.
- 분할 매수(Q3)를 도입하면 체결 단위는 회차(tranche)이며, 회차마다 위 규칙을 적용합니다.

## 6. 시간

- 비즈니스 시각(시세 관측, 체결, 원장 기록)은 `Instant`(UTC)를 쓰고, 컬럼은 `DATETIME(6)`으로 둡니다.
- 현재 시각은 `Clock` 빈에서 받습니다. 엔티티 메서드에는 `Instant now`를 인자로 넘깁니다.

## 7. 테스트 필수 항목

금액, 원장, 체결 코드를 바꿀 때 아래 테스트가 없다면 PR 체크리스트를 체크하지 않습니다.

- [ ] 적용환율: 우대율 0, 80, 100%, 매수·매도 방향, 반올림 경계
- [ ] 원장 균형: 불균형 tx_group은 생성되지 않는다 (순수 단위 테스트)
- [ ] balance_after: 연속된 엔트리의 balance_after가 누적 합계와 같다
- [ ] 잔액 부족: 체결하면 FAILED가 되고, 잔액과 원장은 변하지 않는다
- [ ] 종결 상태 전이 거부: COMPLETED 주문에 다시 체결이나 실패를 시도하면 409
- [ ] 멱등: 같은 체결 이벤트가 두 번 와도 tx_group은 1개
- [ ] 대사: 시나리오를 실행한 뒤 원장 합계, balance, 마지막 balance_after가 모두 일치
- [ ] (통합) 동시성: `kafka-redis-pipeline` 테스트 절 참고 — **H2에서만 실행했다면 MySQL 미검증으로 표기**

## 결정 기록

처음 구현할 때 정한 값을 여기에 적습니다. 바꿀 때는 날짜와 이유를 함께 적습니다.

| 항목 | 결정 | 날짜 |
|------|------|------|
| 금액 scale | 4 (기획 원안 `DECIMAL(19,4)`) | 2026-11-25 |
| 환율 / 스프레드율 scale | 미결정 (초안 8 / 6) | |
| 원장 모델 | **B** — 시스템 계정 포함, tx_group 안에서 통화별 차대 균형 (Q2) | 2026-11-25 |
| DEBIT/CREDIT 부호 관례 | 미결정 — 원안은 "사용자 계좌 DEBIT = 출금" | |
| 반올림 정책 | 미결정 — 가정: 고객이 받는 금액은 내림, 내는 금액은 올림 | |
| 지정가 비교 기준 (기준율 / 적용환율) | 미결정 — 가정: 적용환율 | |
| JPY 환율 기준 단위 (1엔 / 100엔) | 미결정 | |
| 금액 JSON 직렬화 (숫자 / 문자열) | 미결정 — Kafka 메시지는 문자열로 확정 | 2026-11-25 |
