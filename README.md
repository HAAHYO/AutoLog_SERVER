# AutoLog_SERVER
실시간 환율 스트리밍 및 복식부기 원장 기반 지정가 자동 환전 엔진 서비스

## 기술 스택
- Java 17
- Spring Boot 4.2 (Web MVC, Data JPA, Validation, Actuator)
- MySQL 8
- Gradle

## 로컬 실행
1. MySQL 실행 (DB `autolog`는 없으면 자동 생성됩니다)
2. 환경변수 설정 (기본값: `localhost:3306`, `root`, 비밀번호 없음)

   | 변수 | 기본값 |
   | --- | --- |
   | `DB_HOST` | `localhost` |
   | `DB_PORT` | `3306` |
   | `DB_NAME` | `autolog` |
   | `DB_USERNAME` | `root` |
   | `DB_PASSWORD` | (빈 값) |
   | `SERVER_PORT` | `8080` |

3. 실행
   ```bash
   ./gradlew bootRun
   ```
4. 헬스 체크: `GET http://localhost:8080/actuator/health`

## 테스트
```bash
./gradlew test
```
테스트는 `test` 프로필로 H2 인메모리 DB(MySQL 모드)를 사용하므로 MySQL 없이 실행됩니다.

## 패키지 구조
```
com.autolog.server
└── global
    ├── config      # 공통 설정 (JPA Auditing 등)
    ├── entity      # BaseTimeEntity (createdAt / updatedAt)
    └── exception   # ErrorCode, BusinessException, GlobalExceptionHandler
```
도메인 기능은 `com.autolog.server.domain.<도메인명>` 아래에 추가하는 것을 권장합니다.
