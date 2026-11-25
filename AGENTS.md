# AGENTS.md

이 저장소에서 일하는 모든 AI 코딩 에이전트(Claude Code, Codex 등)를 위한 공통 규칙입니다.
도구에 상관없이 이 파일 하나로 평소 작업에 필요한 내용을 다 알 수 있게 썼습니다. Claude Code는 여기에 더해 `CLAUDE.md`(에이전트 하네스 설정)를 읽습니다.

## 언어 규칙

- **사람이 읽는 출력은 모두 한국어**로 씁니다. 사용자 응답, 진행 상황 업데이트, 커밋 메시지(영어 타입 접두사 뒤), PR 제목·본문, 사용자 노출용 예외 메시지가 여기에 해당합니다.
- 코드 식별자, 패키지명, 로그 메시지는 영어로 씁니다.

## 프로젝트

**AutoLog** — 실시간 환율 스트리밍과 복식부기 원장을 기반으로 하는 지정가 자동 환전 엔진의 **백엔드**입니다.

- **1인 개인 프로젝트**입니다. 리뷰어도, 팀 합의 절차도 없습니다. 결정권자는 사용자 한 명이므로, 사용자의 결정이 필요한 사항은 짐작으로 정하지 말고 물어봅니다.
- 아직 초기 단계입니다. 공통 뼈대만 있고 도메인 기능은 하나도 없습니다. 기능을 계획하기 전에 `docs/IMPLEMENTATION-STATUS.md`를 읽으세요.

## 스택

| 항목 | 값 |
|------|-----|
| Java | 17 (Gradle toolchain) |
| Spring Boot | 4.2.0 (Web MVC, Data JPA, Validation, Actuator) |
| DB | MySQL 8 (local) / H2 MySQL 모드 (test) |
| 빌드 | Gradle Groovy DSL, `./gradlew` |
| 기타 | Lombok, DevTools |

**아직 클래스패스에 없는 것:** Spring Security, JWT, Swagger/springdoc, Flyway/Liquibase, Testcontainers, 정적 분석 도구(Checkstyle/Spotless). 필요하면 의존성 추가를 **제안**하세요. 없는 라이브러리를 전제로 코드를 쓰면 안 됩니다.

## 검증

```bash
./gradlew compileJava   # 빠른 타입 체크
./gradlew test          # test 프로필(H2)로 컨텍스트 부팅 + 테스트
./gradlew build         # 컴파일 + 테스트 + jar
```

- 테스트는 H2(MySQL 모드)로 돌기 때문에 MySQL 없이도 통과합니다. 대신 **H2 통과가 MySQL 동작을 보장하지는 않습니다.** 락(`SELECT ... FOR UPDATE`), DECIMAL 정밀도, 인덱스/유니크 제약, 시간대 처리는 H2와 MySQL에서 다르게 동작할 수 있습니다. 이런 부분을 건드렸다면 "MySQL에서는 확인하지 못함"이라고 밝히세요.
- `compileJava` 성공을 "테스트 통과"로 보고하지 마세요.
- 실행하지 않은 검증은 "통과"가 아니라 **"확인하지 못함"**으로 보고합니다.

## 패키지 구조

```
com.autolog.server
├── domain/<도메인>/
│   ├── controller/     <Domain>Controller.java
│   ├── dto/request/    <Action>Request.java   (record)
│   ├── dto/response/   <Action>Response.java  (record)
│   ├── entity/         <Domain>.java, enum
│   ├── repository/     <Domain>Repository.java
│   └── service/        <Domain>Service.java
└── global/
    ├── config/         공통 설정 (JpaAuditingConfig 등)
    ├── entity/         BaseTimeEntity
    └── exception/      ErrorCode, BusinessException, ErrorResponse, GlobalExceptionHandler
```

## 코딩 컨벤션 (요약)

전체 규칙은 `.claude/skills/java-spring-conventions/SKILL.md`에 있습니다. 핵심만 추리면 다음과 같습니다.

- **들여쓰기는 탭**입니다(Spring Initializr 스타일). YAML만 스페이스 2칸을 씁니다.
- **엔티티**
  - `@Getter`와 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`를 붙이고, `@Setter`와 `@Data`는 쓰지 않습니다.
  - 상태 변경은 이름 있는 메서드(`execute()`, `cancel()`)로만 합니다.
  - `@ManyToOne`은 항상 `LAZY`, enum은 `EnumType.STRING`으로 매핑합니다.
- **DTO**
  - `record`로 만듭니다. 응답 DTO에는 `static from(entity)` 팩토리를 둡니다.
  - 엔티티는 컨트롤러 밖으로 나가지 않습니다.
  - 컬렉션 응답은 배열 그대로 내보내지 않고 감싸서 반환합니다.
- **컨트롤러**: 얇게 유지합니다. 서비스 메서드 하나를 호출해 `ResponseEntity`로 감싸는 것만 하고, `@Valid`로 요청을 검증합니다.
- **서비스**
  - 클래스에 `@Transactional(readOnly = true)`를 붙이고, 쓰기 메서드에는 `@Transactional`을 따로 붙입니다.
  - 반환 타입은 DTO입니다.
- **예외**
  - 도메인 예외는 `BusinessException(ErrorCode.XXX)` 하나로 통일합니다. 새 에러는 `ErrorCode` enum에 추가합니다.
  - `RuntimeException`, `IllegalArgumentException` 같은 일반 예외를 그대로 던지면 `GlobalExceptionHandler`의 catch-all에 걸려 **500**이 됩니다.
- **API 경로**: `/api/v1/<복수형 리소스>` 형식입니다(예: `/api/v1/orders`, `/api/v1/orders/{orderId}`).

## 금융 도메인 규칙 (가장 중요)

이 서비스는 돈을 다룹니다. 아래 규칙을 어기면 빌드는 통과해도 잔액이 틀어집니다. 자세한 내용은 `.claude/skills/money-ledger/SKILL.md`에 있습니다.

1. **금액과 환율은 `BigDecimal`만 씁니다.** `double`/`float`은 금지입니다. `new BigDecimal(0.1)` 대신 `new BigDecimal("0.1")` 또는 `BigDecimal.valueOf`를 씁니다.
2. **BigDecimal 비교는 `compareTo`로 합니다.** `equals`는 scale까지 비교하기 때문에 `1.0`과 `1.00`을 다르다고 판단합니다.
3. **나눗셈과 반올림에는 항상 scale과 `RoundingMode`를 명시합니다.** 이를 빼면 `ArithmeticException`이 나거나 결과가 조용히 달라집니다.
4. **DB 컬럼은 `@Column(precision = , scale = )`로 DECIMAL을 지정합니다.** 기본 매핑에 맡기지 않습니다.
5. **원장은 추가만 할 수 있습니다(append-only).** 원장 엔트리를 UPDATE/DELETE하지 않고, 정정할 때는 역분개 엔트리를 추가합니다.
6. **한 거래(journal) 안에서 통화별 차변 합계와 대변 합계가 같아야 합니다.** 이 불변식은 저장 직전에 코드에서 검증합니다.
7. **지정가 주문은 정확히 한 번만 체결되어야 합니다.** 동시성 제어(`@Version` 또는 조건부 UPDATE)와 멱등키 없이 체결 로직을 작성하지 않습니다.
8. **시간은 UTC 기준으로 다룹니다.** 비즈니스 시각(환율 수신, 체결, 분개)은 `Instant`로 저장합니다.

## 코드베이스의 함정

아래 문제들은 빌드로는 드러나지 않으니, 변경할 때마다 직접 확인하세요.

1. **`open-in-view: false`** — 트랜잭션 밖(컨트롤러, DTO 변환 시점)에서 LAZY 연관을 건드리면 `LazyInitializationException`이 납니다. DTO 변환은 서비스 트랜잭션 안에서 끝내세요.
2. **쓰기 메서드에 `@Transactional` 누락** — 클래스 기본값이 `readOnly = true`라서, 누락된 메서드의 변경은 flush되지 않고 **조용히 사라집니다.**
3. **catch-all 500** — `GlobalExceptionHandler`가 `Exception`을 모두 500으로 바꿉니다. 의도한 실패라면 반드시 `BusinessException`으로 던지세요.
4. **`ddl-auto: update`는 추가만 반영합니다** — 마이그레이션 도구가 없습니다.

   | 변경 | 결과 |
   |------|------|
   | nullable 컬럼 추가, 새 엔티티 추가 | 안전 |
   | 데이터가 있는 테이블에 NOT NULL 컬럼 추가 | 실패하거나 기본값이 들어감 |
   | 필드 이름 변경 | 새 컬럼이 생기고 기존 컬럼은 버려짐 (데이터가 사라진 것처럼 보임) |
   | 타입·precision 변경, 필드 삭제 | 무시됨 |

   이름 변경이나 타입 변경은 "완료"로 보고하지 말고, 필요한 수동 SQL을 정리해 사용자에게 알리세요.
5. **목록 조회의 N+1** — 응답 DTO가 LAZY 연관을 읽는다면 리포지토리에서 `JOIN FETCH`나 `@EntityGraph`로 해결합니다. EAGER로 바꿔서 해결하면 안 됩니다.
6. **H2와 MySQL의 차이** — 위 "검증" 절을 참고하세요.

## Git 워크플로 (개인 프로젝트용)

전체 규칙은 `.claude/skills/git-commit-convention/SKILL.md`와 `.claude/skills/github-pr-author/SKILL.md`에 있습니다.

### 브랜치

- `main`은 항상 빌드가 되는 상태를 유지합니다.
- 작업은 `<type>/<kebab-주제>` 브랜치에서 합니다(예: `feat/exchange-rate-stream`, `fix/ledger-rounding`).
- 예외로, **문서만 바꾸는 작은 수정**(`docs:` 커밋 1~2개)은 사용자가 원할 때 `main`에 직접 커밋할 수 있습니다.

### 커밋

형식은 `<type>: <한국어 요약>` 한 줄입니다. scope는 쓰지 않습니다. 본문은 "왜"가 코드만으로 드러나지 않을 때만 짧게 씁니다.

| Type | 의미 |
|------|------|
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 코드 개선 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서 수정 |
| `chore` | 빌드 설정, 의존성, CI, 기타 잡일 |
| `init` | 프로젝트 초기화 |

- **커밋 하나에는 관심사 하나만** 담습니다. 요약에 "및"이 들어가야 한다면 커밋을 나눕니다.
- 각 커밋이 단독으로 컴파일되도록 **의존 순서대로** 커밋합니다: entity → repository → ErrorCode → service → DTO → controller → test/docs/chore
- `git add -A`와 `git add .`는 금지입니다. 경로를 지정해서 스테이징하고, 커밋 전에 `git diff --staged`를 확인합니다.
- 푸시된 커밋에 amend나 force-push를 하지 않습니다. `--no-verify`도 쓰지 않습니다.

### PR

- 기능 브랜치는 PR로 `main`에 병합합니다. CI 기록을 남기고 셀프 리뷰를 하는 것이 목적입니다.
- PR은 `.github/PULL_REQUEST_TEMPLATE.md` 양식으로 작성하고, 체크리스트는 **실제로 확인한 항목만** 체크합니다.
- 병합은 **Merge commit**으로 합니다. 잘게 나눈 커밋 기록을 남기기 위해서입니다. squash는 쓰지 않습니다.

### 확인이 필요한 작업

| 작업 | 규칙 |
|------|------|
| 커밋 | 사용자가 요청했을 때만 ("이거 수정해줘"에 커밋 요청까지 포함된 것은 아님) |
| `git push`, `gh pr create`, `gh pr merge` | 해당 턴에 사용자가 직접 요청하지 않았다면 먼저 확인 |
| force push, 푸시된 커밋 amend | 하지 않음 |

## 참고 문서

| 파일 | 내용 |
|------|------|
| `docs/PRODUCT.md` | 제품 개요, 핵심 시나리오, 미결정 제품 질문 |
| `docs/DOMAIN-MODEL.md` | 도메인(애그리거트) 초안, 주문 상태 머신, 원장 불변식 |
| `docs/IMPLEMENTATION-STATUS.md` | 스펙과 코드의 차이, 구현 순서, 알려진 이슈 |
| `.claude/skills/*/SKILL.md` | 상세 규칙 (일반 마크다운이라 어떤 도구에서도 읽을 수 있음) |

## 작업 원칙

- 구현이 `docs/PRODUCT.md`와 맞지 않으면 조용히 맞추지 말고 보고합니다.
- 확인하지 못한 것은 확인하지 못했다고 말합니다.
- 인증 방식, 스키마 이름 변경, 새 의존성처럼 사용자가 정해야 하는 사항은 혼자 정하지 말고 물어봅니다.
