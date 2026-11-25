---
name: github-pr-author
description: "AutoLog 저장소(HAAHYO/AutoLog_SERVER)에 .github/PULL_REQUEST_TEMPLATE.md 양식으로 한국어 셀프 PR을 만들고(기능 PR은 base=develop, 릴리스 PR은 develop→main), CI 확인 후 병합한다. 'PR 만들어줘', 'PR 올려줘', '머지해줘' 요청이나 기능 브랜치 작업이 끝났을 때 사용한다. 체크리스트를 근거 없이 체크하지 않는 규칙, 푸시·생성·병합 전 확인 게이트, 셀프 리뷰 절차를 다룬다."
---

# 셀프 PR 작성과 병합

개인 프로젝트라 리뷰어가 없습니다. PR을 쓰는 목적은 다음 세 가지입니다.

1. **CI 실행**: `develop`에 들어가기 전에 빌드와 테스트를 돌립니다.
2. **변경 기록**: 나중에 "이 기능은 왜 이렇게 만들었나"를 PR 본문에서 찾을 수 있습니다.
3. **셀프 리뷰**: 병합 전에 디프 전체를 한 번 훑어봅니다.

그러니 PR 본문은 **미래의 나에게 쓰는 메모**입니다. 무엇을 확인했고 무엇을 확인하지 못했는지 정직하게 씁니다.

## 확인 게이트

| 작업 | 규칙 |
|------|------|
| `git push` | 해당 턴에 사용자가 푸시나 PR을 요청하지 않았다면 먼저 확인 |
| `gh pr create` | 제목과 본문을 보여주고 확인받은 뒤 생성 (해당 턴에 "PR 올려줘"라고 했다면 바로 생성 가능) |
| `gh pr merge` | **명시적으로 요청했을 때만** |
| force push | 하지 않음 |

## PR 종류

| 종류 | head → base | 언제 |
|------|-------------|------|
| 기능 PR (기본) | `<type>/<주제>` → **`develop`** | 큰 기능 묶음 하나가 끝났을 때 (작은 작업 단위로 PR을 올리지 않음) |
| 릴리스 PR | `develop` → `main` | 마일스톤(예: MVP 단계 하나)이 끝나 안정적일 때, **사용자가 요청할 때만** |

`gh pr create`에 `--base`를 빠뜨리지 않습니다. 기본 브랜치가 `develop`이라 생략해도 develop으로 가긴 하지만, 의도를 명시하기 위해 항상 적습니다.

## 사전 점검

```bash
git branch --show-current                          # develop, main이면 안 됨
git fetch origin && git log --oneline HEAD..origin/develop   # develop이 앞서 있으면 먼저 반영
git status --short                                 # 깨끗해야 함
git log --oneline develop..HEAD                    # PR에 들어갈 커밋
gh pr list --head "$(git branch --show-current)"   # 이미 열린 PR이 있는지
./gradlew build                                    # 로컬 빌드
```

- 이미 PR이 열려 있다면 새로 만들지 말고 푸시한 뒤 본문을 갱신합니다.
- 푸시는 `git push -u origin "$(git branch --show-current)"`로 합니다.

## 셀프 리뷰 (PR 생성 전)

1. `git diff develop...HEAD`를 처음부터 끝까지 읽습니다.
2. 금액, 원장, 주문, 체결 코드가 바뀌었다면 `qa-verify`의 체크 8(금융 불변식)을 반드시 돌립니다.
3. 그 밖의 변경은 `qa-verify`에서 관련 있는 체크만 돌립니다.
4. 발견된 문제는 PR을 만들기 전에 고치거나, 본문의 "기타 참고사항"에 적습니다.

더 깊게 보고 싶다면 `/code-review` 명령을 쓸 수 있습니다.

## 템플릿 채우기

`.github/PULL_REQUEST_TEMPLATE.md`의 모든 섹션을 채웁니다. 비어 있는 플레이스홀더를 남기지 않습니다.

### 📌 개요
무엇이 왜 바뀌었는지 1~3줄로 씁니다. 파일 목록이 아니라 결과를 씁니다.

### 🔗 관련 이슈
기능 PR은 브랜치 계획의 **묶음 이슈**를 닫습니다(`Closes #<번호>`, 번호는 `docs/IMPLEMENTATION-STATUS.md` 브랜치 계획 표의 "이슈" 열). PR이 함께 해결하는 기술 부채 이슈가 있으면 추가로 적습니다. 관련 이슈가 없으면 `- 없음`이라고 씁니다. 이슈는 `gh issue list`로 확인합니다.

새 묶음 브랜치를 만들 때 묶음 이슈가 없으면 함께 만듭니다(본문은 할 일 체크리스트). 미결정 제품 질문은 `docs/PRODUCT.md`에서만 관리하고 이슈로 만들지 않습니다.

### ✨ 주요 작업 내용
PR이 큰 기능 묶음이므로, **하위 기능별로 묶어서** 씁니다(예: "CurrencyCode", "적용환율 계산기", "원장 엔티티"). 하위 기능마다 체크박스 한 줄을 두고, 필요하면 그 아래에 들여쓰기로 세부 항목을 적습니다. 근거는 `git log --oneline develop..HEAD`입니다. 엔티티, 리포지토리, 서비스 커밋 3개는 한 줄로 합쳐도 됩니다.

### 🧪 테스트 체크리스트
**실제로 사실인 항목만 체크합니다.** 거짓으로 체크된 박스는 나중의 나를 속입니다.

- `./gradlew build 통과` — 로컬이나 CI에서 실제로 통과했을 때만 체크합니다.
- `변경한 로직의 테스트 추가` — 이번 PR에서 실제로 테스트를 추가했을 때만 체크합니다. `contextLoads()`만 있다면 체크하지 않습니다.
- `API 직접 호출로 응답 확인` — 앱을 띄워 curl이나 HTTP 클라이언트로 호출하고 응답을 본 경우에만 체크합니다. 코드를 읽은 것은 해당하지 않습니다.
- `금액/원장/체결 불변식 점검` — 해당 코드가 바뀌었고 `qa-verify` 체크 8을 실제로 돌렸을 때만 체크합니다. 관련이 없으면 `(해당 없음)`이라고 적습니다.

체크하지 않은 항목은 이유를 "기타 참고사항"에 적습니다.

### 🗄️ 스키마 영향
엔티티 변경이 `ddl-auto: update`로 반영되는지 적고, 반영되지 않는 변경(이름 변경, 타입 변경 등)이 있다면 필요한 수동 SQL을 적습니다. 변경이 없다면 `- 없음`이라고 씁니다.

### 💬 기타 참고사항
다음 내용을 적습니다.

- 체크하지 않은 항목과 그 이유
- MySQL에서 확인하지 못한 동작 (H2에서만 확인한 것)
- 이번 PR에서 임시로 정한 제품 결정 (`docs/PRODUCT.md`의 미결정 질문)
- 다음에 할 일

## 생성

한국어와 마크다운이 셸 인용 과정에서 깨지지 않도록, 본문은 스크래치패드 파일로 써서 넘깁니다.

```bash
gh pr create --base develop --head "$(git branch --show-current)" \
  --title "feat: 지정가 주문 생성 API 추가" \
  --body-file <scratchpad>/pr-body.md
```

- 제목 형식은 커밋 규칙과 같은 `<type>: <한국어 요약>`이고, type은 PR의 주된 변경을 따릅니다.
- 생성한 뒤에는 **곧바로 `pr-self-review` 스킬을 실행합니다**(사용자 요청이 없어도). 리뷰와 반영을 마친 뒤 PR URL과 리뷰 결과를 사용자에게 알려줍니다.

## 병합 (요청 시)

```bash
gh pr checks <번호> --watch              # CI 통과 확인
gh pr merge <번호> --merge --delete-branch
git switch develop && git pull
```

- CI가 실패한 상태에서는 병합하지 않습니다. 실패 로그를 사용자에게 보여줍니다.
- 병합 방식은 `--merge`(merge commit)입니다. 잘게 나눈 커밋 기록을 남기기 위해서이며, squash는 쓰지 않습니다.

## 릴리스 PR (develop → main, 요청 시)

```bash
git fetch origin
git log --oneline origin/main..origin/develop     # 릴리스에 들어갈 내용
gh pr create --base main --head develop   --title "chore: <마일스톤> 릴리스" --body-file <scratchpad>/release-body.md
```

- 본문의 "주요 작업 내용"에는 커밋이 아니라 **병합된 기능 PR 목록**(`#번호 제목`)을 적습니다.
- 병합은 `--merge`로 하고 **`--delete-branch`를 붙이지 않습니다.** develop은 지우면 안 됩니다.
- 병합한 뒤 태그를 붙일지(`v0.1.0` 등)는 사용자에게 물어봅니다.
- `main`에만 있고 `develop`에 없는 커밋이 생기면(머지 커밋 외), 곧바로 `main` → `develop`으로 동기화합니다.

## 갱신

새 커밋으로 PR의 내용이 바뀌었다면 푸시한 뒤 본문(작업 내용, 체크리스트)도 갱신합니다.

```bash
git push
gh pr edit <번호> --body-file <scratchpad>/pr-body.md
```

## 규칙

- 기능 PR의 base는 `develop`입니다. 기능 브랜치를 `main`으로 PR하지 않습니다.
- `develop`이나 `main`에서 기능 PR을 만들지 않습니다(릴리스 PR만 예외).
- 브랜치 하나에는 PR 하나만 둡니다.
- 근거 없이 체크박스를 체크하지 않습니다.
- 본문에 비밀값, 토큰, `.env` 내용을 넣지 않습니다.
