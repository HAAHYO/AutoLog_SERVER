# 구현 현황 — 스펙 vs 코드

> 기능을 추가하거나 바꿀 때마다 이 문서를 함께 갱신합니다. 마지막 갱신: 2026-11-25 (기획 반영)

## 현재 있는 것

| 항목 | 위치 | 비고 |
|------|------|------|
| Spring Boot 4.2.0 / Java 17 프로젝트 | `build.gradle` | Web MVC, JPA, Validation, Actuator, Lombok — 4.2 유지로 결정 (Q1) |
| 프로필 설정 | `application.yml`, `application-local.yml`, `src/test/resources/application-test.yml` | local은 MySQL(`ddl-auto: update`), test는 H2 |
| JPA Auditing + `BaseTimeEntity` | `global/config`, `global/entity` | `createdAt`, `updatedAt` (`LocalDateTime`) |
| 공통 예외 처리 | `global/exception` | `ErrorCode`, `BusinessException`, `ErrorResponse`, `GlobalExceptionHandler` |
| 헬스 체크 | `GET /actuator/health` | |
| 테스트 | `AutologServerApplicationTests.contextLoads()` | 이것 하나뿐 |
| CI | `.github/workflows/ci.yml` | develop·main 대상 PR과 푸시 시 `./gradlew build` |
| 하네스 | `AGENTS.md`, `CLAUDE.md`, `.claude/` | |
| 도메인 패키지 뼈대 | `domain/{rate,account,ledger,order,idempotency}` | `package-info.java`만 있음 (클래스 없음) |

## 기능 현황 (PRODUCT.md의 MVP 4단계)

| 단계 | 기능 | 상태 | 막는 것 |
|------|------|------|---------|
| 0 | 인프라: Docker Compose (MySQL, Redis, Kafka) | 미구현 | 없음 |
| 0 | 의존성: QueryDSL, Redisson, Spring Kafka, Testcontainers | 미구현 | Boot 4 호환 버전 확인 |
| ① | `CurrencyCode` (KRW, USD, JPY, EUR) | 미구현 | JPY 기준 단위 결정 |
| ① | Mock Market 제너레이터 | 미구현 | 없음 |
| ① | 외부 환율 API Collector | 미구현 | API 선정 |
| ① | `fx-rate-topic` 발행/구독 → Redis 최신 시세 | 미구현 | Kafka, Redis |
| ① | SSE 시세 스트림 | 미구현 | ① 시세 구독 |
| ② | `accounts` (다통화 지갑, `@Version`) | 미구현 | 없음 |
| ② | `ledger_entries` (tx_group, 통화별 균형 검증, balance_after) + 시스템 계정 | 미구현 | 없음 (모델 B 확정) |
| ② | 적용환율 계산 (스프레드, 우대율) | 미구현 | Q4 공식 |
| ② | 충전 (모의) | 미구현 | Q9 |
| ② | 즉시 환전 (MARKET) | 미구현 | accounts, ledger, 적용환율, 멱등성 키 |
| ③ | 지정가 주문 등록 / 조회 (`fx_orders`) | 미구현 | Q3 분할, Q5 취소·만료 |
| ③ | ZSet 매칭 엔진 → `fx-order-matched` | 미구현 | Redis, Kafka, ① 시세 |
| ③ | 체결 컨슈머 | 미구현 | ②, ③, ④ 락 |
| ④ | `idempotency_keys` + `Idempotency-Key` 처리 | 미구현 | Q10 |
| ④ | Redisson 계좌 락 + `@Version` | 미구현 | Redis |
| ④ | DLQ, 재시도, FAILED 처리, 알림 | 미구현 | Kafka |
| ④ | 대사(Reconciliation) 쿼리 / API | 미구현 | ledger |
| — | k6 부하·동시성 테스트 | 미구현 | 위 기능 |
| — | 회원, 인증 | 미구현 | Q8 |

## 추천 구현 순서

Kafka와 Redis 없이도 검증할 수 있는 **원장 핵심**부터 쌓고, 비동기 계층은 그 위에 올립니다. 비동기 계층에서 버그가 났을 때 원장 쪽이 이미 검증되어 있으면 원인을 좁히기 쉽기 때문입니다.

```
0. BaseTimeEntity → Instant (이슈 #1)  ※ Q1(Boot 4.2 유지), Q2(원장 모델 B)는 결정됨
1. CurrencyCode + AppliedRateCalculator (순수 로직 + 단위 테스트)
2. accounts + ledger_entries + 균형·balance_after 검증 + 대사 쿼리
3. 모의 충전 + 즉시 환전(MARKET) API — DB 락(@Version)만으로 먼저 완성
4. idempotency_keys + Idempotency-Key
5. Docker Compose (MySQL, Redis, Kafka) + 의존성 추가
6. Mock Market → fx-rate-topic → Redis 최신 시세 → SSE
7. 지정가 주문 등록 + ZSet 등록 (AFTER_COMMIT)
8. 매칭 엔진 → fx-order-matched → 체결 컨슈머 (Redisson 락 + @Version)
9. DLQ, 재시도, FAILED, 알림
10. 동시성 테스트(Testcontainers) + k6
11. (선택) 외부 환율 API Collector, Prometheus/Grafana, 인증
```

## 브랜치 계획

브랜치와 PR은 아래 묶음 단위로 만듭니다. 묶음 안의 작은 작업은 커밋으로 나눕니다. "추천 순서 번호"는 바로 위 "추천 구현 순서"의 0~11 번호입니다. "기능 현황" 표의 단계(0, ①~④)와는 다른 번호입니다.

| 브랜치 | 추천 순서 번호 | 내용 |
|--------|-----------|------|
| `feat/ledger-core` | 0~2 | `BaseTimeEntity` → `Instant`, `CurrencyCode`, 적용환율 계산기, `accounts`, `ledger_entries`(모델 B, 시스템 계정), 균형·balance_after 검증, 대사 |
| `feat/exchange` | 3~4 | 모의 충전, 즉시 환전(MARKET) API, `idempotency_keys` + `Idempotency-Key` |
| `chore/infra` | 5 | Docker Compose(MySQL, Redis, Kafka), QueryDSL·Redisson·Spring Kafka·Testcontainers 의존성 |
| `feat/rate-streaming` | 6 | Mock Market, `fx-rate-topic`, Redis 최신 시세, SSE |
| `feat/limit-order` | 7~9 | 지정가 주문, ZSet 매칭, `fx-order-matched` 체결 컨슈머, 분산 락, DLQ·FAILED |
| `test/load` | 10 | 동시성 통합 테스트, k6 |

진행하면서 묶음이 너무 커지면(예: PR 하나에 파일 40개 이상), 사용자와 상의해 둘로 나눕니다.

## 알려진 이슈 / 결정 대기

| # | 내용 | 영향 | 제안 |
|---|------|------|------|
| 1 | `BaseTimeEntity`가 `LocalDateTime`을 쓰는데, 컨벤션은 `Instant`(UTC)임. `hibernate.jdbc.time_zone: UTC` 설정과 섞이면 시각이 9시간 어긋나 보일 수 있음 | 감사 시각, `ledger_entries.created_at` | 첫 엔티티를 만들기 전에 `Instant`로 변경 |
| 2 | ~~Spring Boot 4.2.0 vs 기획의 3.x~~ → **4.2 유지로 결정 (2026-11-25)** | 의존성마다 Boot 4 호환 버전을 골라야 함 | 의존성을 추가할 때 문서로 호환성 확인 |
| 3 | 마이그레이션 도구 없음 (`ddl-auto: update`) | 이름·타입 변경이 조용히 반영되지 않음 | 원장 스키마가 확정되면 Flyway 도입을 검토 |
| 4 | 테스트가 `contextLoads()` 하나뿐 | 동작 검증 수단이 없음 | 1단계부터 순수 단위 테스트 작성 |
| 5 | H2로 테스트 | MySQL 락, DECIMAL, `DATETIME(6)` 미검증 | 5단계에서 Testcontainers(MySQL, Redis, Kafka) 도입 |
| 6 | 금액 JSON 직렬화 방식 미결정 (REST) | JS 대시보드에서 정밀도 손실 가능 | 첫 금액 API를 만들 때 결정 (Kafka 메시지는 문자열로 확정) |
| 7 | Spring Security 없음 | 모든 엔드포인트가 공개 상태 | 인증 방식이 결정되면 도입 (Q8). 그 전에는 userId를 요청 파라미터나 헤더로 대신 받음 |
