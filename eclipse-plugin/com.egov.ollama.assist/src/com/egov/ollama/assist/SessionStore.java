package com.egov.ollama.assist;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 멀티 대화 세션 저장소. 각 세션을 {@code <id>.json}(이름·대화내용·멀티턴 히스토리) 으로 보관한다.
 * 순수 JDK + {@link Json} 만 사용하므로 단위 테스트가 가능하다.
 */
public final class SessionStore {

	/** 세션 요약(목록용). */
	public static final class Info {
		public final String id;
		public final String name;
		public final long modified;

		public Info(String id, String name, long modified) {
			this.id = id;
			this.name = name;
			this.modified = modified;
		}
	}

	/** 세션 본문. */
	public static final class Session {
		public String name;
		public String transcript;
		public List<Object> history = new ArrayList<>();
	}

	private final File dir;

	public SessionStore(File dir) {
		this.dir = dir;
		dir.mkdirs();
	}

	/** 새 세션 생성 후 id 반환. */
	public String create(String name) {
		String id = "s" + System.currentTimeMillis() + "_" + Math.abs(name == null ? 0 : name.hashCode() % 1000);
		Session s = new Session();
		s.name = (name == null || name.trim().isEmpty()) ? "새 대화" : name.trim();
		save(id, s);
		return id;
	}

	public void save(String id, Session s) {
		if (id == null || s == null) {
			return;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", s.name == null ? "" : s.name);
		m.put("transcript", s.transcript == null ? "" : s.transcript);
		m.put("history", s.history == null ? new ArrayList<>() : s.history);
		try {
			Files.write(file(id).toPath(), Json.write(m).getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			// 저장 실패는 무시(상위에서 로깅)
		}
	}

	public Session load(String id) {
		if (id == null) {
			return null;
		}
		File f = file(id);
		if (!f.isFile()) {
			return null;
		}
		try {
			Object parsed = Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			if (!(parsed instanceof Map)) {
				return null;
			}
			Map<?, ?> m = (Map<?, ?>) parsed;
			Session s = new Session();
			s.name = str(m.get("name"));
			s.transcript = str(m.get("transcript"));
			if (m.get("history") instanceof List) {
				s.history = new ArrayList<>((List<?>) m.get("history"));
			}
			return s;
		} catch (Exception e) {
			return null;
		}
	}

	public void delete(String id) {
		if (id != null) {
			file(id).delete();
		}
	}

	/** 수정시각 내림차순 세션 목록. */
	public List<Info> list() {
		List<Info> out = new ArrayList<>();
		File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !n.equals("current.json"));
		if (files == null) {
			return out;
		}
		for (File f : files) {
			String id = f.getName().substring(0, f.getName().length() - 5);
			String name = id;
			try {
				Object parsed = Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
				if (parsed instanceof Map && ((Map<?, ?>) parsed).get("name") != null) {
					name = str(((Map<?, ?>) parsed).get("name"));
				}
			} catch (Exception ignore) {
				// 이름 파싱 실패 시 id 사용
			}
			out.add(new Info(id, name, f.lastModified()));
		}
		out.sort((a, b) -> Long.compare(b.modified, a.modified));
		return out;
	}

	/** 이름 또는 대화 내용에 query 가 포함된 세션 목록(대소문자 무시). 빈 query 면 전체. */
	public List<Info> search(String query) {
		if (query == null || query.trim().isEmpty()) {
			return list();
		}
		String q = query.toLowerCase();
		List<Info> out = new ArrayList<>();
		for (Info info : list()) {
			boolean match = info.name != null && info.name.toLowerCase().contains(q);
			if (!match) {
				Session s = load(info.id);
				if (s != null && s.transcript != null && s.transcript.toLowerCase().contains(q)) {
					match = true;
				}
			}
			if (match) {
				out.add(info);
			}
		}
		return out;
	}

	/** 현재 세션 id 포인터 읽기/쓰기. */
	public String getCurrent() {
		try {
			File f = new File(dir, "current.txt");
			if (f.isFile()) {
				String id = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
				return id.isEmpty() ? null : id;
			}
		} catch (Exception ignore) {
			// 무시
		}
		return null;
	}

	public void setCurrent(String id) {
		try {
			Files.write(new File(dir, "current.txt").toPath(),
					(id == null ? "" : id).getBytes(StandardCharsets.UTF_8));
		} catch (Exception ignore) {
			// 무시
		}
	}

	private File file(String id) {
		return new File(dir, id + ".json");
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}
}
