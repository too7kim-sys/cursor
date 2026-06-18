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
			this.label = label == null ? "" : label;
			this.time = System.currentTimeMillis();
			this.edits = edits;
		}

		public int fileCount() {
			return edits == null ? 0 : edits.size();
		}
	}

	private final List<Checkpoint> cps = new ArrayList<>();

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
		}
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
}
