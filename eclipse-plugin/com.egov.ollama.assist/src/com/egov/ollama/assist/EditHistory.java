package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다단계 되돌리기용 변경 히스토리. Agent 실행 1회 = 체크포인트 1개(여러 파일의 {경로, 변경 전, 변경 후}).
 * 특정 체크포인트 시점 이후를 한 번에 되돌릴 수 있도록 '복원 상태'를 계산한다. SWT 비의존이라 테스트 가능.
 */
public final class EditHistory {

	public static final class Checkpoint {
		public final String label;
		public final long time;
		/** 각 {경로, 변경 전, 변경 후}. */
		public final List<String[]> edits;

		Checkpoint(String label, List<String[]> edits) {
			this(label, edits, System.currentTimeMillis());
		}

		Checkpoint(String label, List<String[]> edits, long time) {
			this.label = label == null ? "" : label;
			this.time = time;
			this.edits = edits;
		}

		public int fileCount() {
			return edits == null ? 0 : edits.size();
		}
	}

	private static final long MAX_BYTES = 4_000_000L; // 저장 내용(전/후) 총 문자 상한(~4MB)
	private static final long DEFAULT_MAX_AGE = 30L * 24 * 60 * 60 * 1000; // 30일

	private final List<Checkpoint> cps = new ArrayList<>();
	private int maxCheckpoints = 30;
	private long maxBytes = MAX_BYTES;
	private long maxAgeMillis = DEFAULT_MAX_AGE;

	public void setMaxCheckpoints(int n) {
		this.maxCheckpoints = Math.max(1, n);
	}

	public void setLimits(int maxCheckpoints, long maxBytes, long maxAgeMillis) {
		this.maxCheckpoints = Math.max(1, maxCheckpoints);
		this.maxBytes = maxBytes;
		this.maxAgeMillis = maxAgeMillis;
	}

	public void clear() {
		cps.clear();
	}

	public int size() {
		return cps.size();
	}

	public Checkpoint get(int i) {
		return cps.get(i);
	}

	public void push(String label, List<String[]> edits) {
		if (edits != null && !edits.isEmpty()) {
			cps.add(new Checkpoint(label, edits));
			prune(System.currentTimeMillis());
		}
	}

	/**
	 * 보관 한도를 넘는 오래된 체크포인트를 앞(가장 오래된 것)부터 제거. 최소 1개는 남긴다.
	 * 1) 개수 상한 2) 총 용량 상한 3) 나이 상한 순으로 정리.
	 */
	public void prune(long now) {
		while (cps.size() > maxCheckpoints) {
			cps.remove(0);
		}
		while (cps.size() > 1 && totalChars() > maxBytes) {
			cps.remove(0);
		}
		if (maxAgeMillis > 0) {
			while (cps.size() > 1 && (now - cps.get(0).time) > maxAgeMillis) {
				cps.remove(0);
			}
		}
	}

	/** 저장 중인 변경 전/후 내용의 총 문자 수(용량 추정). */
	public long totalChars() {
		long n = 0;
		for (Checkpoint cp : cps) {
			for (String[] e : cp.edits) {
				n += (e[1] == null ? 0 : e[1].length()) + (e[2] == null ? 0 : e[2].length());
			}
		}
		return n;
	}

	/**
	 * index..끝 체크포인트들을 되돌릴 때 각 파일을 복원할 내용(경로→변경 전). 같은 파일이 여러 번
	 * 바뀐 경우 가장 이른(=가장 오래된) 변경 전 내용을 사용한다.
	 */
	public Map<String, String> restoreStateFrom(int index) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = Math.max(0, index); i < cps.size(); i++) {
			for (String[] e : cps.get(i).edits) {
				m.putIfAbsent(e[0], e[1]);
			}
		}
		return m;
	}

	/** index 이상(그 체크포인트 포함)을 히스토리에서 제거. */
	public void truncateTo(int index) {
		while (cps.size() > index && cps.size() > 0) {
			cps.remove(cps.size() - 1);
		}
	}

	/** 디스크 영속화용 JSON 직렬화. */
	public String toJson() {
		List<Object> arr = new ArrayList<>();
		for (Checkpoint cp : cps) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("label", cp.label);
			m.put("time", cp.time);
			List<Object> es = new ArrayList<>();
			for (String[] e : cp.edits) {
				List<Object> t = new ArrayList<>();
				t.add(e[0]);
				t.add(e[1]);
				t.add(e[2]);
				es.add(t);
			}
			m.put("edits", es);
			arr.add(m);
		}
		return Json.write(arr);
	}

	/** JSON 에서 복원(기존 내용은 대체). */
	public void loadJson(String json) {
		cps.clear();
		if (json == null || json.trim().isEmpty()) {
			return;
		}
		Object parsed = Json.parse(json);
		if (!(parsed instanceof List)) {
			return;
		}
		for (Object o : (List<?>) parsed) {
			if (!(o instanceof Map)) {
				continue;
			}
			Map<?, ?> m = (Map<?, ?>) o;
			List<String[]> edits = new ArrayList<>();
			Object editsO = m.get("edits");
			if (editsO instanceof List) {
				for (Object eo : (List<?>) editsO) {
					if (eo instanceof List) {
						List<?> t = (List<?>) eo;
						if (t.size() >= 3) {
							edits.add(new String[] { str(t.get(0)), str(t.get(1)), str(t.get(2)) });
						}
					}
				}
			}
			if (!edits.isEmpty()) {
				cps.add(new Checkpoint(str(m.get("label")), edits, asLong(m.get("time"))));
			}
		}
		prune(System.currentTimeMillis()); // 로드 시에도 한도 적용
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}

	private static long asLong(Object o) {
		if (o instanceof Number) {
			return ((Number) o).longValue();
		}
		try {
			return o == null ? 0L : Long.parseLong(o.toString().trim());
		} catch (Exception e) {
			return 0L;
		}
	}
}
