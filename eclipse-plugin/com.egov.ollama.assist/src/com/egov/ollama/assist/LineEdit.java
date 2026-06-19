package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;

/**
 * 줄 범위 기반 텍스트 교체(SWT 비의존, 테스트 가능). old_text 를 정확히 재현하기 어려운 약한
 * 모델을 위해, read_file 이 보여준 줄번호로 [start..end] 구간을 통째로 바꾼다.
 */
public final class LineEdit {

	private LineEdit() {
	}

	/**
	 * 1-based 구간 [start, end](양끝 포함)을 replacement 으로 교체한 새 내용을 반환.
	 * replacement 이 비면 해당 구간을 삭제한다. 범위가 잘못되면 null.
	 *
	 * @param start 1-based 시작 줄(>=1)
	 * @param end   1-based 끝 줄(>=start). 파일 길이를 넘으면 끝까지로 본다.
	 */
	public static String replace(String content, int start, int end, String replacement) {
		if (content == null || start < 1 || end < start) {
			return null;
		}
		String[] lines = content.split("\n", -1);
		if (start > lines.length) {
			return null;
		}
		int last = Math.min(end, lines.length); // 1-based, 포함
		List<String> res = new ArrayList<>();
		for (int i = 0; i < start - 1; i++) {
			res.add(lines[i]);
		}
		if (replacement != null && !replacement.isEmpty()) {
			for (String rl : replacement.split("\n", -1)) {
				res.add(rl);
			}
		}
		for (int i = last; i < lines.length; i++) {
			res.add(lines[i]);
		}
		return String.join("\n", res);
	}
}
