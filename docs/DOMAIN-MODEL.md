# 도메인 모델 (초안)

> **상태: 초안, 아무것도 구현되지 않았습니다.** 첫 구현 때 이 문서를 실제 코드에 맞게 고칩니다. 이 문서와 코드가 다르면 **코드가 진실**이며, 문서를 갱신합니다.

## 애그리거트 (계획)

| 도메인 패키지 | 엔티티 | 역할 | 상태 |
|---------------|--------|------|------|
| `user` | `User` | 사용자, 인증 주체 | 미구현 — 인증 방식 미결정 (PRODUCT Q4) |
| `rate` | `ExchangeRate` (틱/스냅샷) | 통화쌍 환율, `observedAt`, `receivedAt`, 출처 | 미구현 |
| `account` | `Wallet` (사용자 × 통화) | 사용자에게 보여주는 잔액 (원장 잔액 캐시) | 미구현 |
| `ledger` | `LedgerAccount`, `JournalEntry`, `Posting` | 복식부기 원장 | 미구현 |
| `order` | `ExchangeOrder` | 지정가 환전 주문 | 미구현 |

공통 enum으로 `CurrencyCode`(ISO 4217, 통화별 소수 자릿수 포함)를 둡니다. 위치는 `global/` 아래로 정합니다(가정).

## 원장 구조

```
JournalEntry (분개 1건 = 경제적 사건 1건)
├── id, type (DEPOSIT / EXCHANGE / FEE / REVERSAL ...)
├── idempotencyKey  ← DB UNIQUE
├── reference       ← 주문 id, 원 분개 id(역분개) 등
├── occurredAt (Instant)
└── postings: Posting[2..n]
       ├── ledgerAccount → LedgerAccount
       ├── currency
       ├── direction (DEBIT / CREDIT)
       └── amount (> 0)

LedgerAccount
├── 사용자 지갑 계정 (사용자 × 통화)
└── 시스템 계정: 환전 풀(통화별), 수수료 수익, 외부 청산(입출금)
```

**불변식:** 하나의 JournalEntry 안에서 통화별로 `Σ DEBIT = Σ CREDIT`입니다.

### 예: USD 100 매수 체결 (환율 1,350, 수수료 없음 가정)

| 계정 | 통화 | 차변 | 대변 |
|------|------|------|------|
| 사용자 KRW 지갑 | KRW | | 135,000 |
| 환전 풀 (KRW) | KRW | 135,000 | |
| 환전 풀 (USD) | USD | | 100.00 |
| 사용자 USD 지갑 | USD | 100.00 | |

KRW는 135,000 = 135,000, USD는 100 = 100으로 통화별 균형이 맞습니다.

> 차변·대변 중 어느 쪽이 사용자 잔액의 증가인지(계정 성격, 자산/부채 관점)는 첫 구현 때 확정하고 여기에 적습니다. 위 표는 "사용자 지갑 = 차변이 증가"로 가정했습니다.

## 지정가 주문 상태 머신 (초안)

```
          ┌──(지정가 도달, 잔액 충분)──▶ EXECUTED
PENDING ──┼──(사용자 취소)───────────▶ CANCELLED
          └──(만료)──────────────────▶ EXPIRED   ← PRODUCT Q6에 따라 결정
```

- 종결 상태에서는 전이를 허용하지 않습니다(409).
- 전이는 `ExchangeOrder`의 메서드로만 합니다: `execute(rate, now)`, `cancel(now)`, `expire(now)`
- 체결 시 다음 값을 함께 기록합니다: `executedAt`, `executedRate`, `rateObservedAt`, `journalEntryId`
- 부분 체결(Q7)과 잔액 hold(Q9)를 도입하면 이 상태 머신이 바뀝니다.

## 체결 흐름 (초안)

```
환율 틱 수신 (트랜잭션 밖)
  → 신선도 검사 (오래된 틱이면 무시)
  → 해당 통화쌍의 PENDING 주문 중 조건에 맞는 주문 조회 (인덱스: currencyPair, status, limitRate)
  → 주문마다 별도 트랜잭션으로:
       조건부 UPDATE (status = PENDING → EXECUTED), 영향 행이 0이면 건너뜀
       잔액 행 락 → 잔액 검사 → 분개 생성(균형 검증) → 잔액 캐시 갱신
  → 커밋 후 이벤트 (알림, 스트림 전송)
```

주문 하나의 실패가 다른 주문의 체결을 막지 않도록, **주문마다 트랜잭션을 분리**합니다.
