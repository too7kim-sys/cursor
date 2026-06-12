package com.egov.ollama.assist;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Continue 의 Agent 와 유사한 도구 호출 루프.
 * <p>
 * 모델에게 list_files / read_file / write_file 도구를 제공하고, 모델이 반환한 tool_calls 를
 * 워크스페이스에 대해 실행한 뒤 결과를 다시 모델에 전달하는 과정을 반복한다.
 * 파일 수정(write_file)은 사용자 확인 콜백을 거친다. 모든 파일 접근은 프로젝트 루트로 제한된다.
 */
public class OllamaAgent {

	/** 진행 상황/응답 출력 콜백(스레드 무관, 구현체에서 UI 스레드로 보내야 함) */
	public interface Logger {
		void log(String text);
	}

	/** 파일 수정 확인 콜백. true 면 적용. */
	public interface WriteConfirm {
		boolean confirm(String relPath, String oldContent, String newContent);
	}

	private static final int MAX_ITER = 12;
	private static final int MAX_LIST = 300;
	private static final int MAX_READ = 60000;

	/** 모델에 제공할 도구 정의(OpenAI/Ollama function 형식) */
	private static final String TOOLS = "["
			+ "{\"type\":\"function\",\"function\":{\"name\":\"list_files\",\"description\":\"프로젝트 루트 기준 상대 경로의 파일/폴더 목록을 재귀적으로 반환\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"상대 경로(기본 '.')\"}}}}},"
			+ "{\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"description\":\"파일 내용을 읽어 반환\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}},"
			+ "{\"type\":\"function\",\"function\":{\"name\":\"write_file\",\"description\":\"파일을 새 내용으로 덮어쓴다(사용자 확인 후 적용). 수정 시 전체 내용을 제공할 것\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}}}"
			+ "]";

	private final String base;
	private final String model;
	private final String system;
	private final File root;
	private final Logger log;
	private final WriteConfirm confirm;

	public OllamaAgent(String base, String model, String system, File root, Logger log, WriteConfirm confirm) {
		this.base = base;
		this.model = model;
		this.system = system;
		this.root = root;
		this.log = log;
		this.confirm = confirm;
	}

	public void run(String userPrompt) throws IOException {
		List<Object> messages = new ArrayList<>();
		if (system != null && !system.trim().isEmpty()) {
			messages.add(msg("system", system));
		}
		messages.add(msg("system",
				"당신은 코드 작업 에이전트입니다. 제공된 도구(list_files, read_file, write_file)로 프로젝트 파일을 직접 "
						+ "탐색하고 수정하세요. 추측하지 말고 read_file 로 실제 내용을 확인한 뒤, 수정은 반드시 write_file 로 전체 "
						+ "파일 내용을 작성하세요. 작업이 끝나면 무엇을 했는지 한국어로 요약하세요."));
		messages.add(msg("user", userPrompt));

		for (int iter = 0; iter < MAX_ITER; iter++) {
			String body = "{\"model\":" + JsonUtil.quote(model)
					+ ",\"messages\":" + Json.write(messages)
					+ ",\"tools\":" + TOOLS
					+ ",\"stream\":false}";

			log.log("\n(모델 응답 생성 중… #" + (iter + 1) + ")\n");
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
			messages.add(message); // assistant 메시지(tool_calls 포함) 그대로 보존

			List<?> toolCalls = (message.get("tool_calls") instanceof List) ? (List<?>) message.get("tool_calls")
					: null;
			String content = asString(message.get("content"));

			if (toolCalls == null || toolCalls.isEmpty()) {
				if (content != null && !content.isEmpty()) {
					log.log(content);
				}
				log.log("\n\n[완료]\n");
				return;
			}

			for (Object tco : toolCalls) {
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

				log.log("\n🔧 " + name + "(" + briefArgs(args) + ")\n");
				String result = executeTool(name, args);

				Map<String, Object> toolMsg = new LinkedHashMap<>();
				toolMsg.put("role", "tool");
				toolMsg.put("tool_name", name);
				toolMsg.put("content", result);
				messages.add(toolMsg);
			}
		}
		log.log("\n[안내] 최대 반복 횟수(" + MAX_ITER + ")에 도달해 중단했습니다.\n");
	}

	// ===================== 도구 실행 =====================

	private String executeTool(String name, Map<String, Object> args) {
		try {
			switch (name == null ? "" : name) {
			case "list_files":
				return listFiles(asString(args.get("path")));
			case "read_file":
				return readFile(asString(args.get("path")));
			case "write_file":
				return writeFile(asString(args.get("path")), asString(args.get("content")));
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
			sb.append("...(이하 생략, ").append(MAX_LIST).append("개 초과)\n");
		}
		return sb.length() == 0 ? "(빈 디렉터리)" : sb.toString();
	}

	private void listRec(File f, Path rootPath, StringBuilder sb, int[] count, int depth) {
		if (count[0] >= MAX_LIST || depth > 8) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids == null) {
			return;
		}
		Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		for (File k : kids) {
			String n = k.getName();
			if (n.equals(".git") || n.equals("target") || n.equals("node_modules") || n.equals("bin")
					|| n.equals(".settings") || n.equals(".metadata")) {
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

	private String readFile(String rel) throws IOException {
		File f = resolve(rel);
		if (!f.isFile()) {
			return "파일이 없습니다: " + rel;
		}
		String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		if (content.length() > MAX_READ) {
			content = content.substring(0, MAX_READ) + "\n...(파일이 길어 일부만 표시됨)";
		}
		return content;
	}

	private String writeFile(String rel, String content) throws IOException {
		if (content == null) {
			content = "";
		}
		File f = resolve(rel);
		String old = f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
		boolean ok = (confirm == null) || confirm.confirm(rel, old, content);
		if (!ok) {
			return "사용자가 수정을 취소했습니다: " + rel;
		}
		File parent = f.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
		return "작성 완료: " + rel + " (" + content.length() + " chars)";
	}

	// ===================== 헬퍼 =====================

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
