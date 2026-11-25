---
name: pr-self-review
description: "AutoLog PR을 만든 직후 스스로 코드 리뷰를 하고, 지적 사항을 판단해 반영(커밋·푸시·해시 답글·resolve)하거나 이유를 남기고, 결과를 PR 본문에 기록한다. PR을 생성·갱신한 뒤에는 사용자가 따로 요청하지 않아도 항상 이 스킬을 실행한다. '셀프 리뷰', '리뷰하고 반영해줘', 'PR 점검해줘' 요청에도 사용한다."
---

# PR 셀프 리뷰 루프

리뷰어가 없는 1인 프로젝트이므로, PR마다 **Claude가 리뷰어 역할을 하고 그 기록을 PR에 남깁니다.** 그러면 PR만 봐도 "무엇을 점검했고, 무엇을 고쳤고, 무엇을 왜 그대로 뒀는지"를 알 수 있습니다.

**언제:** `github-pr-author`로 PR을 만든 직후에 실행합니다. 이후 의미 있는 커밋을 추가로 푸시했을 때도 그 변경분만 다시 실행합니다. 사용자가 요청하지 않아도 실행합니다.

**하지 않는 것**
- `gh pr review --approve`, `--request-changes`: 자기 PR은 approve할 수 없고, 이 명령은 사용자를 대신해 공식 의사를 표시하는 것이라 쓰지 않습니다.
- 머지: 사용자가 요청할 때만 합니다.

## 1단계: 리뷰

1. CI가 통과했는지 먼저 확인합니다(`gh pr checks <번호> --watch`). 실패했다면 리뷰보다 CI 수정이 먼저입니다.
2. **범용 리뷰**: `code-review` 스킬을 PR 번호를 대상으로 `--comment` 옵션과 함께 실행합니다. 발견 사항이 PR에 인라인 코멘트로 남습니다.
   - 금액, 원장, 체결, Kafka, Redis, 락 코드가 포함된 PR은 `high` 수준으로 실행합니다.
   - 그 밖의 PR은 기본 수준으로 실행합니다.
3. **프로젝트 전용 점검**: `qa-verify`에서 변경 범위에 해당하는 체크를 실행합니다. 금융 코드라면 체크 8, 비동기 코드라면 체크 9가 필수입니다. 범용 리뷰가 잡지 못한 발견 사항은 인라인 코멘트로 직접 남깁니다.

   ```bash
   gh api repos/HAAHYO/AutoLog_SERVER/pulls/<번호>/comments \
     -f body='[셀프 리뷰] <한국어 지적 내용>' \
     -f commit_id="$(gh pr view <번호> --json headRefOid -q .headRefOid)" \
     -f path='<파일 경로>' -F line=<줄 번호> -f side=RIGHT
   ```

4. 문서나 설정만 바뀐 PR은 인라인 코멘트 대신, 점검 결과를 PR 본문의 "🔍 셀프 리뷰" 섹션에만 적어도 됩니다.

## 2단계: 판단

리뷰 결과는 그대로 따를 지시가 아니라 **판단할 입력**입니다. 각 지적을 직접 코드에서 확인한 뒤 다음 중 하나로 분류합니다.

| 판단 | 기준 |
|------|------|
| **반영** | 실제 결함(런타임 오류, 정합성, 컨벤션 위반)이거나, 이 PR 범위 안의 명확한 개선 |
| **미반영: 범위 밖** | 맞는 지적이지만 이 PR과 무관함 → 브랜치 계획의 다음 묶음이나 `docs/IMPLEMENTATION-STATUS.md`의 알려진 이슈에 기록 |
| **미반영: 오탐** | 코드를 확인해 보니 지적이 사실과 다름 → 근거(`file:line`)를 남김 |
| **사용자 결정 필요** | 제품 결정, 스키마 변경, 새 의존성 → 작업을 멈추고 사용자에게 물어봄 |

## 3단계: 반영

반영 커밋과 푸시는 사용자가 상시 허용한 작업입니다(`AGENTS.md`의 확인 규칙 표). 따라서 확인 없이 진행하되, 대상은 **리뷰 중인 PR의 브랜치**로 한정합니다.

1. 반영할 지적마다 **같은 브랜치에** 커밋을 추가합니다. 메시지는 `git-commit-convention`을 따릅니다(보통 `fix:`, `refactor:`, `test:`).
   - 관련된 지적 여러 개를 커밋 하나로 묶지 않습니다. 지적 하나당 커밋 하나가 원칙입니다.
2. 코드를 고쳤다면 `./gradlew build`를 다시 실행합니다.
3. 푸시한 뒤 CI가 통과하는지 확인합니다.
4. 고친 부분만 다시 점검합니다. 이 왕복은 최대 2회입니다. 그 뒤에도 남는 문제는 사용자에게 보고합니다.

## 4단계: 답글과 resolve

스레드마다 첫 코멘트의 `databaseId`에 답글을 달고, 판단에 따라 resolve합니다.

```bash
# 스레드 조회 (id = threadId, comments.nodes[0].databaseId = 답글 대상)
gh api graphql -f query='
query($pr:Int!){ repository(owner:"HAAHYO",name:"AutoLog_SERVER"){
  pullRequest(number:$pr){ reviewThreads(first:100){ nodes{
    id isResolved path line comments(first:1){ nodes{ databaseId body } } } } } } }' -F pr=<번호>

# 답글
gh api repos/HAAHYO/AutoLog_SERVER/pulls/<번호>/comments -f body='<답글>' -F in_reply_to=<databaseId>

# resolve
gh api graphql -f query='mutation($id:ID!){ resolveReviewThread(input:{threadId:$id}){ thread{ isResolved } } }' -f id=<threadId>
```

| 판단 | 답글 | resolve |
|------|------|---------|
| 반영 | `반영했습니다. (\`<short hash>\`)` + 무엇을 바꿨는지 한 줄 | O |
| 미반영: 범위 밖 | 이유와 함께, 어디에 기록했는지 | O |
| 미반영: 오탐 | 근거 `file:line` | O |
| 사용자 결정 필요 | "사용자 결정 대기"라고 적음 | **X** (결정이 나면 반영한 뒤 resolve) |

1인 프로젝트라서 미반영 스레드도 이유를 남긴 뒤 resolve합니다. 리뷰어가 따로 없으니, 열어 두면 아무도 닫지 않습니다. 대신 이유는 반드시 답글로 남깁니다.

## 5단계: 기록

PR 본문의 "🔍 셀프 리뷰" 섹션을 채우고 `gh pr edit <번호> --body-file ...`로 갱신합니다.

```markdown
## 🔍 셀프 리뷰
- 실행: code-review(<수준>) + qa-verify 체크 <번호들>
- 발견 <n>건 → 반영 <n> / 범위 밖 <n> / 오탐 <n> / 결정 대기 <n>
- 반영: `<hash>` <한 줄>, ...
- 미반영: <한 줄 + 이유>, ...
```

마지막으로 사용자에게 한국어로 요약합니다. 내용은 "PR URL, 발견·반영 건수, 남은 결정 사항, 머지 가능 여부"입니다. 머지는 요청할 때만 합니다.
