# eGov Ollama Assist — Eclipse 플러그인 (Continue 유사 기능)

폐쇄망 Ollama 서버에 직접 연결되는 **Eclipse용 AI 코딩 보조 플러그인**입니다.
Continue(VS Code) 의 핵심 기능 중 Eclipse에서 구현 가능한 부분을 직접 만들었습니다.

- 외부 라이브러리 의존성 **없음** (JDK + Eclipse SDK API만 사용) → 폐쇄망에서 PDE로 그대로 빌드/배포 가능
- Ollama 네이티브 API(`/api/chat`, 스트리밍) 사용

## 제공 기능

| 기능 | 지원 | 설명 |
|------|------|------|
| AI 채팅 (스트리밍) | ✅ | 전용 뷰에서 질문/답변, 실시간 스트리밍 출력 |
| **Agent (Claude Code 수준)** | ✅ | 검색·읽기·**부분 수정**·생성·덮어쓰기·**명령 실행**을 자율 수행(변경 전 확인) |
| 선택 코드 설명 | ✅ | 편집기에서 코드 선택 → 우클릭 → Ollama Assist > 코드 설명 |
| 선택 코드 리팩터링 제안 | ✅ | 우클릭 → Ollama Assist > 리팩터링 제안 |
| 작업 중지 | ✅ | 긴 Agent 작업을 [중지] 버튼으로 취소 |
| **diff 미리보기 확인** | ✅ | 파일 변경 전 색상 diff(빨강=삭제/초록=추가)로 확인 후 적용 |
| **대화 저장/지우기** | ✅ | 뷰 툴바에서 대화 내용 저장(.md)·초기화 |
| **선택 코드 보내기** | ✅ | 편집기에서 코드 선택 → `Ctrl+Alt+A`(또는 우클릭) → 뷰 입력창에 코드블록 채움 |
| **자동 검증 루프** | ✅ | 수정 후 빌드/Problems 를 자동 확인 → 실패 시 모델이 자가수정(최대 3회) |
| **낮은 temperature** | ✅ | 코딩 안정성 위해 기본 0.2 (Preferences에서 조정) |
| 한국어 응답 강제 | ✅ | 시스템 프롬프트 기본 내장(Preferences에서 변경) |
| 서버/모델 설정 | ✅ | Window > Preferences > Ollama Assist |

> **Agent 모드는 도구 호출(tool_use) 지원 모델이 필요합니다.** `qwen3-coder:30b` 처럼 tool 지원 모델을 쓰세요.

### Agent 도구 (Claude Code 유사)
| 도구 | 설명 | 확인 |
|------|------|------|
| `list_files` | 프로젝트 파일/폴더 목록(재귀) | - |
| `search_text` | 전체 프로젝트 문자열 검색(파일:줄) | - |
| `semantic_search` | **코드베이스 의미 검색(RAG)** — 키워드가 정확치 않아도 의미가 가까운 코드를 찾음(색인 필요) | - |
| `read_file` | 파일 내용 읽기(`start_line`/`end_line` 범위 지정 가능 — 큰 .xfdl 등) | - |
| `apply_edit` | **부분 수정** — old_text→new_text 한 곳만 교체(diff 미리보기) | ✅ |
| `create_file` | 새 파일 생성 | ✅ |
| `write_file` | 파일 전체 덮어쓰기 | ✅ |
| `run_command` | 빌드/테스트 등 명령 실행 (**기본 비활성**) | ✅ |
| `get_problems` | **Eclipse Problems**(컴파일 오류/경고) 목록 분석 | - |
| `get_console` | **Eclipse Console** 최근 출력(빌드/실행 로그) 분석 | - |
| `list_servers` | 등록 서버(Tomcat 등)와 상태 | - |
| `start_server` / `stop_server` | 서버 기동/중지(WTP) | ✅ |

> **Problems/Console/Servers 연동**: AI가 컴파일 오류를 `get_problems` 로 직접 읽어 고치고, 빌드 로그를 `get_console` 로 확인하며, Tomcat 등을 `start_server`/`stop_server` 로 제어합니다.
> - 예: *"컴파일 오류 다 고쳐줘"* → get_problems 로 오류 목록 확인 → read_file/apply_edit 로 수정 반복
> - 예: *"Tomcat 재시작하고 콘솔 확인해줘"* → stop_server → start_server → get_console
> - 서버 제어는 **WTP(org.eclipse.wst.server.core)** 가 있는 Eclipse(eGovFrame IDE 등)에서 동작하며, 없으면 안내 메시지를 반환합니다(리플렉션 호출이라 빌드는 영향 없음).

### Agent 동작 방식
1. 모델에 위 도구를 제공하고 사용자 요청 전달
2. 모델이 `tool_calls` 반환 → 플러그인이 **프로젝트 루트 내에서만** 실행(경로 이탈 차단)
3. 파일 변경/명령 실행은 **확인 다이얼로그**(diff·미리보기) 후 적용 → 워크스페이스 자동 새로고침
4. 더 호출할 도구가 없을 때까지 반복(최대 25회) 후 한국어로 작업 요약
5. **[중지]** 버튼으로 언제든 취소(진행 중인 한 단계 후 멈춤)

### 자동 검증 루프 (약한 모델 품질 보강)
파일을 수정한 뒤 Agent 가 **스스로 검증하고 오류를 고칩니다**.
1. 수정 발생 → 검증 실행
   - **검증 명령**이 설정돼 있고 명령 실행이 허용되면 그 명령(예: `mvn -q compile`)을 실행
   - 아니면 **Eclipse Problems**(컴파일 오류/경고)를 확인
2. 실패 시 오류 내용을 모델에 전달해 **재수정**(최대 3회) → 통과하면 종료
- 설정: Window > Preferences > Ollama Assist
  - *자동 검증 명령* (비우면 Problems 사용; 명령 사용은 run 허용 필요)
  - *temperature* (코딩 권장 0.1~0.3, 기본 0.2)

> 💡 약한 로컬 모델일수록 **빌드 피드백 루프 + 낮은 temperature**의 효과가 큽니다. 정확한 검증 명령(`mvn -q compile` 등)을 설정하면 자가수정 성공률이 크게 올라갑니다.

### ⚠️ run_command (명령 실행)
- **기본 비활성**입니다. Window > Preferences > Ollama Assist 의 *"Agent 의 명령 실행 허용"* 을 켜야 사용됩니다.
- 켜더라도 실행 직전 **확인 다이얼로그**로 명령을 보여주고 승인받습니다. 작업 폴더는 프로젝트 루트, 120초 타임아웃.

### 정확도 강화 (프로젝트 맥락 주입)
- **채팅 자동 RAG**: 색인이 있으면 일반 채팅에서도 질문과 관련된 코드 발췌를 **자동 첨부**해 답합니다(끄기: Preferences ▸ "채팅에 코드 자동 참고").
- **프로젝트 규칙(AGENTS.md)**: 프로젝트 루트의 `AGENTS.md`(또는 `.ollama-assist.md`)를 채팅·Agent 시스템 프롬프트에 **자동 주입** → 사내 컨벤션/패턴을 따릅니다.
- **`read_file` 라인 범위**: `start_line`/`end_line` 으로 큰 파일(넥사크로 `.xfdl` 등) 일부만 읽기.

### 코드베이스 학습(RAG) — 파인튜닝 없이 "프로젝트를 아는" 효과
파인튜닝 대신 **임베딩 색인 + 의미 검색**으로 프로젝트 맥락을 활용합니다(폐쇄망 적합).

1. 뷰의 **[색인]** 버튼 → 활성 프로젝트를 임베딩 모델(기본 `nomic-embed-text`)로 색인
   - 결과는 `<프로젝트>/.ollama-assist/index.json` 에 저장되어 재시작 후에도 재사용(자동 로드)
   - 큰 프로젝트는 시간이 걸리며 [중지]로 취소 가능(최대 4000 청크)
2. Agent 가 **`semantic_search`** 도구로 의미가 가까운 코드를 찾아 그 패턴대로 구현
   - 예: *"기존 게시판 CRUD와 같은 구조로 공지사항 만들어줘"* → 관련 코드 검색 후 동일 스타일 작성
3. 임베딩 모델은 Window > Preferences > Ollama Assist 에서 변경(`bge-m3` 등)
4. **[색인삭제]** 버튼 → 저장된 색인(`.ollama-assist/index.json`) + 메모리 색인 제거(확인 후)

> 코드가 바뀌면 [색인]만 다시 누르면 됩니다(학습 불필요). 정확한 키워드 검색은 `search_text`, 모호한 의미 검색은 `semantic_search` 를 씁니다.

### 이번 버전에서 제외 (로드맵)
- 인라인 자동완성(FIM) — Eclipse content-assist 깊은 연동 필요
- 채팅(비-Agent) 자동 RAG 주입 — 현재는 Agent 의 semantic_search 도구로 제공

> 자동완성·Agent·코드베이스 검색까지 필요하면 **VS Code + Continue** 를 병행하세요
> (저장소의 `docs/local-dev-setup-windows.md`).

---

## 프로젝트 구조

```
eclipse-plugin/com.egov.ollama.assist/
├── META-INF/MANIFEST.MF        # OSGi 번들 정의 / 의존성
├── plugin.xml                  # 뷰/명령/핸들러/메뉴/설정 확장점
├── build.properties
├── .project / .classpath       # PDE 프로젝트 메타
└── src/com/egov/ollama/assist/
    ├── Activator.java
    ├── JsonUtil.java           # 의존성 없는 최소 JSON 처리
    ├── OllamaClient.java       # /api/chat 스트리밍 호출
    ├── ui/ChatView.java        # 채팅 뷰(SWT)
    ├── handlers/               # 코드 설명/리팩터링 핸들러
    └── preferences/            # 서버/모델/시스템프롬프트 설정
```

---

## 빌드 & 설치 (폐쇄망, Eclipse PDE)

인터넷 없이 **개발 중인 Eclipse 자체를 타깃 플랫폼**으로 빌드합니다.

### A. 바로 실행해 보기 (개발/테스트)
1. Eclipse(PDE 포함, "Eclipse for RCP/RAP" 또는 eGovFrame IDE)에서
   **File > Import > General > Existing Projects into Workspace** →
   `eclipse-plugin/com.egov.ollama.assist` 선택 → Import
2. 프로젝트 우클릭 → **Run As > Eclipse Application**
   → 새 Eclipse 인스턴스가 뜨고 플러그인이 로드됩니다.

### B. 설치용 jar 로 내보내기 (배포)
1. 프로젝트 우클릭 → **Export > Plug-in Development > Deployable plug-ins and fragments**
2. 대상 폴더 지정 → Finish → `plugins/com.egov.ollama.assist_0.1.0.jar` 생성
3. 이 jar 를 Eclipse 설치 폴더의 **`dropins/`** 에 복사 → Eclipse 재시작
   - 또는 다른 PC에 배포 시에도 `dropins/` 에 넣으면 됩니다(동일 폐쇄망 내 복사).

> 📦 **수동 설치/삭제 상세 + 자동 스크립트**: [INSTALL.md](INSTALL.md), [install.bat](install.bat), [uninstall.bat](uninstall.bat)

> JDK 17 이상이 필요합니다. eGov 환경이면 `eclipse.ini` 의 `-vm` 에
> `C:\eGovFrameDev-5.0.0\bin\jdk-17\bin` 을 지정하세요.

---

## 사용법

> **워크스페이스에 프로젝트가 여러 개면**, 뷰 맨 위의 **대상 프로젝트 드롭다운**에서 작업할 프로젝트를 먼저 고르세요. Agent·색인·색인삭제가 모두 이 선택 프로젝트에 적용됩니다. (드롭다운을 클릭하면 열린 프로젝트 목록이 갱신되고, 기본값은 활성 편집기의 프로젝트입니다.)

1. **서버 설정**: Window > Preferences > **Ollama Assist**
   - Ollama 서버 URL: `http://192.168.45.214:11434` (← 실제 서버 IP)
   - 모델 이름: `qwen3-coder:30b` (서버 `ollama list` 와 일치)
   - 시스템 프롬프트: (기본값에 한국어 응답 지시 포함)
2. **채팅 뷰 열기**: Window > Show View > Other > **Ollama Assist > Ollama Assist**
3. **채팅**: 입력창에 질문 → [보내기] 또는 `Ctrl+Enter`
4. **코드 설명/리팩터링**: 편집기에서 코드 선택 → 우클릭 → **Ollama Assist** 메뉴

---

## 동작 확인 / 문제 해결

- 연결 점검(cmd): `curl http://192.168.45.214:11434/api/tags`
- 응답이 안 오면: 서버 `OLLAMA_HOST=0.0.0.0:11434`, 방화벽 11434, 모델명 일치 확인
- 빌드/임포트 에러: PDE(Plug-in Development Environment)가 설치된 Eclipse인지 확인
- 한글 깨짐(콘솔): 이 플러그인은 SWT 위젯에 출력하므로 콘솔 코드페이지와 무관합니다

---

## 라이선스 / 확장

내부 사용 목적의 예시 구현입니다. 자유롭게 수정해 사용하세요.
스트리밍 콜백(`OllamaClient.ChunkConsumer`)과 핸들러(`AbstractCodeActionHandler`) 구조라
새 기능(테스트 생성, 주석 추가 등)은 핸들러 한 개 추가로 쉽게 확장할 수 있습니다.
