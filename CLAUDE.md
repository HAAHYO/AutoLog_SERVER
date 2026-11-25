# CLAUDE.md

**먼저 [AGENTS.md](AGENTS.md)를 읽으세요.** 프로젝트 개요, 언어 규칙, 컨벤션, 금융 도메인 규칙, 함정, 검증 방법, Git 워크플로가 모두 거기 있습니다. 이 파일에는 Claude Code 전용 설정인 하네스 구성만 적습니다.

## 하네스: AutoLog backend

**목표:** 레이어 사이 경계에서 생기는 결함(DTO와 엔티티의 불일치, 예외와 상태 코드의 불일치, 원장 불변식 위반)을 운영 전에 교차 검증으로 잡아냅니다.

**언제 쓰나:**

| 작업 규모 | 방식 |
|-----------|------|
| 질문, 한두 줄 수정 | 하네스 없이 바로 처리 |
| 엔드포인트 1~2개, 엔티티 필드 추가 같은 중간 규모 | 직접 구현하고, 커밋 전에 `qa-verify` 스킬로 셀프 점검 |
| 새 도메인(애그리거트), 원장·체결 로직, 여러 레이어에 걸친 변경 | `autolog-orchestrator` 스킬 사용 |

후속 요청("QA 다시 돌려줘", "QA에서 나온 거 고쳐줘")도 orchestrator로 처리하고, 기존 `_workspace/`를 이어서 씁니다.

**항상 적용:**
- Java 파일을 쓰거나 리뷰하기 전에 `java-spring-conventions` 스킬을 읽습니다.
- 금액, 환율, 원장, 주문 체결 코드를 건드릴 때는 `money-ledger` 스킬을 함께 읽습니다.
- Kafka, Redis, 분산 락, SSE, 스케줄러 코드를 건드릴 때는 `kafka-redis-pipeline` 스킬을 함께 읽습니다.

### 팀 구성

1인 프로젝트라 팀은 작게 꾸렸습니다. API 계약 설계와 Git 작업은 리더(메인 세션)가 직접 맡습니다.

| 에이전트 | 역할 | 스킬 |
|----------|------|------|
| (리더) | 요구사항 정리, API 계약 작성, 통합, 빌드, 커밋/PR | `autolog-orchestrator`, `api-contract`, `git-commit-convention`, `github-pr-author` |
| `domain-engineer` | 엔티티, 리포지토리, 서비스, ErrorCode, 컨슈머, Redis, 락 Facade | `jpa-domain`, `money-ledger`, `kafka-redis-pipeline` |
| `web-engineer` | 컨트롤러, 요청/응답 DTO, SSE 엔드포인트 | `api-contract` |
| `qa-inspector` | 레이어 간 교차 검증 (체크 1~9) | `qa-verify`, `money-ledger`, `kafka-redis-pipeline` |

세 에이전트 모두 `java-spring-conventions`를 읽습니다. 커밋은 리더만 하며, 통합과 빌드가 끝난 뒤에 합니다.

### 스킬 목록

| 스킬 | 용도 |
|------|------|
| `autolog-orchestrator` | 큰 기능을 에이전트 팀으로 진행 |
| `java-spring-conventions` | Java/Spring 코딩 컨벤션 전체 |
| `api-contract` | 엔드포인트 계약(경로, DTO, 실패 표) 작성 |
| `jpa-domain` | 엔티티, 쿼리, 트랜잭션, 스키마 제약 |
| `money-ledger` | 금액, 환율, 복식부기, 체결 동시성 규칙 |
| `qa-verify` | 레이어 간 교차 검증 체크리스트 |
| `git-commit-convention` | 커밋 단위와 메시지 규칙 |
| `github-pr-author` | 셀프 PR 작성 |

### 참고한 하네스와 다른 점

`VOID-GSM/TaekBaeWatShong_SERVER`의 하네스를 개인 프로젝트에 맞게 줄였습니다.

- **뺀 것:**
  - 리뷰 코멘트 작성·응답 스킬 (리뷰어가 없음)
  - `api-designer`, `security-engineer`, `git-workflow-engineer` 에이전트
  - Discord 알림, SSH 배포
  - `.codex/` 스킬 사본
- **더한 것:**
  - `money-ledger` 스킬 (이 프로젝트의 핵심 위험 영역)
  - H2와 MySQL 차이에 대한 검증 규칙
- **보류한 것:**
  - 보안 스킬과 에이전트: Spring Security를 도입할 때 `security-guard` 스킬과 함께 추가합니다.
  - 배포 파이프라인: 배포할 서버가 정해지면 추가합니다.
  - Codex 지원: Codex도 쓰게 되면 `.codex/skills/`에 사본을 만들고, `diff -r .claude/skills .codex/skills`로 동기화 상태를 확인합니다.

### 변경 이력

| 날짜 | 변경 | 대상 | 이유 |
|------|------|------|------|
| 2026-11-25 | 최초 구축: 에이전트 3개, 스킬 8개, docs, CI, PR 템플릿 | 전체 | TaekBaeWatShong 하네스를 개인 프로젝트·Java·금융 도메인에 맞게 조정 |
