# 구현 현황 — 스펙 vs 코드

> 기능을 추가하거나 바꿀 때마다 이 문서를 함께 갱신합니다. 마지막 갱신: 2026-11-25

## 현재 있는 것

| 항목 | 위치 | 비고 |
|------|------|------|
| Spring Boot 4.2.0 / Java 17 프로젝트 | `build.gradle` | Web MVC, JPA, Validation, Actuator, Lombok |
| 프로필 설정 | `application.yml`, `application-local.yml`, `src/test/resources/application-test.yml` | local은 MySQL(`ddl-auto: update`), test는 H2 |
| JPA Auditing + `BaseTimeEntity` | `global/config`, `global/entity` | `createdAt`, `updatedAt` (`LocalDateTime`) |
| 공통 예외 처리 | `global/exception` | `ErrorCode`, `BusinessException`, `ErrorResponse`, `GlobalExceptionHandler` |
| 헬스 체크 | `GET /actuator/health` | |
| 테스트 | `AutologServerApplicationTests.contextLoads()` | 이것 하나뿐 |
| CI | `.github/workflows/ci.yml` | PR과 main 푸시 시 `./gradlew build` |

## API

없음. 도메인 엔드포인트가 하나도 없습니다.

## 기능 현황

| 기능 (PRODUCT.md) | 상태 | 막는 것 |
|-------------------|------|---------|
| 통화 enum (`CurrencyCode`) | 미구현 | Q2 (지원 통화) |
| 환율 수신, 저장 | 미구현 | Q1 (공급자) |
| 실시간 환율 전달 (클라이언트용) | 미구현 | Q1, Q11 |
| 원장 (계정, 분개, 전기) | 미구현 | 없음 — **가장 먼저 시작할 수 있음** |
| 지갑, 잔액 | 미구현 | 원장 |
| 충전 | 미구현 | 원장, Q3 |
| 지정가 주문 등록, 취소, 조회 | 미구현 | 지갑, Q5, Q6, Q9 |
| 체결 엔진 | 미구현 | 환율, 주문, 원장, Q10 |
| 회원, 인증 | 미구현 | Q4, Spring Security 도입 |

## 추천 구현 순서

```
1. CurrencyCode + money-ledger 스킬의 "결정 기록" 확정 (scale, 반올림)
2. ledger (LedgerAccount, JournalEntry, Posting) + 균형 검증 단위 테스트
3. account (Wallet, 잔액 캐시) + 충전(모의)
4. rate (환율 엔티티 + 모의 공급자)
5. order (주문 등록/취소/조회)
6. 체결 엔진 (동시성 + 멱등)
7. 실시간 스트리밍 API
8. user + 인증 (Spring Security) — 필요한 시점에 앞당길 수 있음
```

인증을 나중으로 미룬 이유는, 원장과 체결이 이 프로젝트의 핵심 위험이기 때문입니다. 인증 없이 개발하는 동안에는 사용자를 요청 파라미터나 고정값으로 대신합니다. 언제 인증을 넣을지는 사용자가 정합니다.

## 알려진 이슈 / 결정 대기

| # | 내용 | 영향 | 제안 |
|---|------|------|------|
| 1 | `BaseTimeEntity`가 `LocalDateTime`을 쓰는데, 컨벤션은 비즈니스 시각을 `Instant`(UTC)로 둠. 게다가 `hibernate.jdbc.time_zone: UTC` 설정 때문에 JVM 기본 시간대(KST)의 LocalDateTime이 변환되어 저장될 수 있음 | 감사 시각이 9시간 어긋나 보일 수 있음 | 첫 엔티티를 만들기 전에 `BaseTimeEntity`를 `Instant`로 바꾸는 것을 권장 |
| 2 | 마이그레이션 도구 없음 (`ddl-auto: update`) | 이름·타입 변경이 조용히 반영되지 않음 | 첫 배포 전에 Flyway를 도입하고 `validate`로 전환 |
| 3 | Spring Security 없음 | 모든 엔드포인트가 공개 상태 | 인증 방식이 결정되면 도입하고, `security-guard` 스킬도 추가 |
| 4 | 테스트가 `contextLoads()` 하나뿐 | 동작 검증 수단이 없음 | 원장부터 순수 단위 테스트 작성 |
| 5 | H2로 테스트 | MySQL의 락, DECIMAL 동작 미검증 | 동시성 기능을 구현할 때 Testcontainers(MySQL) 도입 검토 |
| 6 | 금액 JSON 직렬화 방식 미결정 | JS 클라이언트에서 정밀도 손실 가능 | 첫 금액 API를 만들 때 결정 |
