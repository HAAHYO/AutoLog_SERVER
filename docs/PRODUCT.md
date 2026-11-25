# AutoLog — 제품 스펙

> 사용자가 정리한 기획(2026-11-25)을 바탕으로 쓴 문서입니다. "결정 필요"로 표시한 항목은 아직 확정되지 않았습니다. 결정이 나면 이 문서를 고치고, 아래 "미결정 질문"에서 해당 항목을 지웁니다.

## 목적

금융권 IT 표준 스택으로 **실시간 환율 스트리밍 + 복식부기 원장 + 지정가 자동 환전 매칭 엔진**을 만드는 1인 포트폴리오 프로젝트입니다. 대용량 트래픽과 비동기 처리, 그리고 **금융 정합성(중복 환전 방지, 동시성 제어, 원장 대사)**을 직접 구현하고 검증하는 것이 목표입니다.

## 시스템 구성

```
[외부 환율 API / Mock Market]
        │ 1~5초 주기 수집 (Collector)
        ▼
   Kafka: fx-rate-topic
        │
        ├─▶ Rate Consumer ─▶ Redis (최신 환율) ─▶ SSE ─▶ 대시보드 차트
        │
        └─▶ Matching Engine ─▶ Redis ZSet (score = 목표환율) ZRANGEBYSCORE
                    │ 체결 대상 주문
                    ▼
             Kafka: fx-order-matched
                    │
                    ▼
            Execution Consumer ─▶ [분산 락 + @Version] ─▶ MySQL (accounts, fx_orders, ledger_entries)
                    │ 일시 오류
                    ▼
             Kafka: DLQ ─▶ 재시도 ─▶ 최종 실패 시 FAILED + 알림
```

## 기술 스택 (목표)

| 영역 | 기술 | 현재 상태 |
|------|------|-----------|
| Backend | Java 17+, Spring Boot, Spring Data JPA, QueryDSL | Boot 4.2.0 + JPA 세팅 완료, QueryDSL 미도입 — **Boot 버전 결정 필요 (Q1)** |
| DB | MySQL 8.0 (원장, 주문) | 로컬 설정 완료 |
| Cache / Lock / Matching | Redis (최신 시세, Redisson 분산 락, ZSet 매칭) | 미도입 |
| Message Broker | Apache Kafka (시세 스트리밍, 체결 이벤트, DLQ) | 미도입 |
| Frontend | React 또는 Next.js + TailwindCSS + Lightweight Charts | 이 저장소 범위 밖 (백엔드는 SSE 제공) |
| Infra / Test | Docker Compose, k6 (부하·동시성), Prometheus & Grafana (선택) | 미도입 |

## MVP 기능 (4단계)

### ① 실시간 환율 수집과 스트리밍

- **Collector**
  - 외부 환율 API(한국수출입은행, Yahoo Finance, Alpha Vantage 등)에서 1~5초 주기로 USD, JPY, EUR 시세를 수집합니다.
  - **Mock Market 모드**를 함께 둡니다. 가상으로 환율이 변하는 제너레이터로, 장 마감 시간이나 테스트·시연할 때 씁니다.
  - 외부 API와 Mock 중 무엇을 쓸지는 설정(프로필 또는 프로퍼티)으로 전환합니다.
- **전파**
  - 수집한 시세는 Kafka `fx-rate-topic`으로 발행합니다.
  - 컨슈머가 이를 받아 Redis의 최신 환율을 갱신하고, 동시에 접속 중인 클라이언트에 **SSE**로 보냅니다.

### ② 외화 계좌와 복식부기 원장

- **다통화 지갑**: 한 사용자가 KRW, USD, JPY(, EUR) 계좌를 각각 가집니다.
- **스프레드와 우대율**
  - 매매기준율에 통화별 스프레드를 적용하고, 고객 등급이나 이벤트에 따른 **환율 우대율**을 반영해 최종 적용 환율을 계산합니다.
  - 우대율 예: 트래블로그 100% 우대, 일반 고객 80% 우대
  - 계산식(가정):
    ```
    살 때 적용환율 = 기준율 + 기준율 × 스프레드율 × (1 − 우대율)
    팔 때 적용환율 = 기준율 − 기준율 × 스프레드율 × (1 − 우대율)
    ```
    확정은 **Q4**에서 합니다.
- **원장 기록**
  - 환전 한 건을 단순히 잔액만 바꾸는 방식으로 처리하지 않습니다.
  - **원화 출금 기록과 외화 입금 기록을 한 쌍(Transaction Group)으로** 불변(Immutable) 테이블에 기록합니다.
  - 복식부기를 어디까지 엄격하게 적용할지는 **Q2**에서 정합니다.

### ③ 지정가 자동 환전 매칭 엔진 (분할 매수·매도)

- **주문 등록**
  - 예약 주문을 등록합니다. 예: "USD가 1,350원 이하가 되면 100만 원어치를 자동 환전해 줘(3회 분할 매수)"
  - 주문 유형은 **시장가(MARKET, 즉시)**와 **지정가(LIMIT, 예약)** 두 가지입니다.
- **고속 매칭**
  - 환율이 들어올 때마다 DB 전체에서 예약 주문을 SELECT하지 않습니다.
  - 대신 예약 주문을 **Redis ZSet**(score = 목표환율)에 저장해 두고, 현재 환율에 도달하면 `ZRANGEBYSCORE`로 체결 대상만 O(log N + M)으로 뽑아냅니다.
  - 뽑아낸 주문은 Kafka `fx-order-matched`로 넘깁니다.
- 분할 체결이 정확히 무엇을 뜻하는지는 **Q3**에서 정합니다.

### ④ 금융 무결성 보장 장치 (핵심 어필 포인트)

- **멱등성 키**
  - 클라이언트는 환전을 요청할 때 `Idempotency-Key` 헤더에 UUID를 담아 보냅니다.
  - 네트워크 지연으로 재시도 요청이 와도, 이미 처리된 키라면 **이전 응답을 그대로 반환**해서 중복 환전을 막습니다. 확인은 Redis와 DB에서 합니다.
- **계좌 단위 분산 락 + 낙관적 락**
  - 사용자가 '즉시 환전'을 누르는 순간 '지정가 자동 환전'이 동시에 실행되어 잔액이 마이너스가 되는 상황을 막아야 합니다.
  - 이를 위해 계좌 ID를 키로 하는 **Redisson 분산 락**과 DB의 **`@Version`(낙관적 락)**을 이중으로 적용합니다.
- **DLQ와 보상 처리**
  - 자동 환전을 체결하다가 일시적인 오류가 나면 Kafka DLQ로 보내 재시도합니다.
  - 최종적으로 실패하면 주문 상태를 **FAILED**(사유: 잔액 부족 등)로 바꾸고 알림을 보냅니다.

## 핵심 테이블 (기획 원안)

| 테이블 | 핵심 컬럼 | 설계 의도 |
|--------|-----------|-----------|
| `accounts` | id, user_id, currency, balance `DECIMAL(19,4)`, version | 소수점 오차 방지(DECIMAL), 동시성 제어(version) |
| `fx_orders` | id, account_id, order_type(MARKET/LIMIT), target_rate, krw_amount, status(PENDING/COMPLETED/FAILED) | 시장가·지정가 주문의 상태 전이 관리 |
| `ledger_entries` | id, tx_group_id, account_id, entry_type(DEBIT/CREDIT), amount, balance_after, applied_rate, created_at | 모든 자금 이동을 차변·대변으로 기록하여 감사와 정산 대사(Reconciliation)에 활용 |
| `idempotency_keys` | idem_key(PK), request_hash, response_body, created_at | 중복 결제·환전 방지 |

이 원안을 바탕으로 구체화한 모델은 `docs/DOMAIN-MODEL.md`에 있습니다.

## 업무 규칙 (확정)

- 금액과 환율은 `BigDecimal`과 `DECIMAL`로 다룹니다. `double`은 쓰지 않습니다.
- 원장(`ledger_entries`)은 수정하거나 삭제하지 않습니다. 정정은 역분개로 합니다.
- 환전 한 건의 원장 기록은 하나의 `tx_group_id`로 묶이며, 반드시 한 트랜잭션 안에서 함께 저장됩니다.
- 같은 `Idempotency-Key`의 요청은 한 번만 처리하고, 이후 요청에는 처음 응답을 그대로 돌려줍니다.
- 하나의 주문(분할 체결이라면 분할 회차 하나)은 최대 한 번만 체결됩니다.
- 잔액은 음수가 될 수 없습니다.
- 지정가 체결 시점에 잔액이 부족하면 주문은 `FAILED`가 됩니다(기획 원안 기준).

## 미결정 질문

구현하다가 이 질문에 걸리면, 혼자 정하지 말고 사용자에게 물어봅니다. 임시로 정하고 진행해야 한다면 보수적인 쪽을 택하고, PR 본문에 그 가정을 적습니다.

| # | 질문 | 영향 범위 |
|---|------|-----------|
| 1 | **Spring Boot 버전**: 기획은 3.x인데 현재 4.2.0으로 세팅되어 있음. QueryDSL(5.x/OpenFeign 포크), Redisson, Spring Kafka가 Boot 4와 호환되는지 확인하고, 내릴지 유지할지 결정 | `build.gradle` 전체 |
| 2 | **원장 모델의 엄격도**: (A) 기획 원안대로 사용자 계좌 간 쌍(KRW 출금 + USD 입금)만 기록 vs (B) 시스템 계정(환전 포지션, 스프레드 수익)을 두어 tx_group 안에서 **통화별** 차변 = 대변이 되게 기록. 권장은 B — `DOMAIN-MODEL.md` 참고 | ledger, accounts, 대사 |
| 3 | **분할 매수의 의미**: "100만 원, 3회 분할"이 (a) 같은 목표환율에서 3회에 나눠 체결인지, (b) 목표환율을 단계별로 다르게(1,350 / 1,340 / 1,330) 두는 것인지 | fx_orders 모델, ZSet 멤버 단위 |
| 4 | **스프레드와 우대율 공식**, 반올림 방향, 고객 등급은 어디에 저장할지 | 적용환율 계산, users |
| 5 | **주문 취소와 만료**: 원안에 CANCELLED와 EXPIRED가 없음. 사용자 취소를 허용할지, 유효기간을 둘지 | 주문 상태 머신, ZSet 제거 |
| 6 | **잔액 hold**: 지정가 주문을 등록할 때 KRW를 묶어 둘지(hold), 체결할 때 차감할지 (원안: 체결 시 부족하면 FAILED이므로 hold 없음으로 읽힘) | 잔액 모델 |
| 7 | **매수와 매도**: 원안의 `krw_amount`는 KRW → 외화(매수) 기준. 외화 → KRW 매도도 지원할지, 지원한다면 금액 필드를 어떻게 할지 | fx_orders 컬럼 |
| 8 | **인증 방식** (이메일 + JWT? 소셜?) | user, Spring Security |
| 9 | **충전**: KRW 입금은 모의(관리자/테스트 API)로 할지 | 외부 청산 계정 |
| 10 | **멱등성 키의 범위와 TTL**: 사용자별로 유일하게 할지 전역으로 할지, 보관 기간 | idempotency_keys |
| 11 | **환율 신선도 기준**: 몇 초 지난 시세를 체결에 쓰지 않을지 | 매칭 엔진 |
