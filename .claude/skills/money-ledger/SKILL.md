---
name: money-ledger
description: "AutoLog의 금융 핵심 규칙 — BigDecimal 금액·환율 처리(scale, RoundingMode, compareTo), DECIMAL 컬럼 정의, 복식부기 원장(append-only, 통화별 차대 균형, 역분개), 잔액 계산과 캐시, 지정가 주문 체결의 정확히-한-번 보장(락, @Version, 조건부 UPDATE, 멱등키), 환율 데이터의 시각·신선도. 금액·환율·잔액·원장·주문·체결 코드를 작성하거나 리뷰할 때 반드시 사용한다. 빌드는 통과하는데 돈이 틀리는 버그를 막기 위한 스킬이다."
---

# 금액·원장·체결 규칙

이 서비스에서 가장 위험한 버그는 **컴파일도 되고 테스트도 통과하는데 잔액이 틀리는** 버그입니다. 아래 규칙은 그런 버그를 막기 위한 것입니다.

> 이 문서의 수치(scale, precision)는 초안입니다. 처음 구현할 때 정한 값을 이 문서에 확정 값으로 기록합니다.

## 1. 금액과 환율 타입

| 대상 | Java | DB 컬럼 (초안) |
|------|------|----------------|
| 금액 | `BigDecimal` | `DECIMAL(19, 4)` — `@Column(precision = 19, scale = 4)` |
| 환율 | `BigDecimal` | `DECIMAL(19, 8)` — `@Column(precision = 19, scale = 8)` |
| 통화 | `enum CurrencyCode` (ISO 4217: KRW, USD, JPY, EUR ...) | `VARCHAR(3)` + `EnumType.STRING` |

- `double`, `float`, `long`(최소 단위 정수)을 섞어 쓰지 않습니다. 이 프로젝트는 `BigDecimal`로 통일합니다.
- `new BigDecimal(0.1)`은 금지입니다. 이 값은 `0.1000000000000000055...`가 됩니다. `new BigDecimal("0.1")`이나 `BigDecimal.valueOf(0.1)`을 씁니다.
- 통화마다 소수 자릿수가 다릅니다. KRW와 JPY는 0, USD와 EUR은 2입니다. **사용자에게 지급·차감되는 최종 금액**은 해당 통화의 자릿수로 반올림합니다. 자릿수는 `CurrencyCode` enum에 필드로 둡니다(`KRW(0)`, `USD(2)`).
- 금액만 따로 다니는 경우가 많아지면 `Money(BigDecimal amount, CurrencyCode currency)` 값 객체를 도입합니다. 통화가 다른 금액끼리 더하는 실수를 타입으로 막을 수 있습니다.

## 2. BigDecimal 연산

```java
// 비교: compareTo를 씁니다
if (balance.compareTo(amount) < 0) { ... }        // O
if (balance.equals(BigDecimal.ZERO)) { ... }       // X — 0.00은 ZERO와 equals가 false
if (balance.signum() == 0) { ... }                 // O

// 나눗셈: scale과 RoundingMode를 반드시 지정합니다
BigDecimal rate = quote.divide(base, 8, RoundingMode.HALF_UP);   // O
BigDecimal rate = quote.divide(base);                           // X — 무한소수면 ArithmeticException

// 최종 금액: 통화 자릿수로 맞춥니다
BigDecimal payout = raw.setScale(currency.getScale(), RoundingMode.DOWN);
```

- **반올림 정책은 한곳에 모읍니다.** 예를 들어 "고객에게 지급하는 금액은 내림(DOWN), 수수료는 올림(UP)"처럼 방향이 바뀌는 규칙은 상수나 정책 클래스로 정의합니다. 코드 곳곳에 `HALF_UP`이 흩어지면 안 됩니다. 정책은 처음 구현할 때 정하고 이 문서에 기록합니다.
- 중간 계산은 높은 scale로 하고, **마지막에 한 번만** 반올림합니다. 단계마다 반올림하면 오차가 누적됩니다.
- `BigDecimal`을 `HashSet`이나 `HashMap`의 키로 쓰지 않습니다. `equals`와 `hashCode`가 scale을 포함하기 때문입니다.
- JPA에서 DB로 읽어 온 `BigDecimal`은 컬럼의 scale을 가집니다(`1` → `1.0000`). 테스트에서 금액을 비교할 때는 `isEqualByComparingTo`를 씁니다(AssertJ).

## 3. 복식부기 원장

### 모델 (초안)

- **JournalEntry(분개)**: 하나의 경제적 사건(입금, 환전 체결, 수수료)을 나타냅니다. `id`, `type`, `idempotencyKey`, `occurredAt(Instant)`, `reference`(주문 id 등)를 가집니다.
- **Posting(전기 라인)**: 분개 하나에 2개 이상 붙습니다. `ledgerAccount`, `currency`, `direction(DEBIT/CREDIT)`, `amount(> 0)`를 가집니다.
- **LedgerAccount(원장 계정)**: 사용자 지갑(통화별), 시스템 계정(환전 풀, 수수료 수익, 외부 입출금 청산 계정)이 있습니다.

### 불변식

1. **한 분개 안에서 통화별 차변 합계와 대변 합계가 같아야 합니다.**
   - 환전 분개에는 통화가 두 개 등장합니다. 예를 들어 KRW 차변/대변과 USD 차변/대변이 있습니다.
   - 환율 차이는 시스템의 환전 풀 계정이 흡수하므로, 각 통화는 그 통화 안에서 균형을 이룹니다.
   - 이 검증은 분개를 저장하기 직전, **도메인 코드(JournalEntry 생성 팩토리)**에서 합니다. 실패하면 `LEDGER_UNBALANCED`(500 계열 내부 오류)를 던집니다. 이 오류는 버그가 있다는 뜻이므로 로그를 error 레벨로 남깁니다.
2. **Posting의 amount는 항상 양수입니다.** 방향은 `direction`으로 표현하고, 음수 금액으로 방향을 표현하지 않습니다.
3. **append-only**: Posting과 JournalEntry는 UPDATE도 DELETE도 하지 않습니다.
   - 엔티티에 변경 메서드를 만들지 않습니다.
   - 리포지토리에 delete 계열 메서드를 호출하는 코드를 만들지 않습니다.
   - 정정이 필요하면 **역분개**(차변과 대변을 뒤집은 새 분개)를 추가하고, 원래 분개를 `reference`로 가리킵니다.
4. **멱등성**: 같은 사건이 두 번 기록되지 않도록, 분개마다 `idempotencyKey`(예: `order:{orderId}:execute`)에 **DB 유니크 제약**을 겁니다. 애플리케이션 코드의 확인만으로는 동시 요청을 막을 수 없습니다.

### 잔액

- 진실의 원천(source of truth)은 Posting의 합계입니다.
- 성능을 위해 잔액 캐시(`Wallet.balance` 등)를 둔다면 다음을 지킵니다.
  - Posting 저장과 **같은 트랜잭션** 안에서 갱신합니다.
  - 동시 갱신은 행 락(`PESSIMISTIC_WRITE`)이나 `@Version`으로 직렬화합니다.
  - 캐시와 Posting 합계를 비교하는 검증 쿼리나 테스트를 둡니다.
- **잔액 부족 검사와 차감은 같은 락 범위 안에서** 합니다. 검사하고 락 없이 차감하면, 동시 요청 두 개가 모두 검사를 통과해 잔액이 음수가 됩니다.

## 4. 지정가 주문 체결 — 정확히 한 번

### 상태 머신 (초안)

```
PENDING ──(지정가 도달 + 잔액 충분)──▶ EXECUTED
   │
   ├──(사용자 취소)──▶ CANCELLED
   └──(만료 시각 경과)──▶ EXPIRED        ← 만료를 둘지는 미결정 (docs/PRODUCT.md)
```

종결 상태(EXECUTED, CANCELLED, EXPIRED)에서는 어떤 전이도 허용하지 않습니다.

### 동시성

체결 경로는 여러 갈래에서 같은 주문에 동시에 도달할 수 있습니다. 환율 틱 처리 스레드, 사용자의 취소 요청, 스케줄러가 그 예입니다. 다음 중 하나로 **한 번만** 성공하게 만듭니다.

| 방식 | 방법 | 비고 |
|------|------|------|
| 조건부 UPDATE | `update ExchangeOrder o set o.status = 'EXECUTED' ... where o.id = :id and o.status = 'PENDING'`을 실행하고, 영향 행 수가 1일 때만 분개 | 가장 단순하고 확실함. 권장 |
| 낙관적 락 | 엔티티에 `@Version`을 두고, `OptimisticLockException`이 나면 재조회하여 이미 처리됐는지 확인 | 충돌이 적을 때 적합 |
| 비관적 락 | `@Lock(PESSIMISTIC_WRITE)`로 조회한 뒤 상태 확인 | 잔액 행 락과 같이 쓸 때 **락 획득 순서를 고정**해야 데드락을 막을 수 있음 |

어떤 방식이든, **주문 상태 전이 + 분개 + 잔액 갱신은 한 트랜잭션**으로 묶습니다.

**H2는 MySQL의 락 동작을 재현하지 못합니다.** 동시성 테스트(`ExecutorService` + `CountDownLatch`)가 H2에서 통과했더라도 "MySQL 미검증"이라고 보고합니다.

### 체결 환율

- 체결에 사용한 **환율 값, 환율의 시각, 출처**를 주문(또는 분개)에 함께 저장합니다. 나중에 "왜 이 금액이 나왔는가"를 재현할 수 있어야 합니다.
- 지정가 비교 방향을 통화쌍과 매수·매도 방향에 따라 정확히 정의하고 테스트합니다. 예: "USD를 살 때 환율이 limit **이하**로 내려오면 체결". 방향이 뒤집히는 버그가 가장 흔합니다.

## 5. 환율 데이터

- 환율 틱에는 공급자가 준 시각(`observedAt`)과 서버가 받은 시각(`receivedAt`)을 둘 다 `Instant`로 저장합니다.
- **오래된 환율로 체결하지 않습니다.** 신선도 기준(예: N초 이상 지난 환율은 체결에 쓰지 않음)을 정하고, 기준을 넘으면 체결을 보류합니다. N은 미결정입니다.
- 공급자 장애, 0이나 음수 같은 비정상 값, 급격한 튐(spike)에 대한 방어 규칙은 처음 구현할 때 정합니다.
- 외부 공급자 호출은 트랜잭션 밖에서 하고, 타임아웃을 반드시 설정합니다.

## 6. 테스트 필수 항목

금액, 원장, 체결 코드를 바꿀 때는 다음 테스트가 있어야 합니다. 없다면 PR 체크리스트를 체크하지 않습니다.

- [ ] 분개 균형: 통화별 차변과 대변이 맞지 않는 분개는 생성되지 않는다 (순수 단위 테스트)
- [ ] 반올림: 경계값(0.5, 통화 자릿수 경계)에서 정책대로 반올림된다
- [ ] 지정가 방향: limit 직전, limit과 같을 때, limit을 넘었을 때 각각 체결 여부
- [ ] 종결 상태 전이 거부: EXECUTED 주문을 취소하면 409
- [ ] 멱등성: 같은 멱등키로 두 번 요청하면 분개가 1개만 생긴다
- [ ] (가능하면) 동시 체결: 같은 주문을 N개 스레드에서 체결하면 EXECUTED 1건, 분개 1개 — **H2에서만 실행했다면 MySQL 미검증으로 표기**

## 결정 기록

처음 구현할 때 정한 값을 여기에 적습니다. 바꿀 때는 날짜와 이유를 함께 적습니다.

| 항목 | 결정 | 날짜 |
|------|------|------|
| 금액 scale / 환율 scale | 미결정 (초안 4 / 8) | |
| 반올림 정책 | 미결정 | |
| 체결 동시성 방식 | 미결정 (조건부 UPDATE 권장) | |
| 환율 신선도 기준 | 미결정 | |
| 금액 JSON 직렬화 | 미결정 | |
