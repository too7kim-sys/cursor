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
	private static final int MAX_LIST = 400;
	private static final int MAX_READ = 60000;
	private static final int MAX_SEARCH = 100;
	private static final int CMD_TIMEOUT_SEC = 120;

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
	/** 이번 실행에서 적용한 변경 기록: 각 항목 {상대경로, 변경 전 내용, 변경 후 내용}. */
	private final java.util.List<String[]> appliedChanges = new java.util.ArrayList<>();

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
		messages.add(msg("user", userPrompt));
		int verifyRounds = 0;

		for (int iter = 0; iter < MAX_ITER; iter++) {
			if (isCancelled()) {
				log.progress("\n[중지됨]\n");
				return;
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
				log.progress("\n[오류] 예상치 못한 응답 형식\n");
				return;
			}
			Map<?, ?> rootMap = (Map<?, ?>) parsed;
			if (rootMap.get("error") != null) {
				log.progress("\n[오류] " + rootMap.get("error") + "\n");
				return;
			}
			Object msgO = rootMap.get("message");
			if (!(msgO instanceof Map)) {
				log.progress("\n[오류] 응답에 message 가 없습니다\n");
				return;
			}
			Map<String, Object> message = castMap(msgO);
			messages.add(message);

			List<?> toolCalls = (message.get("tool_calls") instanceof List) ? (List<?>) message.get("tool_calls")
					: null;
			String content = asString(message.get("content"));

			if (toolCalls == null || toolCalls.isEmpty()) {
				// 자동 검증 루프: 수정이 있었고 검증 수단이 있으면 빌드/오류를 확인해 실패 시 재수정
				if (edited && verifyRounds < MAX_VERIFY && !isCancelled()) {
					String verdict = autoVerify();
					if (verdict != null) {
						verifyRounds++;
						log.progress("\n🔁 자동 검증 실패 — 수정 재시도 (" + verifyRounds + "/" + MAX_VERIFY + ")\n");
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
					log.progress("\n[중지됨]\n");
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

				log.progress("\n🔧 " + name + "(" + briefArgs(args) + ")\n");
				String result = executeTool(name, args);
				log.progress("   ↳ " + firstLine(result) + "\n");

				Map<String, Object> toolMsg = new LinkedHashMap<>();
				toolMsg.put("role", "tool");
				toolMsg.put("tool_name", name);
				toolMsg.put("content", result);
				messages.add(toolMsg);
			}
		}
		log.progress("\n[안내] 최대 반복 횟수(" + MAX_ITER + ")에 도달해 중단했습니다.\n");
	}

	private boolean isCancelled() {
		return cancelled != null && cancelled.getAsBoolean();
	}

	private String agentSystemPrompt() {
		StringBuilder sb = new StringBuilder();
		sb.append("당신은 숙련된 코드 작업 에이전트입니다. 제공된 도구로 프로젝트를 직접 탐색·수정하세요.\n");
		sb.append("작업 원칙:\n");
		sb.append("1) 추측하지 말고 search_text(정확한 키워드)/semantic_search(의미 기반)/list_files/read_file 로 실제 코드를 먼저 확인한다.\n");
		if (retriever != null) {
			sb.append("   - 어디를 봐야 할지 모호하면 semantic_search 로 관련 코드를 먼저 찾는다(프로젝트 패턴을 따른다).\n");
		}
		sb.append("2) 기존 파일 수정은 가능한 한 apply_edit(부분 수정)을 사용한다. old_text 는 파일에서 유일하게 식별되는 충분한 길이로 제시한다.\n");
		sb.append("3) 새 파일은 create_file 로 만든다. 파일 전체를 바꿔야 할 때만 write_file 을 쓴다.\n");
		if (enableRun) {
			sb.append("4) 필요 시 run_command 로 빌드/테스트를 실행해 결과를 확인한다.\n");
		}
		if (env != null) {
			sb.append("5) 컴파일 오류 수정 요청 시 get_problems 로 실제 오류 목록을 먼저 확인하고, ")
					.append("실행/빌드 로그는 get_console 로 확인한다. 서버는 list_servers/start_server/stop_server 로 다룬다.\n");
		}
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
		p.put("query", prop("string", "찾을 문자열"));
		tools.add(func("search_text", "프로젝트 전체에서 문자열을 검색해 파일:줄 위치를 반환", p, Arrays.asList("query")));

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
		tools.add(func("apply_edit", "파일에서 old_text 를 찾아 new_text 로 한 번만 교체(부분 수정). 사용자 확인 후 적용",
				p, Arrays.asList("path", "old_text", "new_text")));

		p = new LinkedHashMap<>();
		p.put("path", prop("string", "파일 경로"));
		p.put("content", prop("string", "전체 새 내용"));
		tools.add(func("write_file", "파일을 새 내용으로 전체 덮어쓰기. 사용자 확인 후 적용", p, Arrays.asList("path", "content")));

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

	/** 이번 실행에서 적용된 변경 목록(각 {상대경로, 변경 전, 변경 후}). 검토/되돌리기용. */
	public java.util.List<String[]> getAppliedChanges() {
		return appliedChanges;
	}

	private String executeTool(String name, Map<String, Object> args) {
		try {
			switch (name == null ? "" : name) {
			case "list_files":
				return listFiles(asString(args.get("path")));
			case "read_file":
				return readFile(asString(args.get("path")), asInt(args.get("start_line")), asInt(args.get("end_line")));
			case "search_text":
				return searchText(asString(args.get("query")));
			case "semantic_search":
				return retriever == null ? "코드 색인이 없습니다(뷰의 [색인] 버튼으로 생성하세요)."
						: safe(retriever.search(asString(args.get("query"))));
			case "create_file":
				return createFile(asString(args.get("path")), asString(args.get("content")));
			case "apply_edit":
				return applyEdit(asString(args.get("path")), asString(args.get("old_text")),
						asString(args.get("new_text")));
			case "write_file":
				return writeFile(asString(args.get("path")), asString(args.get("content")));
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

	private String searchText(String query) throws IOException {
		if (query == null || query.isEmpty()) {
			return "query 가 필요합니다";
		}
		StringBuilder sb = new StringBuilder();
		int[] count = { 0 };
		Path rootPath = root.getCanonicalFile().toPath();
		searchRec(root.getCanonicalFile(), rootPath, query, sb, count, 0);
		if (count[0] == 0) {
			return "일치하는 내용이 없습니다: " + query;
		}
		if (count[0] >= MAX_SEARCH) {
			sb.append("...(이하 생략)\n");
		}
		return sb.toString();
	}

	private void searchRec(File f, Path rootPath, String query, StringBuilder sb, int[] count, int depth) {
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
				searchRec(k, rootPath, query, sb, count, depth + 1);
			} else if (k.isFile() && k.length() <= 1_000_000) {
				try {
					String content = read(k);
					if (content.indexOf('\0') >= 0) {
						continue; // 바이너리 추정
					}
					String[] lines = content.split("\n", -1);
					for (int i = 0; i < lines.length && count[0] < MAX_SEARCH; i++) {
						if (lines[i].contains(query)) {
							String relp = rootPath.relativize(k.toPath()).toString().replace('\\', '/');
							String line = lines[i].trim();
							if (line.length() > 200) {
								line = line.substring(0, 200) + "…";
							}
							sb.append(relp).append(':').append(i + 1).append(": ").append(line).append('\n');
							count[0]++;
						}
					}
				} catch (IOException ignore) {
					// 읽기 실패 파일 건너뜀
				}
			}
		}
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
		appliedChanges.add(new String[] { rel, "", content });
		return "파일 생성 완료: " + rel + " (" + content.length() + " chars)";
	}

	private String applyEdit(String rel, String oldText, String newText) throws IOException {
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
		int idx = content.indexOf(oldText);
		if (idx < 0) {
			return "old_text 를 파일에서 찾지 못했습니다. read_file 로 정확한 내용(공백/들여쓰기 포함)을 확인하세요.";
		}
		if (content.indexOf(oldText, idx + 1) >= 0) {
			return "old_text 가 여러 곳과 일치합니다. 더 길고 유일한 범위를 지정하세요.";
		}
		String updated = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
		if (!confirm.ask("Ollama Agent — 부분 수정 확인",
				"파일: " + rel + "\n\n" + TextDiff.unified(oldText, newText))) {
			return "사용자가 수정을 취소했습니다: " + rel;
		}
		write(f, updated);
		edited = true;
		appliedChanges.add(new String[] { rel, content, updated });
		return "부분 수정 완료: " + rel;
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
		appliedChanges.add(new String[] { rel, old, content });
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
				return out.startsWith("exit=0") ? null : "검증 명령 실패:\n" + out;
			} catch (Exception e) {
				return "검증 명령 오류: " + e.getMessage();
			}
		}
		if (env != null) {
			log.progress("\n🔎 Problems 검증 중…\n");
			String probs = env.getProblems();
			if (probs == null || probs.startsWith("오류 0개")) {
				return null;
			}
			if (probs.startsWith("오류 ")) {
				return "컴파일 오류/경고가 있습니다:\n" + probs;
			}
		}
		return null;
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
