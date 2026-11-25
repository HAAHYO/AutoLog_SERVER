---
name: api-contract
description: "AutoLog 서버 엔드포인트를 구현하기 전에 HTTP 계약을 작성한다 — 경로, 메서드, 요청/응답 DTO 형태와 nullable 여부, 상태 코드, 실패 경로별 ErrorCode, 금융 불변식. 엔드포인트를 추가·변경·리뷰할 때, 컨트롤러나 DTO를 작성할 때, '이 API가 뭘 반환해?'에 답할 때 사용한다. Swagger가 없으므로 이 계약이 유일한 API 명세다."
---

# API 계약 설계

계약을 먼저 쓰고 구현은 그 다음에 합니다. 이 프로젝트에는 Swagger가 없어서, 계약 문서가 엔드포인트 동작(특히 **실패 동작**)을 설명하는 유일한 문서입니다.

먼저 `java-spring-conventions`를 읽으세요.

## 계약 형식

엔드포인트마다 아래 형식으로 `_workspace/01_contract.md`에 작성합니다. 하네스 없이 작업할 때도 PR 본문에 이 형식을 요약해 넣으면 좋습니다.

```markdown
### <METHOD> <path> — <한 줄 목적>

**Controller:** `domain/<d>/controller/<D>Controller.java#<method>`
**Service:** `<D>Service.<method>(<인자 타입>) : <반환 DTO>`

**Request**
- Path: `orderId: Long`
- Query: `status: OrderStatus` (선택)
- Header: `Idempotency-Key: UUID` (돈이 움직이는 POST라면 필수)
- Body: `OrderCreateRequest(targetCurrency: CurrencyCode @NotNull, krwAmount: BigDecimal @NotNull @Positive, ...)`

**Success**
- Status: `201 CREATED`
- Body: `OrderResponse` — 필드 전체와 각 필드의 nullable 여부 (예: executedAt: nullable — PENDING이면 null)

**Failures**
| 조건 | ErrorCode | Status | 메시지 |
|------|-----------|--------|--------|
| 요청 검증 실패 | `INVALID_INPUT` | 400 | (필드별 errors) |
| 주문 없음 | `ORDER_NOT_FOUND` | 404 | "주문을 찾을 수 없습니다." |

**불변식** (금액/원장/체결이 관련된 경우 필수)
- 예: 체결 시 생성되는 분개의 통화별 차변 합계 = 대변 합계
- 예: 같은 멱등키로 두 번 요청하면 두 번째 요청은 첫 번째 결과를 그대로 반환

**Notes**
- 부수 효과, 발행 이벤트, 멱등성, 정렬 기준, 페이지네이션
```

실패 표의 모든 행에는 **실제 ErrorCode 상수 이름**(이미 있는 것이든 새로 정의하는 것이든)을 적습니다. "400 반환"만 적고 ErrorCode가 없으면 계약이 아닙니다. 누군가는 무언가를 던져야 하기 때문입니다.

## 경로와 메서드

| 의도 | 메서드 | 경로 |
|------|--------|------|
| 생성 | `POST` | `/api/v1/orders` |
| 목록 조회 | `GET` | `/api/v1/orders?status=PENDING` |
| 단건 조회 | `GET` | `/api/v1/orders/{orderId}` |
| 부분 수정 | `PATCH` | `/api/v1/orders/{orderId}` |
| 행위(상태 전이) | `POST` | `/api/v1/orders/{orderId}/cancel` |
| 삭제 | `DELETE` | `/api/v1/orders/{orderId}` — 원장 데이터에는 만들지 않습니다 |

- 상태 전이(취소, 체결 등)는 `PATCH`로 status 필드를 바꾸는 방식이 아니라 **행위 경로 + POST**로 표현합니다. 그래야 전이 규칙이 엔티티 메서드 하나와 1:1로 대응합니다.
- 경로 변수 이름은 `{orderId}`처럼 `<리소스>Id`로 짓습니다.

## 상태 코드

| 상태 | 언제 | 어떻게 |
|------|------|--------|
| 200 | 조회, 수정 성공 | `ResponseEntity.ok(...)` |
| 201 | 생성 | `ResponseEntity.status(HttpStatus.CREATED).body(...)` |
| 204 | 본문 없는 성공 | `ResponseEntity.noContent().build()` |
| 400 | 형식이 잘못된 입력 | `@Valid` 실패 → `INVALID_INPUT` (자동) |
| 404 | 대상이 없음 | `<DOMAIN>_NOT_FOUND` |
| 409 | 현재 상태와 충돌 | 예: `ORDER_NOT_PENDING`, `DUPLICATE_IDEMPOTENCY_KEY` |
| 422 | 입력은 맞지만 비즈니스 규칙 위반 | 예: `INSUFFICIENT_BALANCE`, `IDEMPOTENCY_KEY_REUSED`(같은 키에 다른 본문) |
| 500 | 설계하지 않은 실패 | 계약에 **절대 넣지 않습니다** |

**400과 409, 422를 구분하세요.**

- 400: 금액이 음수인 것처럼 요청 자체가 잘못된 경우입니다.
- 409: 이미 체결된 주문을 취소하려는 것처럼 대상의 현재 상태와 충돌하는 경우입니다.
- 422: 잔액이 부족한 것처럼 요청은 올바르지만 지금 수행할 수 없는 경우입니다.

인증과 권한(401/403)은 Spring Security를 도입한 뒤에 이 표에 추가합니다. 지금은 모든 엔드포인트가 공개 상태입니다.

## DTO 설계 규칙

1. **컬렉션은 감싸서 반환합니다.** `OrderListResponse(List<OrderResponse> orders)`
2. **nullable 여부는 엔티티의 상태 머신을 따릅니다.** 필드마다 "어떤 상태에서 이 값이 비어 있을 수 있는가?"를 확인하고, 비어 있을 수 있는 상태가 하나라도 있으면 nullable로 계약에 적습니다.
3. **요청 제약은 계약에 모두 적습니다.** 단일 필드 제약은 Bean Validation 애노테이션으로, 필드 간 제약이나 DB 조회가 필요한 제약은 서비스 가드와 실패 표의 행으로 적습니다.
4. **금액과 환율 필드는 `BigDecimal`입니다.** 계약에 scale(소수 자릿수)도 적습니다. JSON 직렬화 형식(숫자/문자열)은 `java-spring-conventions`의 결정을 따릅니다.
5. **시각 필드는 `Instant`(ISO-8601 UTC)입니다.**
6. 엔티티를 그대로 투영하지 않는 형태가 필요하면 응답 record 안에 중첩 record로 정의합니다.

## 멱등성 키

- 돈이 움직이는 요청(즉시 환전, 주문 등록, 충전)은 `Idempotency-Key` 헤더를 **필수**로 받습니다. 계약의 Request에 적습니다.
- 실패 표에는 다음 세 행을 항상 넣습니다.

  | 조건 | ErrorCode | 상태 |
  |------|-----------|------|
  | 헤더 누락 | `IDEMPOTENCY_KEY_MISSING` | 400 |
  | 같은 키로 처리 중 | `IDEMPOTENCY_KEY_IN_PROGRESS` | 409 |
  | 같은 키에 다른 본문 | `IDEMPOTENCY_KEY_REUSED` | 422 |

- 같은 키와 같은 본문으로 다시 요청하면 **처음 응답(상태 코드와 본문)을 그대로** 반환합니다. Notes에 이 사실을 적습니다.

## 페이지네이션

목록이 계속 늘어나는 리소스(주문 내역, 원장 엔트리, 환율 이력)는 처음부터 페이지네이션을 설계합니다. 형식은 첫 목록 API를 만들 때 정합니다(offset 또는 cursor). 원장이나 환율 이력처럼 데이터가 많이 쌓이는 경우에는 cursor(`id` 기준)를 권장합니다.

## 제품 결정이 필요한 실패 동작

예를 들어 "지정가에 도달했지만 잔액이 부족한 주문은 거절할까, 대기시킬까?" 같은 문제는 계약에 **두 가지 안을 모두** 적고 사용자에게 물어봅니다. 혼자 고르지 않습니다. 답을 받으면 `docs/PRODUCT.md`의 미결정 질문 목록도 갱신합니다.

## 인계

이 계약을 받는 쪽은 다음과 같습니다.

- `web-engineer`: 컨트롤러와 DTO를 만듭니다.
- `domain-engineer`: 서비스 시그니처, 가드, ErrorCode를 만듭니다.
- `qa-inspector`: 실패 표와 불변식을 체크리스트로 씁니다.

따라서 실패 표의 행이 모호하면 검증할 수 없는 엔드포인트가 만들어집니다.
