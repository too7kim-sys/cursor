package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 단일 파일 내용 검색(SWT/Eclipse 비의존, 테스트 가능). 파일 순회는 호출부가 담당하고,
 * 여기서는 한 파일의 텍스트에서 일치 줄(+선택적 앞뒤 컨텍스트)을 형식화해 반환한다.
 * 반환 각 줄은 {@code "<lineNo>: <text>"}(일치) 또는 {@code "<lineNo>- <text>"}(컨텍스트),
 * 그룹 사이 구분은 {@code "--"}. 호출부는 앞에 {@code "상대경로:"} 를 붙인다.
 */
public final class TextSearch {

	private static final int MAX_LINE = 200;

	private TextSearch() {
	}

	/**
	 * @param content 파일 전체 내용
	 * @param query   부분문자열 또는 정규식
	 * @param regex   true 면 query 를 정규식으로 해석
	 * @param context 일치 줄 앞뒤로 포함할 줄 수(0 이면 일치 줄만)
	 * @param max     최대 일치 개수
	 * @return 형식화된 줄 목록(일치 없으면 빈 목록)
	 * @throws java.util.regex.PatternSyntaxException regex=true 이고 패턴이 잘못된 경우
	 */
	public static List<String> search(String content, String query, boolean regex, int context, int max) {
		List<String> out = new ArrayList<>();
		if (content == null || query == null || query.isEmpty() || max <= 0) {
			return out;
		}
		String[] lines = content.split("\n", -1);
		Pattern p = regex ? Pattern.compile(query) : null;
		int ctx = Math.max(0, context);
		// 먼저 일치 줄을 표시한다(최대 max 개). 인접 매치가 서로의 컨텍스트로 흡수돼
		// 일치 줄이 "N-"(컨텍스트)로 잘못 찍히거나 카운트가 어긋나는 것을 방지한다.
		boolean[] isMatch = new boolean[lines.length];
		List<Integer> matches = new ArrayList<>();
		for (int i = 0; i < lines.length && matches.size() < max; i++) {
			boolean hit = regex ? p.matcher(lines[i]).find() : lines[i].contains(query);
			if (hit) {
				isMatch[i] = true;
				matches.add(i);
			}
		}
		int lastEmitted = -1;
		for (int idx : matches) {
			int from = Math.max(0, idx - ctx);
			int to = Math.min(lines.length - 1, idx + ctx);
			if (ctx > 0 && lastEmitted >= 0 && from > lastEmitted + 1) {
				out.add("--");
			}
			for (int j = Math.max(from, lastEmitted + 1); j <= to; j++) {
				String t = lines[j].trim();
				if (t.length() > MAX_LINE) {
					t = t.substring(0, MAX_LINE) + "…";
				}
				out.add((j + 1) + (isMatch[j] ? ": " : "- ") + t);
				lastEmitted = j;
			}
		}
		return out;
	}

	/** search() 결과 한 줄이 일치 줄("N: …")인지 판별(컨텍스트 "N- …"/그룹 구분 "--" 와 구분). */
	public static boolean isMatchLine(String h) {
		if (h == null) {
			return false;
		}
		int i = 0;
		while (i < h.length() && Character.isDigit(h.charAt(i))) {
			i++;
		}
		return i > 0 && i < h.length() && h.charAt(i) == ':';
	}
}
