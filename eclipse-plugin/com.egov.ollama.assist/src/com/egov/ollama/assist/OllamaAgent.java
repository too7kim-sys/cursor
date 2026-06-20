package com.egov.ollama.assist;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Claude Code 수준의 도구 호출 에이전트(폐쇄망 Ollama 전용).
 * <p>
 * 제공 도구: list_files, read_file, search_text, create_file, apply_edit(부분 수정),
 * write_file(전체 덮어쓰기), run_command(선택). 모델의 tool_calls 를 프로젝트 루트 내에서
 * 실행하고 결과를 회신하며 반복한다. 파일 변경/명령 실행은 사용자 확인을 거친다.
 */
public class OllamaAgent {

	/** 진행/응답 출력 콜백 */
	public interface Logger {
		/** 모델의 실제 답변 내용을 출력. */
		void log(String text);

		/** 단계별 진행·상태(도구 호출, 결과 요약, 진행 안내 등)를 출력. 기본은 log 와 동일. */
		default void progress(String text) {
			log(text);
		}
	}

	/** 변경/명령 확인 콜백. true 면 진행. */
	public interface Confirm {
		boolean ask(String title, String message);
	}

	/**
	 * Eclipse 환경 연동(진단/서버). UI 계층에서 구현해 주입한다.
	 * 순수 JDK 로직과 분리하기 위해 인터페이스로 둔다.
	 */
	public interface Environment {
		String getProblems();

		String getConsole();

		/** run_command 등의 출력을 Eclipse Console 패널로 보낸다(기본 무동작). */
		default void console(String text) {
		}

		String listServers();

		String startServer(String name);

		String stopServer(String name);
	}

	/** 코드베이스 의미 검색(RAG). 색인이 없으면 null. */
	public interface Retriever {
		String search(String query) throws Exception;
	}

	private static final String ENV_NA = "이 기능은 현재 사용할 수 없습니다(Eclipse 환경 미연결).";

	private static final int MAX_ITER = 25;
	private static final int MAX_VERIFY = 3;
	private int maxVerify = MAX_VERIFY;

	/** 자동 검증 재시도 최대 횟수(환경설정에서 주입). 1 미만이면 검증 루프 비활성. */
	public void setMaxVerify(int n) {
		this.maxVerify = Math.max(0, n);
	}
	private static final int MAX_LIST = 400;
	private static final int MAX_READ = 60000;
	private static final int MAX_SEARCH = 100;
	private static final int CMD_TIMEOUT_SEC = 120;
	/** 이 문자 수를 넘으면 오래된 도구 결과를 압축한다. */
	private static final int CONTEXT_BUDGET = 48000;
	/** 압축 시 보존할 최근 도구 결과 수. */
	private static final int KEEP_RECENT_TOOL = 4;
	/** 오래된 도구 결과를 줄일 목표 길이. */
	private static final int COMPACT_TOOL_CHARS = 600;
	/** 같은 호출이 이 횟수에 도달하면 모델에 경고(넌지). */
	private static final int LOOP_NUDGE = 3;
	/** 같은 호출이 이 횟수에 도달하면 실행을 중단. */
	private static final int LOOP_ABORT = 5;
	/** 시작 시 주입할 프로젝트 구조 최대 항목 수. */
	private static final int STRUCT_MAX = 120;
	/** 구조 힌트 system 메시지의 접두어(컨텍스트 압축 시 식별용). */
	private static final String STRUCT_HINT = "프로젝트 구조(일부)";

	private final String base;
	private final String model;
	private final String system;
	private final File root;
	private final Logger log;
	private final Confirm confirm;
	private final boolean enableRun;
	private final BooleanSupplier cancelled;
	private final Environment env;
	private final Retriever retriever;
	private final double temperature;
	private final String verifyCommand;
	private boolean edited;
	/** 이번 실행에서 적용한 변경 기록. */
	private final java.util.List<FileChange> appliedChanges = new java.util.ArrayList<>();
	/** 동일 도구 호출 반복(루프) 감지기. */
	private final RepeatTracker repeats = new RepeatTracker();

	public OllamaAgent(String base, String model, String system, File root, boolean enableRun,
			double temperature, String verifyCommand,
			Logger log, Confirm confirm, BooleanSupplier cancelled, Environment env, Retriever retriever) {
		this.base = base;
		this.model = model;
		this.system = system;
		this.root = root;
		this.enableRun = enableRun;
		this.temperature = temperature;
		this.verifyCommand = verifyCommand;
		this.log = log;
		this.confirm = confirm;
		this.cancelled = cancelled;
		this.env = env;
		this.retriever = retriever;
	}

	public void run(String userPrompt) throws IOException {
		final String tools = toolsJson();
		List<Object> messages = new ArrayList<>();
		if (system != null && !system.trim().isEmpty()) {
			messages.add(msg("system", system));
		}
		messages.add(msg("system", agentSystemPrompt()));
		String rules = readProjectRules(root);
		if (rules != null) {
			messages.add(msg("system", "이 프로젝트의 규칙/컨벤션(AGENTS.md). 반드시 준수하세요:\n" + rules));
			log.progress("\n(프로젝트 규칙 AGENTS.md 적용)\n");
		}
		String structure = projectStructure();
		if (structure != null && !structure.isEmpty()) {
			messages.add(msg("system",
					STRUCT_HINT + ". list_files 호출을 줄이기 위한 참고용이며, 자세한 내용은 도구로 확인하세요:\n" + structure));
		}
		messages.add(msg("user", userPrompt));
		int verifyRounds = 0;

		for (int iter = 0; iter < MAX_ITER; iter++) {
			if (isCancelled()) {
				log.log("\n[중지됨]\n");
				return;
			}
			// 컨텍스트 한계 방지: 대화가 커지면 오래된 도구 결과를 압축하고,
			// 그래도 예산을 넘으면 1회성 구조 힌트(고정 오버헤드)까지 줄인다.
			if (ContextManager.estimateChars(messages) > CONTEXT_BUDGET) {
				int saved = ContextManager.compactToolOutputs(messages, KEEP_RECENT_TOOL, COMPACT_TOOL_CHARS);
				if (ContextManager.estimateChars(messages) > CONTEXT_BUDGET) {
					saved += ContextManager.compactStaleHints(messages, STRUCT_HINT, 400);
				}
				if (saved > 0) {
					log.progress("\n🗜 컨텍스트 정리(" + saved + "자 축약)\n");
				}
			}
			String body = "{\"model\":" + JsonUtil.quote(model)
					+ ",\"messages\":" + Json.write(messages)
					+ ",\"tools\":" + tools
					+ ",\"stream\":false"
					+ ",\"options\":{\"temperature\":" + temperature + "}}";

			log.progress("\n(모델 응답 생성 중… #" + (iter + 1) + ")\n");
			String resp = OllamaClient.post(base, "/api/chat", body);
			Object parsed = Json.parse(resp);
			if (!(parsed instanceof Map)) {
				log.log("\n[오류] 예상치 못한 응답 형식\n");
				return;
			}
			Map<?, ?> rootMap = (Map<?, ?>) parsed;
			if (rootMap.get("error") != null) {
				log.log("\n[오류] " + rootMap.get("error") + "\n");
				return;
			}
			Object msgO = rootMap.get("message");
			if (!(msgO instanceof Map)) {
				log.log("\n[오류] 응답에 message 가 없습니다\n");
				return;
			}
			Map<String, Object> message = castMap(msgO);
			messages.add(message);

			List<?> toolCalls = (message.get("tool_calls") instanceof List) ? (List<?>) message.get("tool_calls")
					: null;
			String content = asString(message.get("content"));

			if (toolCalls == null || toolCalls.isEmpty()) {
				// 자동 검증 루프: 수정이 있었고 검증 수단이 있으면 빌드/오류를 확인해 실패 시 재수정
				if (edited && verifyRounds < maxVerify && !isCancelled()) {
					String verdict = autoVerify();
					if (verdict != null) {
						verifyRounds++;
						log.progress("\n🔁 자동 검증 실패 — 수정 재시도 (" + verifyRounds + "/" + maxVerify + ")\n");
						edited = false;
						messages.add(msg("user",
								"자동 검증에서 문제가 발견되었습니다. 아래 내용을 분석해 코드를 수정하세요. "
										+ "수정 후에는 추가 설명만 하세요.\n\n" + verdict));
						continue;
					}
					log.progress("\n✅ 자동 검증 통과\n");
				}
				if (content != null && !content.isEmpty()) {
					log.log(content);
				}
				log.progress("\n\n[완료]\n");
				return;
			}

			for (Object tco : toolCalls) {
				if (isCancelled()) {
					log.log("\n[중지됨]\n");
					return;
				}
				if (!(tco instanceof Map)) {
					continue;
				}
				Map<?, ?> tc = (Map<?, ?>) tco;
				Object fnO = tc.get("function");
				if (!(fnO instanceof Map)) {
					continue;
				}
				Map<?, ?> fn = (Map<?, ?>) fnO;
				String name = asString(fn.get("name"));
				Map<String, Object> args = toArgs(fn.get("arguments"));

				// 막힘(루프) 감지: 같은 도구를 같은 인자로 "연달아" 반복하면 한 번 넌지하고, 계속되면 중단.
				// 표시용 briefArgs 가 아니라 전체 인자(Json)로 서명해 서로 다른 호출이 충돌하지 않게 한다.
				String sig = RepeatTracker.signature(name, Json.write(args));
				int times = repeats.recordStreak(sig);
				if (times >= LOOP_ABORT) {
					log.log("\n[안내] 동일한 호출(" + name + ")이 " + times + "회 연속 반복되어 중단합니다. 접근을 바꿔 다시 시도하세요.\n");
					return;
				}

				log.progress("\n🔧 " + name + "(" + briefArgs(args) + ")\n");
				String result = executeTool(name, args);
				log.progress("   ↳ " + firstLine(result) + "\n");

				Map<String, Object> toolMsg = new LinkedHashMap<>();
				toolMsg.put("role", "tool");
				toolMsg.put("tool_name", name);
				if (times == LOOP_NUDGE) {
					result = result + "\n\n[주의] 같은 도구를 같은 인자로 여러 번 호출하고 있습니다. "
							+ "다른 접근(다른 파일/검색어/도구)을 시도하거나, 정보가 충분하면 작업을 마무리하세요.";
				}
				toolMsg.put("content", result);
				messages.add(toolMsg);
			}
		}
		log.log("\n[안내] 최대 반복 횟수(" + MAX_ITER + ")에 도달해 중단했습니다.\n");
	}

	private boolean isCancelled() {
		return cancelled != null && cancelled.getAsBoolean();
	}

	private String agentSystemPrompt() {
		StringBuilder sb = new StringBuilder();
		sb.append("당신은 숙련된 코드 작업 에이전트입니다. 제공된 도구로 프로젝트를 직접 탐색·수정하세요.\n");
		sb.append("작업 원칙:\n");
		sb.append("0) 여러 단계가 필요한 작업은 먼저 update_plan 으로 계획을 세우고, 단계가 끝날 때마다 상태를 갱신한다.\n");
		sb.append("1) 추측하지 말고 search_text(정확한 키워드)/semantic_search(의미 기반)/find_files(파일명)/list_files/read_file 로 실제 코드를 먼저 확인한다.\n");
		sb.append("   - search_text 는 regex(정규식), glob(파일 한정, 예 *.java), context(주변 줄)로 좁힐 수 있다. find_files 로 파일명을 빠르게 찾는다.\n");
		sb.append("   - 큰 파일은 통째로 읽지 말고 outline 으로 구조를 본 뒤 read_symbol 로 필요한 메서드/클래스만 읽는다.\n");
		if (retriever != null) {
			sb.append("   - 어디를 봐야 할지 모호하면 semantic_search 로 관련 코드를 먼저 찾는다(프로젝트 패턴을 따른다).\n");
		}
		sb.append("2) 기존 파일 수정은 가능한 한 apply_edit(부분 수정)을 사용한다. old_text 는 파일에서 유일하게 식별되는 충분한 길이로 제시한다.\n");
		sb.append("   - old_text 를 정확히 옮기기 어렵거나 실패하면, read_file 로 줄번호를 확인한 뒤 replace_lines(start_line, end_line)로 줄 범위를 교체한다.\n");
		sb.append("3) 새 파일은 create_file 로 만든다. 파일 전체를 바꿔야 할 때만 write_file 을 쓴다. 파일 삭제는 delete_file, 이동/이름변경은 move_file 을 쓴다.\n");
		if (enableRun) {
			sb.append("4) 필요 시 run_command 로 빌드/테스트를 실행해 결과를 확인한다.\n");
		}
		if (env != null) {
			sb.append("5) 컴파일 오류 수정 요청 시 get_problems 로 실제 오류 목록을 먼저 확인하고, ")
					.append("실행/빌드 로그는 get_console 로 확인한다. 서버는 list_servers/start_server/stop_server 로 다룬다.\n");
		}
		sb.append("6) 마치기 전에 변경이 요청을 충족하는지 스스로 점검한다(필요하면 수정한 파일을 read_file 로 재확인).\n");
		sb.append("작업이 끝나면 변경한 파일과 이유를 한국어로 요약한다.");
		return sb.toString();
	}

	// ===================== 도구 정의 =====================

	private String toolsJson() {
		List<Object> tools = new ArrayList<>();
		Map<String, Object> p;

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "상대 경로(기본 '.')"));
		tools.add(func("list_files", "프로젝트 루트 기준 파일/폴더 목록을 재귀적으로 반환", p, null));

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "읽을 파일 경로"));
		p.put("start_line", prop("integer", "시작 줄(1부터, 선택). 큰 파일은 범위 지정 권장"));
		p.put("end_line", prop("integer", "끝 줄(선택)"));
		tools.add(func("read_file", "파일 내용을 반환(start_line/end_line 으로 범위 지정 가능)", p, Arrays.asList("path")));

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "대상 파일 경로"));
		tools.add(func("outline", "파일의 클래스/메서드/함수 정의 목록을 '줄번호: 선언' 으로 반환(파일 구조 파악용)",
				p, Arrays.asList("path")));

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "대상 파일 경로"));
		p.put("symbol", prop("string", "읽을 심볼명(메서드/클래스/함수)"));
		tools.add(func("read_symbol",
				"파일에서 특정 심볼(메서드/클래스)의 본문만 줄번호와 함께 반환. 큰 파일을 통째 읽지 말고 필요한 부분만 본다",
				p, Arrays.asList("path", "symbol")));

		p = new LinkedHashMap<>();
		p.put("query", prop("string", "찾을 문자열(regex=true 면 정규식)"));
		p.put("regex", prop("boolean", "true 면 query 를 정규식으로 해석(기본 false)"));
		p.put("glob", prop("string", "검색 대상 파일 제한(예: *.java, src/**/*.xml). 비우면 전체"));
		p.put("context", prop("integer", "일치 줄 앞뒤로 함께 보여줄 줄 수(기본 0)"));
		tools.add(func("search_text",
				"프로젝트에서 문자열/정규식을 검색해 파일:줄 위치를 반환. glob 로 파일을 좁히고 context 로 주변 줄을 함께 본다",
				p, Arrays.asList("query")));

		p = new LinkedHashMap<>();
		p.put("glob", prop("string", "파일명/경로 glob(예: *Service.java, src/**/*.xml)"));
		tools.add(func("find_files", "이름/경로 glob 으로 파일을 빠르게 찾아 경로 목록을 반환", p, Arrays.asList("glob")));

		if (retriever != null) {
			p = new LinkedHashMap<>();
			p.put("query", prop("string", "자연어 또는 코드로 된 검색 의도"));
			tools.add(func("semantic_search",
					"코드베이스 의미 검색(RAG). 키워드가 정확치 않아도 의미가 가까운 코드 조각을 찾아 반환",
					p, Arrays.asList("query")));
		}

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "새 파일 경로"));
		p.put("content", prop("string", "파일 내용"));
		tools.add(func("create_file", "새 파일을 생성(이미 있으면 실패). 사용자 확인 후 적용", p, Arrays.asList("path", "content")));

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "수정할 파일 경로"));
		p.put("old_text", prop("string", "교체 대상이 되는, 파일 내에서 유일한 기존 텍스트(공백/들여쓰기 포함)"));
		p.put("new_text", prop("string", "대체할 새 텍스트"));
		p.put("all", prop("boolean", "true 면 일치하는 모든 곳을 교체(정확 일치만). 기본 false=한 곳"));
		tools.add(func("apply_edit",
				"파일에서 old_text 를 찾아 new_text 로 교체(부분 수정). 기본은 한 곳, all=true 면 전체. 사용자 확인 후 적용",
				p, Arrays.asList("path", "old_text", "new_text")));

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "수정할 파일 경로"));
		p.put("start_line", prop("integer", "교체 시작 줄(1부터, 포함)"));
		p.put("end_line", prop("integer", "교체 끝 줄(포함)"));
		p.put("new_text", prop("string", "해당 줄 범위를 대체할 새 내용(빈 값이면 삭제)"));
		tools.add(func("replace_lines",
				"read_file 로 본 줄번호 기준으로 [start_line..end_line] 구간을 new_text 로 교체. old_text 를 정확히 옮기기 어려울 때 사용. 사용자 확인 후 적용",
				p, Arrays.asList("path", "start_line", "end_line", "new_text")));

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "파일 경로"));
		p.put("content", prop("string", "전체 새 내용"));
		tools.add(func("write_file", "파일을 새 내용으로 전체 덮어쓰기. 사용자 확인 후 적용", p, Arrays.asList("path", "content")));

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "삭제할 파일 경로"));
		tools.add(func("delete_file", "파일을 삭제(되돌리기 이력에 기록). 사용자 확인 후 적용", p, Arrays.asList("path")));

		p = new LinkedHashMap<>();
		p.put("from", prop("string", "원본 파일 경로"));
		p.put("to", prop("string", "대상 파일 경로(이동/이름변경)"));
		tools.add(func("move_file", "파일을 이동하거나 이름을 변경(되돌리기 이력에 기록). 사용자 확인 후 적용",
				p, Arrays.asList("from", "to")));

		p = new LinkedHashMap<>();
		Map<String, Object> steps = prop("array",
				"작업 단계 목록. 각 항목은 문자열이거나 {step, status} 객체(status: pending|in_progress|done)");
		steps.put("items", prop("string", "단계 설명"));
		p.put("steps", steps);
		tools.add(func("update_plan",
				"현재 작업 계획(todo)을 갱신해 사용자에게 진행 상황을 보여준다. 복잡한 작업은 먼저 계획을 세우고 단계마다 갱신한다",
				p, Arrays.asList("steps")));

		if (enableRun) {
			p = new LinkedHashMap<>();
			p.put("command", prop("string", "프로젝트 루트에서 실행할 쉘 명령(예: mvn -q compile)"));
			tools.add(func("run_command", "프로젝트 루트에서 명령을 실행하고 출력을 반환. 사용자 확인 후 실행",
					p, Arrays.asList("command")));
		}

		if (env != null) {
			tools.add(func("get_problems", "Eclipse Problems 뷰의 컴파일 오류/경고 목록(파일:줄: 메시지)을 반환",
					new LinkedHashMap<>(), null));
			tools.add(func("get_console", "Eclipse Console 의 최근 출력(빌드/실행 로그)을 반환",
					new LinkedHashMap<>(), null));
			tools.add(func("list_servers", "등록된 서버(Tomcat 등)와 상태 목록을 반환",
					new LinkedHashMap<>(), null));
			p = new LinkedHashMap<>();
			p.put("name", prop("string", "서버 이름(list_servers 로 확인)"));
			tools.add(func("start_server", "지정한 서버를 기동(사용자 확인 후)", p, Arrays.asList("name")));
			p = new LinkedHashMap<>();
			p.put("name", prop("string", "서버 이름"));
			tools.add(func("stop_server", "지정한 서버를 중지(사용자 확인 후)", p, Arrays.asList("name")));
		}

		return Json.write(tools);
	}

	private static Map<String, Object> prop(String type, String desc) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", type);
		if (desc != null) {
			m.put("description", desc);
		}
		return m;
	}

	private static Map<String, Object> func(String name, String desc, Map<String, Object> properties,
			List<String> required) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("type", "object");
		params.put("properties", properties);
		if (required != null && !required.isEmpty()) {
			params.put("required", required);
		}
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("name", name);
		f.put("description", desc);
		f.put("parameters", params);
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("type", "function");
		t.put("function", f);
		return t;
	}

	// ===================== 도구 실행 =====================

	/** 이번 실행에서 적용된 변경 목록. 검토/되돌리기용. */
	public java.util.List<FileChange> getAppliedChanges() {
		return appliedChanges;
	}

	/** 한 실행 안에서 변하지 않는 읽기 도구 결과 캐시. 파일 수정 시 비운다. */
	private final java.util.Map<String, String> toolCache = new java.util.HashMap<>();

	private static boolean isCacheable(String name) {
		switch (name == null ? "" : name) {
		case "list_files":
		case "read_file":
		case "outline":
		case "read_symbol":
		case "search_text":
		case "find_files":
			return true;
		default:
			return false;
		}
	}

	private static boolean isModifying(String name) {
		switch (name == null ? "" : name) {
		case "create_file":
		case "apply_edit":
		case "replace_lines":
		case "write_file":
		case "delete_file":
		case "move_file":
			return true;
		default:
			return false;
		}
	}

	private String executeTool(String name, Map<String, Object> args) {
		String cacheKey = isCacheable(name) ? name + "|" + Json.write(args) : null;
		if (cacheKey != null) {
			String hit = toolCache.get(cacheKey);
			if (hit != null) {
				return hit;
			}
		}
		String result = dispatchTool(name, args);
		if (cacheKey != null && result != null && !result.startsWith("도구 오류")) {
			toolCache.put(cacheKey, result);
		}
		// 파일이 바뀌면 읽기 캐시가 낡으므로 비운다(다음 read 가 디스크를 다시 본다)
		if (isModifying(name) && result != null && (result.contains("완료") || result.contains("저장"))) {
			toolCache.clear();
		}
		return result;
	}

	private String dispatchTool(String name, Map<String, Object> args) {
		try {
			switch (name == null ? "" : name) {
			case "list_files":
				return listFiles(asString(args.get("path")));
			case "read_file":
				return readFile(asString(args.get("path")), asInt(args.get("start_line")), asInt(args.get("end_line")));
			case "outline":
				return outline(asString(args.get("path")));
			case "read_symbol":
				return readSymbol(asString(args.get("path")), asString(args.get("symbol")));
			case "search_text":
				return searchText(asString(args.get("query")), asString(args.get("glob")),
						asBool(args.get("regex")), asInt(args.get("context")));
			case "find_files":
				return findFiles(asString(args.get("glob")));
			case "semantic_search":
				return retriever == null ? "코드 색인이 없습니다(뷰의 [색인] 버튼으로 생성하세요)."
						: safe(retriever.search(asString(args.get("query"))));
			case "create_file":
				return createFile(asString(args.get("path")), asString(args.get("content")));
			case "apply_edit":
				return applyEdit(asString(args.get("path")), asString(args.get("old_text")),
						asString(args.get("new_text")), asBool(args.get("all")));
			case "replace_lines":
				return replaceLines(asString(args.get("path")), asInt(args.get("start_line")),
						asInt(args.get("end_line")), asString(args.get("new_text")));
			case "write_file":
				return writeFile(asString(args.get("path")), asString(args.get("content")));
			case "delete_file":
				return deleteFile(asString(args.get("path")));
			case "move_file":
				return moveFile(asString(args.get("from")), asString(args.get("to")));
			case "update_plan":
				return updatePlan(args.get("steps"));
			case "run_command":
				return runCommand(asString(args.get("command")));
			case "get_problems":
				return env == null ? ENV_NA : safe(env.getProblems());
			case "get_console":
				return env == null ? ENV_NA : safe(env.getConsole());
			case "list_servers":
				return env == null ? ENV_NA : safe(env.listServers());
			case "start_server":
				return controlServer(asString(args.get("name")), true);
			case "stop_server":
				return controlServer(asString(args.get("name")), false);
			default:
				return "알 수 없는 도구: " + name;
			}
		} catch (Exception e) {
			return "도구 오류: " + e.getMessage();
		}
	}

	private File resolve(String rel) throws IOException {
		String rootPath = root.getCanonicalPath();
		if (rel == null || rel.isEmpty() || rel.equals(".") || rel.equals("./")) {
			return root.getCanonicalFile();
		}
		File f = new File(root, rel).getCanonicalFile();
		if (!f.getPath().equals(rootPath) && !f.getPath().startsWith(rootPath + File.separator)) {
			throw new IOException("프로젝트 밖 경로 접근 거부: " + rel);
		}
		return f;
	}

	private String listFiles(String rel) throws IOException {
		File dir = resolve(rel);
		if (!dir.exists()) {
			return "경로가 없습니다: " + rel;
		}
		StringBuilder sb = new StringBuilder();
		int[] count = { 0 };
		Path rootPath = root.getCanonicalFile().toPath();
		listRec(dir, rootPath, sb, count, 0);
		if (count[0] >= MAX_LIST) {
			sb.append("...(이하 생략)\n");
		}
		return sb.length() == 0 ? "(빈 디렉터리)" : sb.toString();
	}

	private void listRec(File f, Path rootPath, StringBuilder sb, int[] count, int depth) {
		if (count[0] >= MAX_LIST || depth > 10) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids == null) {
			return;
		}
		Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		for (File k : kids) {
			if (isIgnored(k.getName())) {
				continue;
			}
			if (count[0] >= MAX_LIST) {
				return;
			}
			String relp = rootPath.relativize(k.toPath()).toString().replace('\\', '/');
			sb.append(k.isDirectory() ? relp + "/\n" : relp + "\n");
			count[0]++;
			if (k.isDirectory()) {
				listRec(k, rootPath, sb, count, depth + 1);
			}
		}
	}

	/** 실행 시작 시 1회 주입할 얕고(깊이≤3) 작은(≤STRUCT_MAX) 프로젝트 구조. 실패하면 null. */
	private String projectStructure() {
		try {
			StringBuilder sb = new StringBuilder();
			int[] count = { 0 };
			Path rootPath = root.getCanonicalFile().toPath();
			structRec(root.getCanonicalFile(), rootPath, sb, count, 0);
			if (count[0] >= STRUCT_MAX) {
				sb.append("...(이하 생략)\n");
			}
			return sb.toString();
		} catch (Exception e) {
			return null;
		}
	}

	private void structRec(File f, Path rootPath, StringBuilder sb, int[] count, int depth) {
		if (count[0] >= STRUCT_MAX || depth > 3) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids == null) {
			return;
		}
		Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		for (File k : kids) {
			if (isIgnored(k.getName()) || count[0] >= STRUCT_MAX) {
				continue;
			}
			String relp = rootPath.relativize(k.toPath()).toString().replace('\\', '/');
			sb.append(k.isDirectory() ? relp + "/\n" : relp + "\n");
			count[0]++;
			if (k.isDirectory()) {
				structRec(k, rootPath, sb, count, depth + 1);
			}
		}
	}

	private static boolean isIgnored(String name) {
		return name.equals(".git") || name.equals("target") || name.equals("node_modules") || name.equals("bin")
				|| name.equals(".settings") || name.equals(".metadata") || name.equals(".svn");
	}

	private String readFile(String rel, int start, int end) throws IOException {
		File f = resolve(rel);
		if (!f.isFile()) {
			return "파일이 없습니다: " + rel;
		}
		String content = read(f);
		if (start > 0 || end > 0) {
			String[] lines = content.split("\n", -1);
			int s = start > 0 ? start : 1;
			int e = end > 0 ? Math.min(end, lines.length) : lines.length;
			if (s > lines.length) {
				return "시작 줄(" + s + ")이 파일 길이(" + lines.length + "줄)를 초과합니다.";
			}
			StringBuilder sb = new StringBuilder();
			sb.append("(").append(rel).append(" 줄 ").append(s).append("-").append(e).append(")\n");
			for (int i = s - 1; i < e; i++) {
				sb.append(i + 1).append(": ").append(lines[i]).append('\n');
			}
			String out = sb.toString();
			return out.length() > MAX_READ ? out.substring(0, MAX_READ) + "\n...(생략)" : out;
		}
		if (content.length() > MAX_READ) {
			content = content.substring(0, MAX_READ)
					+ "\n...(파일이 길어 일부만 표시됨. start_line/end_line 으로 범위 지정 가능)";
		}
		return content;
	}

	private String outline(String rel) throws IOException {
		File f = resolve(rel);
		if (!f.isFile()) {
			return "파일이 없습니다: " + rel;
		}
		String out = SymbolReader.outline(read(f));
		return out.isEmpty() ? "(인식된 심볼이 없습니다: " + rel + ")" : out;
	}

	private String readSymbol(String rel, String symbol) throws IOException {
		if (symbol == null || symbol.isEmpty()) {
			return "symbol 이 필요합니다";
		}
		File f = resolve(rel);
		if (!f.isFile()) {
			return "파일이 없습니다: " + rel;
		}
		String body = SymbolReader.read(read(f), symbol);
		if (body == null) {
			return "심볼을 찾지 못했습니다: " + symbol + " (outline 으로 정의 목록을 확인하세요)";
		}
		return body.length() > MAX_READ ? body.substring(0, MAX_READ) + "\n...(생략)" : body;
	}

	private String searchText(String query, String glob, boolean regex, int context) throws IOException {
		if (query == null || query.isEmpty()) {
			return "query 가 필요합니다";
		}
		if (regex) {
			try {
				java.util.regex.Pattern.compile(query);
			} catch (java.util.regex.PatternSyntaxException e) {
				return "정규식 오류: " + e.getMessage();
			}
		}
		int ctx = context > 0 ? Math.min(context, 5) : 0;
		StringBuilder sb = new StringBuilder();
		int[] count = { 0 };
		Path rootPath = root.getCanonicalFile().toPath();
		searchRec(root.getCanonicalFile(), rootPath, query, glob, regex, ctx, sb, count, 0);
		if (count[0] == 0) {
			return "일치하는 내용이 없습니다: " + query + (glob != null && !glob.isEmpty() ? " (glob=" + glob + ")" : "");
		}
		if (count[0] >= MAX_SEARCH) {
			sb.append("...(이하 생략)\n");
		}
		return sb.toString();
	}

	private void searchRec(File f, Path rootPath, String query, String glob, boolean regex, int context,
			StringBuilder sb, int[] count, int depth) {
		if (count[0] >= MAX_SEARCH || depth > 10) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids == null) {
			return;
		}
		for (File k : kids) {
			if (isIgnored(k.getName()) || count[0] >= MAX_SEARCH) {
				continue;
			}
			if (k.isDirectory()) {
				searchRec(k, rootPath, query, glob, regex, context, sb, count, depth + 1);
			} else if (k.isFile() && k.length() <= 1_000_000) {
				String relp = rootPath.relativize(k.toPath()).toString().replace('\\', '/');
				if (glob != null && !glob.isEmpty() && !GlobMatcher.matches(glob, relp)) {
					continue;
				}
				try {
					String content = read(k);
					if (content.indexOf('\0') >= 0) {
						continue; // 바이너리 추정
					}
					List<String> hits = TextSearch.search(content, query, regex, context, MAX_SEARCH - count[0]);
					for (String h : hits) {
						if (h.equals("--")) {
							sb.append("--\n");
							continue;
						}
						sb.append(relp).append(':').append(h).append('\n');
						if (TextSearch.isMatchLine(h)) { // 일치 줄만 카운트(컨텍스트 '-'/구분 '--' 제외)
							count[0]++;
						}
					}
				} catch (IOException ignore) {
					// 읽기 실패 파일 건너뜀
				}
			}
		}
	}

	private String findFiles(String glob) throws IOException {
		if (glob == null || glob.isEmpty()) {
			return "glob 이 필요합니다(예: *Service.java)";
		}
		StringBuilder sb = new StringBuilder();
		int[] count = { 0 };
		Path rootPath = root.getCanonicalFile().toPath();
		findRec(root.getCanonicalFile(), rootPath, glob, sb, count, 0);
		if (count[0] == 0) {
			return "일치하는 파일이 없습니다: " + glob;
		}
		if (count[0] >= MAX_LIST) {
			sb.append("...(이하 생략)\n");
		}
		return sb.toString();
	}

	private void findRec(File f, Path rootPath, String glob, StringBuilder sb, int[] count, int depth) {
		if (count[0] >= MAX_LIST || depth > 12) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids == null) {
			return;
		}
		Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		for (File k : kids) {
			if (isIgnored(k.getName()) || count[0] >= MAX_LIST) {
				continue;
			}
			if (k.isDirectory()) {
				findRec(k, rootPath, glob, sb, count, depth + 1);
			} else if (k.isFile()) {
				String relp = rootPath.relativize(k.toPath()).toString().replace('\\', '/');
				if (GlobMatcher.matches(glob, relp)) {
					sb.append(relp).append('\n');
					count[0]++;
				}
			}
		}
	}

	private String deleteFile(String rel) throws IOException {
		File f = resolve(rel);
		if (!f.isFile()) {
			return "파일이 없습니다: " + rel;
		}
		String old = read(f);
		if (!confirm.ask("Ollama Agent — 파일 삭제 확인", "삭제할 파일: " + rel + "\n\n[내용 미리보기]\n" + clip(old))) {
			return "사용자가 삭제를 취소했습니다: " + rel;
		}
		Files.delete(f.toPath());
		edited = true;
		appliedChanges.add(new FileChange(rel, old, "")); // 되돌리면 내용 복원
		return "파일 삭제 완료: " + rel;
	}

	private String moveFile(String fromRel, String toRel) throws IOException {
		if (fromRel == null || fromRel.isEmpty() || toRel == null || toRel.isEmpty()) {
			return "from/to 경로가 필요합니다";
		}
		File from = resolve(fromRel);
		File to = resolve(toRel);
		if (!from.isFile()) {
			return "원본 파일이 없습니다: " + fromRel;
		}
		if (to.exists()) {
			return "대상이 이미 존재합니다: " + toRel;
		}
		String content = read(from);
		if (!confirm.ask("Ollama Agent — 파일 이동 확인", fromRel + "\n  → " + toRel)) {
			return "사용자가 이동을 취소했습니다.";
		}
		File parent = to.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		Files.move(from.toPath(), to.toPath());
		edited = true;
		appliedChanges.add(new FileChange(fromRel, content, "")); // 원본 복원용(되돌리면 내용 복원)
		appliedChanges.add(FileChange.created(toRel, content)); // 대상 생성(되돌리면 삭제)
		return "이동 완료: " + fromRel + " → " + toRel;
	}

	private String updatePlan(Object stepsObj) {
		if (!(stepsObj instanceof List) || ((List<?>) stepsObj).isEmpty()) {
			return "steps(배열)가 필요합니다";
		}
		String rendered = PlanRenderer.render((List<?>) stepsObj);
		if (rendered.isEmpty()) {
			return "표시할 단계가 없습니다";
		}
		log.progress(rendered);
		return "계획을 갱신했습니다(" + ((List<?>) stepsObj).size() + "단계).";
	}

	private String createFile(String rel, String content) throws IOException {
		if (content == null) {
			content = "";
		}
		File f = resolve(rel);
		if (f.exists()) {
			return "이미 존재합니다(수정은 apply_edit/write_file 사용): " + rel;
		}
		if (!confirm.ask("Ollama Agent — 파일 생성 확인",
				"새 파일: " + rel + "\n\n[내용 미리보기]\n" + clip(content))) {
			return "사용자가 생성을 취소했습니다: " + rel;
		}
		write(f, content);
		edited = true;
		appliedChanges.add(FileChange.created(rel, content)); // 되돌리면 삭제
		return "파일 생성 완료: " + rel + " (" + content.length() + " chars)";
	}

	private String applyEdit(String rel, String oldText, String newText, boolean all) throws IOException {
		if (oldText == null || oldText.isEmpty()) {
			return "old_text 가 필요합니다";
		}
		if (newText == null) {
			newText = "";
		}
		File f = resolve(rel);
		if (!f.isFile()) {
			return "파일이 없습니다: " + rel;
		}
		String content = read(f);

		// all=true: 정확 일치 전부 교체
		if (all) {
			int count = EditMatch.countExact(content, oldText);
			if (count == 0) {
				String hint = EditMatch.nearestHint(content, oldText);
				return "old_text 를 파일에서 찾지 못했습니다(all 교체는 정확 일치만 지원). read_file 로 확인하세요."
						+ (hint != null ? "\n" + hint : "");
			}
			String updated = content.replace(oldText, newText);
			if (!confirm.ask("Ollama Agent — 다중 수정 확인",
					"파일: " + rel + "  (" + count + "곳 일괄 교체)\n\n" + TextDiff.unified(oldText, newText))) {
				return "사용자가 수정을 취소했습니다: " + rel;
			}
			write(f, updated);
			edited = true;
			appliedChanges.add(new FileChange(rel, content, updated));
			return "부분 수정 완료: " + rel + " (" + count + "곳 교체)";
		}

		EditMatch.Result m = EditMatch.find(content, oldText);
		if (m == null) {
			String hint = EditMatch.nearestHint(content, oldText);
			return "old_text 를 파일에서 찾지 못했습니다. read_file 로 정확한 내용(공백/들여쓰기 포함)을 확인하세요."
					+ (hint != null ? "\n" + hint : "");
		}
		if ("exact".equals(m.mode) && EditMatch.hasDuplicateExact(content, oldText)) {
			return "old_text 가 여러 곳과 일치합니다. 더 길고 유일한 범위를 지정하거나 all=true 로 일괄 교체하세요.";
		}
		if (!"exact".equals(m.mode) && m.ambiguous) {
			return "보정 매칭(공백/들여쓰기 무시)이 여러 곳과 일치합니다. old_text 를 더 길고 유일하게 지정하세요.";
		}
		String matched = content.substring(m.start, m.end);
		String updated = content.substring(0, m.start) + newText + content.substring(m.end);
		String note = "exact".equals(m.mode) ? "" : "  (공백/들여쓰기 보정 매칭)";
		if (!confirm.ask("Ollama Agent — 부분 수정 확인",
				"파일: " + rel + note + "\n\n" + TextDiff.unified(matched, newText))) {
			return "사용자가 수정을 취소했습니다: " + rel;
		}
		write(f, updated);
		edited = true;
		appliedChanges.add(new FileChange(rel, content, updated));
		return "부분 수정 완료: " + rel + ("exact".equals(m.mode) ? "" : " (보정 매칭)");
	}

	private String replaceLines(String rel, int start, int end, String newText) throws IOException {
		if (start < 1 || end < start) {
			return "start_line/end_line 이 올바르지 않습니다(1부터, start<=end).";
		}
		File f = resolve(rel);
		if (!f.isFile()) {
			return "파일이 없습니다: " + rel;
		}
		String content = read(f);
		String updated = LineEdit.replace(content, start, end, newText == null ? "" : newText);
		if (updated == null) {
			int total = content.split("\n", -1).length;
			return "줄 범위를 적용할 수 없습니다(파일은 " + total + "줄). read_file 로 줄번호를 확인하세요.";
		}
		String before = sliceLines(content, start, end);
		if (!confirm.ask("Ollama Agent — 줄 범위 수정 확인",
				"파일: " + rel + "  (줄 " + start + "-" + end + ")\n\n" + TextDiff.unified(before, newText == null ? "" : newText))) {
			return "사용자가 수정을 취소했습니다: " + rel;
		}
		write(f, updated);
		edited = true;
		appliedChanges.add(new FileChange(rel, content, updated));
		return "줄 범위 수정 완료: " + rel + " (줄 " + start + "-" + end + ")";
	}

	/** content 의 1-based [start,end] 줄을 추출(diff 표시용). */
	private static String sliceLines(String content, int start, int end) {
		String[] lines = content.split("\n", -1);
		int s = Math.max(1, start);
		int e = Math.min(end, lines.length);
		StringBuilder sb = new StringBuilder();
		for (int i = s - 1; i < e; i++) {
			sb.append(lines[i]);
			if (i < e - 1) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	private String writeFile(String rel, String content) throws IOException {
		if (content == null) {
			content = "";
		}
		File f = resolve(rel);
		String old = f.isFile() ? read(f) : "";
		String head = old.isEmpty() ? "[새 파일 생성]\n" : "[기존 파일 전체 덮어쓰기]\n";
		String detail = old.isEmpty() ? clip(content) : TextDiff.unified(old, content);
		if (!confirm.ask("Ollama Agent — 파일 저장 확인", head + rel + "\n\n" + detail)) {
			return "사용자가 저장을 취소했습니다: " + rel;
		}
		write(f, content);
		edited = true;
		appliedChanges.add(new FileChange(rel, old, content));
		return "저장 완료: " + rel + " (" + content.length() + " chars)";
	}

	private String runCommand(String command) throws IOException, InterruptedException {
		if (!enableRun) {
			return "명령 실행이 비활성화되어 있습니다(Preferences > Ollama Assist 에서 활성화).";
		}
		if (command == null || command.trim().isEmpty()) {
			return "command 가 필요합니다";
		}
		if (!confirm.ask("Ollama Agent — 명령 실행 확인", "작업 폴더: " + root.getName() + "\n\n$ " + command)) {
			return "사용자가 명령 실행을 취소했습니다.";
		}
		return execShell(command);
	}

	/** 쉘 명령 실행(확인 없이). 반환 첫 줄은 "exit=N". */
	private String execShell(String command) throws IOException, InterruptedException {
		boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
		ProcessBuilder pb = win ? new ProcessBuilder("cmd", "/c", command)
				: new ProcessBuilder("sh", "-c", command);
		pb.directory(root);
		pb.redirectErrorStream(true);
		Process proc = pb.start();
		StringBuilder out = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
			String l;
			while ((l = br.readLine()) != null) {
				out.append(l).append('\n');
				if (out.length() > MAX_READ) {
					out.append("...(출력 생략)\n");
					break;
				}
			}
		}
		boolean done = proc.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
		if (!done) {
			proc.destroyForcibly();
			out.append("[시간 초과 ").append(CMD_TIMEOUT_SEC).append("초로 종료]");
		}
		int exit = done ? proc.exitValue() : -1;
		if (env != null) {
			env.console("$ " + command + "\n" + out + "[exit=" + exit + "]\n\n");
		}
		return "exit=" + exit + "\n" + out;
	}

	/**
	 * 자동 검증. 통과/검증불가면 null, 실패면 모델에 전달할 문제 텍스트를 반환.
	 * 1순위: 검증 명령(verifyCommand, enableRun 필요), 2순위: Eclipse Problems.
	 */
	private String autoVerify() {
		if (verifyCommand != null && !verifyCommand.trim().isEmpty() && enableRun) {
			try {
				log.progress("\n🔎 검증 실행: " + verifyCommand + "\n");
				String out = execShell(verifyCommand);
				if (out.startsWith("exit=0")) {
					return null;
				}
				String focused = VerifyReport.focusTestLog(out, 40); // 실패/예외 줄만 정제
				return "검증 명령 실패(아래 실패 내용을 해결하세요):\n" + (focused != null ? focused : out);
			} catch (Exception e) {
				return "검증 명령 오류: " + e.getMessage();
			}
		}
		if (env != null) {
			log.progress("\n🔎 Problems 검증 중…\n");
			return VerifyReport.focusErrors(env.getProblems(), 30, changedHints()); // 변경 파일 우선, 오류만
		}
		return null;
	}

	/** 이번 실행에서 변경한 파일들의 파일명(검증 오류 우선순위 힌트). */
	private java.util.Set<String> changedHints() {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (FileChange c : appliedChanges) {
			String rel = c.path;
			int slash = Math.max(rel.lastIndexOf('/'), rel.lastIndexOf('\\'));
			out.add(slash >= 0 ? rel.substring(slash + 1) : rel);
		}
		return out;
	}

	private String controlServer(String name, boolean start) {
		if (env == null) {
			return ENV_NA;
		}
		if (name == null || name.trim().isEmpty()) {
			return "name 이 필요합니다(list_servers 로 서버 이름 확인)";
		}
		String action = start ? "기동" : "중지";
		if (!confirm.ask("Ollama Agent — 서버 " + action + " 확인", "서버 '" + name + "' 를 " + action + "합니다.")) {
			return "사용자가 서버 " + action + "을 취소했습니다.";
		}
		return safe(start ? env.startServer(name) : env.stopServer(name));
	}

	private static String safe(String s) {
		return s == null ? "(결과 없음)" : s;
	}

	// ===================== 파일 IO 헬퍼 =====================

	private String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	private void write(File f, String content) throws IOException {
		File parent = f.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	// ===================== 기타 헬퍼 =====================

	private static String clip(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 1200 ? s.substring(0, 1200) + "\n...(미리보기 생략)" : s;
	}

	private static String firstLine(String s) {
		if (s == null) {
			return "";
		}
		int nl = s.indexOf('\n');
		String line = nl >= 0 ? s.substring(0, nl) : s;
		return line.length() > 120 ? line.substring(0, 120) + "…" : line;
	}

	private static Map<String, Object> msg(String role, String content) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("role", role);
		m.put("content", content);
		return m;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object o) {
		return (Map<String, Object>) o;
	}

	private static String asString(Object o) {
		return o == null ? null : o.toString();
	}

	private static boolean asBool(Object o) {
		if (o instanceof Boolean) {
			return (Boolean) o;
		}
		return o != null && "true".equalsIgnoreCase(o.toString());
	}

	private static int asInt(Object o) {
		if (o instanceof Number) {
			return ((Number) o).intValue();
		}
		if (o instanceof String) {
			try {
				return (int) Double.parseDouble(((String) o).trim());
			} catch (NumberFormatException ignore) {
				return -1;
			}
		}
		return -1;
	}

	/**
	 * 프로젝트 규칙 파일(AGENTS.md → .ollama-assist.md 순)을 읽어 반환. 없으면 null.
	 * 채팅/에이전트 시스템 프롬프트에 주입해 사내 컨벤션을 따르게 한다.
	 */
	public static String readProjectRules(File root) {
		if (root == null) {
			return null;
		}
		String[] candidates = { "AGENTS.md", ".ollama-assist.md" };
		for (String name : candidates) {
			File f = new File(root, name);
			if (f.isFile()) {
				try {
					String c = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
					return c.length() > 8000 ? c.substring(0, 8000) + "\n...(생략)" : c;
				} catch (IOException ignore) {
					return null;
				}
			}
		}
		return null;
	}

	private static Map<String, Object> toArgs(Object o) {
		if (o instanceof Map) {
			return castMap(o);
		}
		if (o instanceof String) {
			try {
				Object p = Json.parse((String) o);
				if (p instanceof Map) {
					return castMap(p);
				}
			} catch (Exception ignore) {
				// 무시
			}
		}
		return new LinkedHashMap<>();
	}

	private static String briefArgs(Map<String, Object> args) {
		if (args == null || args.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> e : args.entrySet()) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			String v = asString(e.getValue());
			if (v != null && v.length() > 40) {
				v = v.substring(0, 40) + "…";
			}
			sb.append(e.getKey()).append("=").append(v);
		}
		return sb.toString();
	}
}
