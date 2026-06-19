package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;

/**
 * 심볼(메서드/클래스) 단위 읽기 헬퍼(SWT 비의존, 테스트 가능). 정확한 파서가 아니라
 * {@link SymbolIndex} 휴리스틱 + 중괄호 균형으로 본문 범위를 추정한다. 약한 로컬 모델이
 * 거대 파일을 통째로 읽지 않고 필요한 심볼만 보게 해 토큰을 아끼는 용도.
 */
public final class SymbolReader {

	private static final int FALLBACK_RADIUS = 8;

	private SymbolReader() {
	}

	/** 파일의 심볼 정의 목록을 "줄번호: 선언" 형식으로 반환(등장 줄 오름차순, 중복 제거). */
	public static String outline(String content) {
		if (content == null || content.isEmpty()) {
			return "";
		}
		String[] lines = content.split("\n", -1);
		List<int[]> seen = new ArrayList<>(); // [line]
		List<String> out = new ArrayList<>();
		boolean[] used = new boolean[lines.length];
		for (String sym : SymbolIndex.extractSymbols(content)) {
			int dl = SymbolIndex.defLine(content, sym);
			if (dl < 0 || dl >= lines.length || used[dl]) {
				continue;
			}
			used[dl] = true;
			seen.add(new int[] { dl });
		}
		seen.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int[] s : seen) {
			int ln = s[0];
			String text = lines[ln].trim();
			if (text.length() > 160) {
				text = text.substring(0, 160) + "…";
			}
			out.add((ln + 1) + ": " + text);
		}
		return String.join("\n", out);
	}

	/**
	 * 심볼의 정의 줄부터 본문(중괄호 균형) 끝까지를 줄번호와 함께 반환. 중괄호가 없으면 선언 줄 주변을 반환.
	 * 못 찾으면 null.
	 */
	public static String read(String content, String name) {
		if (content == null || name == null || name.isEmpty()) {
			return null;
		}
		int dl = SymbolIndex.defLine(content, name);
		if (dl < 0) {
			return null;
		}
		String[] lines = content.split("\n", -1);
		if (dl >= lines.length) {
			return null;
		}
		// 정의 줄 이후 첫 '{' 위치(줄) 찾기
		int braceLine = -1;
		for (int i = dl; i < lines.length; i++) {
			if (lines[i].indexOf('{') >= 0) {
				braceLine = i;
				break;
			}
			// 선언이 ';' 로 끝나면(본문 없는 추상/인터페이스 메서드) 선언 줄만
			if (lines[i].indexOf(';') >= 0) {
				break;
			}
		}
		int start = dl;
		int end;
		if (braceLine < 0) {
			end = Math.min(lines.length - 1, dl + 1);
			return format(lines, start, Math.min(end, dl)); // 선언 줄(들)
		}
		int depth = 0;
		boolean opened = false;
		end = lines.length - 1;
		outer: for (int i = braceLine; i < lines.length; i++) {
			String l = lines[i];
			for (int j = 0; j < l.length(); j++) {
				char c = l.charAt(j);
				if (c == '{') {
					depth++;
					opened = true;
				} else if (c == '}') {
					depth--;
					if (opened && depth <= 0) {
						end = i;
						break outer;
					}
				}
			}
		}
		// 비정상(불균형)이면 합리적 상한으로 자른다
		if (!opened) {
			start = Math.max(0, dl - 1);
			end = Math.min(lines.length - 1, dl + FALLBACK_RADIUS);
		}
		return format(lines, start, end);
	}

	private static String format(String[] lines, int start, int end) {
		StringBuilder sb = new StringBuilder();
		for (int i = start; i <= end && i < lines.length; i++) {
			sb.append(i + 1).append(": ").append(lines[i]).append('\n');
		}
		return sb.toString();
	}
}
