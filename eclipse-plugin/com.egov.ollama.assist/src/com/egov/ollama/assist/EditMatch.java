package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;

/**
 * apply_edit 용 매칭. 모델이 준 old_text 가 정확히 일치하지 않을 때(주로 줄 끝 공백/줄바꿈/들여쓰기 차이)
 * 점진적으로 관대하게 찾아 실제 교체 구간[start,end)을 돌려준다. SWT 비의존이라 테스트 가능.
 */
public final class EditMatch {

	public static final class Result {
		public final int start;
		public final int end;
		/** exact=정확 일치, 그 외는 보정 매칭(공백/들여쓰기 무시). */
		public final String mode;

		public Result(int start, int end, String mode) {
			this.start = start;
			this.end = end;
			this.mode = mode;
		}
	}

	private EditMatch() {
	}

	public static Result find(String content, String oldText) {
		if (content == null || oldText == null || oldText.isEmpty()) {
			return null;
		}
		int i = content.indexOf(oldText);
		if (i >= 0) {
			return new Result(i, i + oldText.length(), "exact");
		}
		Result r = lineMatch(content, oldText, false);
		if (r != null) {
			return r; // 줄 끝 공백/줄바꿈 차이 보정
		}
		return lineMatch(content, oldText, true); // 들여쓰기까지 무시
	}

	/** content 에 또 다른 정확 일치가 있는지(애매성 검사용). */
	public static boolean hasDuplicateExact(String content, String oldText) {
		int i = content.indexOf(oldText);
		return i >= 0 && content.indexOf(oldText, i + 1) >= 0;
	}

	/** oldText 의 정확 일치 횟수(다중 교체용). */
	public static int countExact(String content, String oldText) {
		if (content == null || oldText == null || oldText.isEmpty()) {
			return 0;
		}
		int n = 0;
		int i = 0;
		while ((i = content.indexOf(oldText, i)) >= 0) {
			n++;
			i += oldText.length();
		}
		return n;
	}

	private static Result lineMatch(String content, String oldText, boolean fullTrim) {
		List<int[]> lines = lineSpans(content);
		String[] rawOld = oldText.split("\n", -1);
		List<String> oldLines = new ArrayList<>();
		for (String s : rawOld) {
			oldLines.add(s);
		}
		// old_text 끝의 빈 줄 제거("foo\n" 이 "foo" 줄과 매칭되도록)
		while (oldLines.size() > 1 && oldLines.get(oldLines.size() - 1).trim().isEmpty()) {
			oldLines.remove(oldLines.size() - 1);
		}
		int m = oldLines.size();
		if (m == 0) {
			return null;
		}
		for (int ws = 0; ws + m <= lines.size(); ws++) {
			boolean ok = true;
			for (int k = 0; k < m; k++) {
				int[] span = lines.get(ws + k);
				String cl = content.substring(span[0], span[1]);
				if (!norm(cl, fullTrim).equals(norm(oldLines.get(k), fullTrim))) {
					ok = false;
					break;
				}
			}
			if (ok) {
				int start = lines.get(ws)[0];
				int end = lines.get(ws + m - 1)[1];
				return new Result(start, end, fullTrim ? "indent" : "trailing-ws");
			}
		}
		return null;
	}

	/** 각 줄의 [시작, 줄바꿈 직전] 오프셋. */
	private static List<int[]> lineSpans(String content) {
		List<int[]> out = new ArrayList<>();
		int i = 0;
		while (true) {
			int nl = content.indexOf('\n', i);
			if (nl < 0) {
				out.add(new int[] { i, content.length() });
				break;
			}
			out.add(new int[] { i, nl });
			i = nl + 1;
		}
		return out;
	}

	private static String norm(String s, boolean fullTrim) {
		if (fullTrim) {
			return s.trim();
		}
		int end = s.length();
		while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
			end--;
		}
		return s.substring(0, end);
	}
}
