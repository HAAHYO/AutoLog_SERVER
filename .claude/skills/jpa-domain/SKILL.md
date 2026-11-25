---
name: jpa-domain
description: "AutoLog 서버의 JPA 엔티티·리포지토리·서비스 구현 규칙 — 상태 전이 메서드, 파생 쿼리와 JPQL, N+1 방지, 트랜잭션 경계, open-in-view=false 대응, ddl-auto:update 스키마 제약, 락. 엔티티를 추가·변경할 때, 쿼리를 작성할 때, 서비스 메서드를 구현할 때, LazyInitializationException이나 N+1을 디버깅할 때 사용한다."
---

# JPA 도메인과 서비스 구현

먼저 `java-spring-conventions`를 읽으세요. 금액, 원장, 체결과 관련된 작업이라면 `money-ledger`도 함께 읽으세요.

## `ddl-auto: update`에서의 스키마 변경

마이그레이션 도구(Flyway 등)가 없습니다. local 프로필에서는 Hibernate가 시작할 때 스키마를 **추가 방향으로만** 맞춥니다.

| 변경 | 결과 | 대응 |
|------|------|------|
| 새 엔티티, nullable 컬럼 추가 | 안전 | 그대로 진행 |
| 데이터가 있는 테이블에 NOT NULL 컬럼 추가 | 실패하거나 의도하지 않은 기본값이 들어감 | nullable로 먼저 추가하고, 백필 SQL을 실행한 뒤 NOT NULL로 바꿉니다 |
| 필드·컬럼 이름 변경 | 새 컬럼이 생기고 기존 컬럼은 남음 | 수동 `ALTER TABLE ... RENAME COLUMN` |
| 타입, precision/scale 변경 | **무시됨** | 수동 `ALTER TABLE ... MODIFY` |
| 필드 삭제 | 컬럼이 남음 | 수동 `ALTER TABLE ... DROP COLUMN` (선택) |
| 유니크·인덱스 추가 | 기존 데이터가 위반하면 실패 | 중복 데이터를 먼저 확인 |

**이름 변경이나 타입 변경을 "완료"로 보고하지 않습니다.** 노트의 `## 스키마 영향` 섹션에 필요한 SQL을 적고, 사용자에게 알립니다.

운영 배포 전에는 Flyway를 도입하고 `ddl-auto: validate`로 바꾸는 것을 권장합니다. 아직 결정하지 않은 사항이며 `docs/IMPLEMENTATION-STATUS.md`에 기록되어 있습니다.

## 엔티티 동작 메서드

- 상태 전이는 엔티티 메서드로만 합니다. 메서드는 (1) 현재 상태에서 전이가 허용되는지 검사하고, (2) 상태와 그에 짝을 이루는 시각·값을 **함께** 설정합니다.
- 서비스에서 `order.setStatus(...)`처럼 상태를 직접 바꿀 수 있으면 안 됩니다. 애초에 setter가 없어야 합니다.
- 생성 시점의 불변 사실(소유 계좌, 통화쌍, 주문 금액, 생성 시각)은 생성자에서만 설정하고, 이후에 바꾸는 메서드를 만들지 않습니다.
- 현재 시각은 메서드 파라미터(`Instant now`)로 받습니다. 엔티티 안에서 `Instant.now()`를 호출하지 않습니다.

## 리포지토리 쿼리

- 동적 조건 검색은 QueryDSL(도입 후)로 합니다. 구조는 `java-spring-conventions`의 QueryDSL 절을 따릅니다.
- 파생 쿼리 메서드명을 우선 씁니다. 메서드명은 **컨텍스트가 뜰 때** 검증되므로, `compileJava` 성공만으로는 쿼리가 맞는지 알 수 없습니다. 반드시 `./gradlew test`(컨텍스트 부팅)로 확인합니다.
- `@Query`를 쓸 때는 다음을 지킵니다.
  - JPQL에는 테이블·컬럼명이 아니라 **엔티티·필드명**을 씁니다.
  - 쿼리는 텍스트 블록(`"""`)으로 씁니다.
  - `@Param` 이름은 플레이스홀더와 정확히 같아야 합니다.
- **컬렉션 쿼리에는 정렬을 명시합니다.**
- 대량 데이터 쿼리(원장 엔트리, 환율 이력)에는 페이지네이션(`Pageable` 또는 `Slice`, cursor 방식)과 인덱스를 함께 설계합니다. 인덱스는 `@Table(indexes = @Index(...))`로 선언합니다.
- 조건부 UPDATE(`@Modifying @Query("update ... where status = 'PENDING'")`)를 쓸 때는 반환된 영향 행 수를 반드시 확인합니다. 0이면 다른 트랜잭션이 먼저 처리한 것입니다. 또 영속성 컨텍스트가 실제 DB와 달라지므로 `clearAutomatically = true` 옵션을 넣을지 판단합니다.

## N+1

- 목록 응답 DTO의 `from()`이 LAZY 연관(`order.getAccount().getCurrency()`)을 읽으면, 행마다 SELECT가 한 번씩 더 나갑니다.
- 해결은 리포지토리에서 `JOIN FETCH`나 `@EntityGraph`로 합니다. **EAGER로 바꾸지 않습니다.**
- 컬렉션(`@OneToMany`)을 fetch join하면서 페이지네이션을 하면 Hibernate가 메모리에서 페이징합니다(경고 HHH90003004). 이 경우에는 `default_batch_fetch_size: 100`(설정되어 있음)을 활용하거나 쿼리를 둘로 나눕니다.
- local 프로필은 `show-sql: true`라서, 앱을 띄워 엔드포인트를 호출해 보면 SELECT 횟수를 직접 셀 수 있습니다.

## `open-in-view: false`

- 트랜잭션이 끝난 뒤(컨트롤러, Jackson 직렬화 시점)에 LAZY 연관을 건드리면 `LazyInitializationException`이 나고, 이는 500이 됩니다.
- 따라서 **DTO 변환은 서비스의 트랜잭션 안에서 끝냅니다.** 서비스가 DTO를 반환하는 컨벤션은 이 때문입니다.

## 서비스

- 클래스에 `@Transactional(readOnly = true)`를 붙이고, 쓰기 메서드에는 `@Transactional`을 따로 붙입니다. 쓰기 메서드를 추가할 때마다 확인하세요.
- 메서드 순서는 다음과 같습니다.
  1. 가드: 존재 여부, 중복, 멱등키, 잔액
  2. 조회
  3. 엔티티 메서드로 변경
  4. 원장 분개 (`money-ledger` 참고)
  5. 이벤트 발행
  6. DTO 반환
- 변경은 dirty checking에 맡기고, `save()`는 새 엔티티에만 호출합니다.
- **같은 클래스 안에서 다른 메서드를 호출(self-invocation)하면 그 메서드의 `@Transactional`은 적용되지 않습니다.** 트랜잭션을 분리해야 한다면 별도 빈으로 뺍니다.
- 외부 API 호출(환율 공급자)은 트랜잭션 **밖**에서 합니다. 트랜잭션 안에서 네트워크 대기를 하면 DB 커넥션을 붙잡고 있게 됩니다.
- 분산 락이 필요한 쓰기는 **Facade(락) → TxService(@Transactional)** 두 빈으로 나눕니다. 락은 반드시 커밋 뒤에 풉니다(`kafka-redis-pipeline` 참고).
- `@Version` 충돌(`ObjectOptimisticLockingFailureException`)은 재시도 대상입니다. 재시도 위치(Facade, 컨슈머 에러 핸들러)를 정하고, 무한 재시도를 막습니다.

## 도메인 이벤트

- 체결 후 알림처럼 커밋된 뒤에 일어나야 하는 부수 효과는 `ApplicationEventPublisher`와 `@TransactionalEventListener(phase = AFTER_COMMIT)`로 처리합니다.
- AFTER_COMMIT 리스너 안에서 DB에 쓰려면 `@Transactional(propagation = REQUIRES_NEW)`가 필요합니다.

## 새 도메인 추가 순서

1. `domain/<d>/entity/` — 엔티티와 enum
2. `domain/<d>/repository/`
3. `global/exception/ErrorCode`에 `<D>_...` 상수 추가
4. `domain/<d>/service/`
5. `domain/<d>/dto/`, `controller/` (web-engineer 담당)
6. `docs/DOMAIN-MODEL.md`, `docs/IMPLEMENTATION-STATUS.md` 갱신

이 순서는 커밋 순서와 같습니다(`git-commit-convention` 참고).

## 노트 출력

`_workspace/02_domain_notes.md`에 다음을 적습니다.

- 추가·변경한 엔티티 필드와 상태 전이
- 리포지토리 메서드와 쿼리 전략 (fetch join, 락, 인덱스)
- 트랜잭션 경계
- **`## 스키마 영향`** (필수): `ddl-auto: update`가 반영하는 것과 반영하지 못하는 것, 필요한 수동 SQL
