---
name: java-spring-conventions
description: "AutoLog 서버의 Java/Spring Boot 코딩 컨벤션 전체 레퍼런스 — 패키지 구조, 엔티티·DTO·컨트롤러·서비스·리포지토리·예외 작성 규칙, Lombok 사용 범위, 들여쓰기, 네이밍. 이 저장소에서 Java 파일을 작성·수정·리뷰하기 전에 항상 읽는다. 오케스트레이터 실행 여부와 무관하게 적용된다."
---

# AutoLog Java/Spring 컨벤션

## 스택 사실

- Java 17이므로 `record`, `switch` 식, `var`, 텍스트 블록을 쓸 수 있습니다. 패턴 매칭 `switch`(21)는 쓸 수 없습니다.
- Spring Boot 4.2.0, Jakarta EE를 씁니다. import는 `javax.*`가 아니라 `jakarta.*`입니다.
- Lombok을 씁니다. 허용 범위는 아래에 따로 정리했습니다.
- `spring.jpa.open-in-view: false`입니다.
- `GlobalExceptionHandler`(`@RestControllerAdvice`)가 있으며, 처리하지 못한 예외는 모두 500이 됩니다.
- **도입 예정이지만 아직 없는 것**: QueryDSL, Redis/Redisson, Spring Kafka, Testcontainers. 의존성은 사용자에게 제안하고 승인받은 뒤 추가합니다. Boot 4.2 유지가 결정되었으므로(PRODUCT Q1), Boot 4 / Hibernate 7과 호환되는 버전을 문서로 확인한 뒤 고릅니다.

## 포맷

- 들여쓰기는 **탭**입니다. YAML은 스페이스 2칸입니다. 자세한 설정은 `.editorconfig`에 있습니다.
- 와일드카드 import(`import java.util.*;`)는 쓰지 않습니다.
- 파일 끝에는 개행을 하나 넣습니다.

## 패키지 구조

```
com.autolog.server
├── domain/<도메인>/{controller, dto/request, dto/response, entity, repository, service}
└── global/{config, entity, exception}
```

- 도메인 패키지명은 단수 소문자를 씁니다: `rate`, `account`, `ledger`, `order`, `idempotency`, (예정) `user`
- 이미 만들어 둔 뼈대 패키지에는 `package-info.java`가 있습니다. 도메인 최상위 파일에는 담당 범위를 적어 두었으니, 새 클래스는 그 범위에 맞는 도메인에 둡니다. 실제 클래스가 들어온 뒤에도 `package-info.java`는 지우지 않습니다.
- 쓰이지 않을 계층 패키지는 만들지 않습니다(예: `idempotency`에는 HTTP 진입점이 인터셉터라서 `controller`가 없습니다).
- 도메인 안에 추가로 둘 수 있는 하위 패키지:

  | 패키지 | 내용 |
  |--------|------|
  | `client/` | 외부 API 클라이언트 (환율 공급자) |
  | `messaging/` | Kafka 프로듀서, `@KafkaListener` 어댑터, 이벤트 record |
  | `cache/` | Redis 접근 (최신 시세, ZSet 매칭 인덱스) |
  | `facade/` | 분산 락을 잡고 트랜잭션 서비스를 호출하는 Facade (`kafka-redis-pipeline` 참고) |
  | `scheduler/` | `@Scheduled` 작업 (Collector, Mock Market) |

- `@KafkaListener`, SSE 엔드포인트, 스케줄러는 **컨트롤러처럼 얇게** 두고 서비스를 호출합니다.
- Kafka 토픽명, Redis 키 접두사, 락 키는 `global/config`의 상수나 `@ConfigurationProperties` 한곳에서 관리합니다.
- 여러 도메인이 함께 쓰는 유틸은 `global/` 아래에 둡니다. 단, "혹시 쓸지도 몰라서" 미리 만들지는 않습니다.

## 네이밍

| 대상 | 규칙 | 예 |
|------|------|-----|
| 엔티티 | 단수 명사 | `FxOrder`, `LedgerEntry`, `Account` |
| 리포지토리 | `<Entity>Repository` | `FxOrderRepository` |
| 서비스 | `<Domain>Service` | `FxOrderService` |
| 요청 DTO | `<Action>Request` | `OrderCreateRequest` |
| 응답 DTO | `<Action/Entity>Response` | `OrderResponse`, `OrderListResponse` |
| ErrorCode | `<DOMAIN>_<상황>` | `ORDER_NOT_FOUND`, `LEDGER_UNBALANCED` |

테이블명은 `@Table(name = "...")`으로 명시하고, 복수형 snake_case로 짓습니다(`accounts`, `fx_orders`, `ledger_entries`, `idempotency_keys` — `docs/DOMAIN-MODEL.md`). `order`는 SQL 예약어이므로 주문 엔티티는 `FxOrder`입니다.

## 엔티티

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FxOrder extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OrderStatus status;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(precision = 19, scale = 8)
	private BigDecimal executedRate;

	private Instant executedAt;

	@Builder
	private FxOrder(Account account, BigDecimal amount) { ... }

	public void complete(BigDecimal executedRate, Instant now) {
		if (status != OrderStatus.PENDING) {
			throw new BusinessException(ErrorCode.ORDER_NOT_PENDING);
		}
		this.status = OrderStatus.COMPLETED;
		this.executedRate = executedRate;
		this.executedAt = now;
	}
}
```

- `@Setter`, `@Data`, `@ToString`(연관관계 순환), `@EqualsAndHashCode`는 엔티티에 붙이지 않습니다.
- `@Builder`는 클래스가 아니라 **생성자**에 붙입니다. 그래야 id나 status 같은 필드가 빌더에 노출되지 않습니다.
- **상태 전이는 이름 있는 메서드로만** 합니다. 상태와 그에 짝을 이루는 시각(`executedAt` 등)을 함께 설정하고, 전이가 허용되는 상태인지 메서드 안에서 검사합니다.
- `@ManyToOne`과 `@OneToOne`은 항상 `fetch = LAZY`로 둡니다. JPA 기본값이 EAGER이기 때문입니다.
- enum은 `EnumType.STRING`과 `length`를 명시합니다.
- 금액과 환율 컬럼에는 `precision`과 `scale`을 명시합니다. `money-ledger` 스킬을 참고하세요.
- 생성·수정 시각이 필요하면 `BaseTimeEntity`를 상속합니다.
- 파생값은 컬럼으로 저장하지 않고 메서드로 계산합니다. 원장의 잔액 캐시는 예외이며, `money-ledger`를 참고하세요.

## DTO

```java
public record OrderCreateRequest(
		@NotNull CurrencyCode fromCurrency,
		@NotNull CurrencyCode toCurrency,
		@NotNull @Positive BigDecimal amount,
		@NotNull @Positive BigDecimal limitRate
) {
}

public record OrderResponse(Long id, OrderStatus status, BigDecimal amount, Instant executedAt) {

	public static OrderResponse from(FxOrder order) {
		return new OrderResponse(order.getId(), order.getStatus(), order.getAmount(), order.getExecutedAt());
	}
}

public record OrderListResponse(List<OrderResponse> orders) {
}
```

- 요청과 응답 모두 `record`로 만듭니다.
- 요청 검증은 Bean Validation(`@NotNull`, `@Positive`, `@Size` 등)으로 하고, 컨트롤러에서 `@Valid`로 실행합니다. 여러 필드에 걸친 규칙이나 DB 조회가 필요한 규칙은 서비스에서 검사합니다.
- **엔티티는 컨트롤러 경계를 넘지 않습니다.** 엔티티를 DTO로 바꾸는 매핑은 응답 DTO의 `static from()`에서만 합니다.
- **컬렉션 응답은 감싸서 반환합니다.** `OrderListResponse(List<OrderResponse> orders)`처럼 만들고, 배열을 그대로 반환하지 않습니다. 이렇게 해야 나중에 `totalCount`나 페이지 정보를 추가해도 클라이언트가 깨지지 않습니다.
- 응답의 nullable 여부는 실제 엔티티 상태를 따릅니다. 예를 들어 PENDING 주문의 `executedAt`은 null입니다.
- JSON 필드명은 record 컴포넌트명(camelCase)을 그대로 씁니다. 네이밍 전략은 설정하지 않았습니다.
- **금액·환율(`BigDecimal`)은 JSON에서 문자열로 주고받습니다**(2026-11-25 결정, #13). 예: `"amount": "136500"`, `"appliedRate": "1365.00000000"`. JS 클라이언트가 숫자를 double로 읽으면서 정밀도가 깨지는 것을 막기 위해서입니다.
  - 응답: 전역 Jackson 설정으로 모든 `BigDecimal`을 문자열로 직렬화합니다. 필드마다 `@JsonFormat`을 붙이지 않습니다.
  - 요청: 문자열 `"100.50"`을 받습니다. 숫자 `100.50`도 Jackson이 받아들이지만, 계약서와 클라이언트 예시는 항상 문자열로 적습니다.
  - 지수 표기(`1E+3`)가 나가지 않도록 `toPlainString()` 형태로 내보냅니다.

## 컨트롤러

```java
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class FxOrderController {

	private final FxOrderService fxOrderService;

	@PostMapping
	public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(fxOrderService.createOrder(request));
	}

	@GetMapping("/{orderId}")
	public ResponseEntity<OrderResponse> getOrder(@PathVariable Long orderId) {
		return ResponseEntity.ok(fxOrderService.getOrder(orderId));
	}
}
```

- 컨트롤러는 얇게 유지합니다. 서비스 메서드 하나를 호출하고 `ResponseEntity`로 감싸는 것이 전부입니다. 비즈니스 로직, 리포지토리 접근, 엔티티 처리는 하지 않습니다.
- 의존성은 생성자 주입(`@RequiredArgsConstructor` + `private final`)으로 받습니다. 필드에 `@Autowired`를 쓰지 않습니다.
- 생성은 `201`(`ResponseEntity.status(HttpStatus.CREATED)`), 나머지는 `ResponseEntity.ok(...)`로 응답합니다. 본문이 없을 때는 `204`(`ResponseEntity.noContent().build()`)를 씁니다.
- `ResponseEntity<?>`, `ResponseEntity<Object>`, `Map` 반환은 쓰지 않습니다.
- 경로는 `/api/v1/<복수형 리소스>`입니다. 리소스를 다루는 행위는 하위 경로로 표현합니다(`POST /api/v1/orders/{orderId}/cancel`).
- HTTP 메서드는 용도에 따라 고릅니다: `POST`는 생성 또는 행위, `GET`은 조회, `PATCH`는 부분 수정, `DELETE`는 삭제입니다. 단, **원장 데이터에는 DELETE를 만들지 않습니다.**

## 서비스

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FxOrderService {

	@Transactional
	public OrderResponse createOrder(OrderCreateRequest request) { ... }

	public OrderResponse getOrder(Long orderId) { ... }
}
```

- `@Transactional`은 `org.springframework.transaction.annotation.Transactional`을 import합니다. `jakarta.transaction.Transactional`이 아닙니다.
- **쓰기 메서드에는 반드시 `@Transactional`을 붙입니다.** 빠뜨리면 readOnly로 실행되어 변경이 조용히 사라집니다.
- 메서드 안의 순서는 정해져 있습니다: 가드(검증) → 조회 → 엔티티 메서드로 변경 → (이벤트 발행) → DTO 반환
- 변경은 dirty checking에 맡기고, `save()`는 새로 만든 엔티티에만 호출합니다.
- 서비스는 DTO를 반환합니다. 다른 서비스가 엔티티를 필요로 할 때는 패키지 내부용 메서드(`getOrderEntity` 등)를 따로 둘 수 있습니다.
- `Instant.now()`를 직접 호출하지 말고 `Clock` 빈을 주입받아 씁니다. 체결 시각이나 환율 만료 같은 로직을 테스트할 수 있게 하기 위해서입니다. `Clock` 빈은 처음 필요해지는 시점에 `global/config`에 추가합니다.

## 예외

```java
throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
throw new BusinessException(ErrorCode.INVALID_INPUT, "지정가는 0보다 커야 합니다.");
```

- 의도한 실패는 모두 `BusinessException(ErrorCode)`로 던집니다. HTTP 상태는 `ErrorCode`의 `status`가 결정합니다.
- 새 에러는 `ErrorCode` enum에 추가합니다. 코드는 도메인 접두사와 번호로 짓습니다.

  | 접두사 | 도메인 |
  |--------|--------|
  | C | 공통 |
  | U | user |
  | A | account |
  | L | ledger |
  | O | order |
  | R | rate |
  | I | idempotency |

  예: `O001`
- 메시지는 사용자에게 노출되므로 한국어로 씁니다. 내부 구현(SQL, 클래스명)이 드러나지 않게 합니다.
- `RuntimeException`, `IllegalArgumentException`, `IllegalStateException`을 직접 던지지 않습니다. catch-all 핸들러에 걸려 500이 됩니다.
- 상태 코드는 다음 기준으로 고릅니다.

  | 상태 | 쓰는 경우 |
  |------|-----------|
  | 400 | 입력이 잘못됨 |
  | 404 | 대상이 없음 |
  | 409 | 현재 상태와 충돌함 (이미 체결된 주문 취소, 중복 멱등키) |
  | 422 | 입력은 올바르지만 비즈니스 규칙에 맞지 않음 (잔액 부족 등) |

  422는 `HttpStatus.UNPROCESSABLE_CONTENT`를 씁니다(Spring 7에서 `UNPROCESSABLE_ENTITY`의 새 이름).
- 동시 수정 충돌(`@Version`, 락 대기 실패·데드락)은 직접 잡지 않아도 `GlobalExceptionHandler`가 `CONCURRENT_UPDATE`(C004, 409)로 바꿉니다. 재시도할 수 있는 실패라서 500으로 두지 않습니다.
- 5xx `ErrorCode`(예: `LEDGER_UNBALANCED`)로 던진 예외는 상세 메시지를 로그로만 남기고, 응답에는 기본 메시지만 내보냅니다.

## 리포지토리

- 파생 쿼리 메서드명을 우선합니다. 메서드명이 읽기 어려울 정도로 길어지면 `@Query`(JPQL, 텍스트 블록, `@Param`)를 씁니다.
- **컬렉션을 반환하는 쿼리에는 정렬을 명시합니다**(`OrderBy...` 또는 `ORDER BY`). 정렬이 없으면 MySQL이 돌려주는 순서는 보장되지 않습니다.
- 목록 응답이 LAZY 연관을 읽는다면 `JOIN FETCH`나 `@EntityGraph`를 씁니다.
- 동시성 제어가 필요하면 `@Lock(LockModeType.PESSIMISTIC_WRITE)`나 `@Version`을 씁니다. `money-ledger` 스킬을 참고하세요.

### QueryDSL (도입 후)

- 동적 조건 검색(주문 내역 필터, 원장 기간 조회)은 QueryDSL로, 단순 조회는 파생 쿼리로 작성합니다.
- 구조는 `<Entity>RepositoryCustom` 인터페이스 + `<Entity>RepositoryImpl`(`JPAQueryFactory` 주입)이고, 본 리포지토리가 Custom을 상속합니다.
- `JPAQueryFactory` 빈은 `global/config/QuerydslConfig`에 둡니다.
- Q클래스(`build/generated`)는 커밋하지 않습니다.
- 목록 쿼리는 cursor 페이지네이션(`id < :cursor ORDER BY id DESC LIMIT n`)을 기본으로 합니다.

## 테스트

- 테스트 클래스에는 `@ActiveProfiles("test")`를 붙입니다. H2를 사용합니다.
- 도메인 로직(엔티티 상태 전이, 금액 계산, 분개 균형)은 **스프링 없이 순수 단위 테스트**로 작성합니다. 빠르고 실패 원인이 분명하기 때문입니다.
- 리포지토리 쿼리는 `@DataJpaTest`, 컨트롤러는 `@WebMvcTest`로 테스트합니다.
- 테스트 메서드명은 한국어로 써도 됩니다: `void 잔액이_부족하면_주문이_거절된다()`.

## 빌드와 검증

```bash
./gradlew compileJava
./gradlew test
./gradlew build
```

실행하지 않은 검증은 통과로 보고하지 않습니다. H2에서만 확인한 내용은 MySQL에서 미검증이라고 밝힙니다.
