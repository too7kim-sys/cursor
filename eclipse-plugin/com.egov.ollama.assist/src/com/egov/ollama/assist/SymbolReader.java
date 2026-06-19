package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;

/**
 * 심볼(메서드/클래스) 단위 읽기 헬퍼(SWT 비의존, 테스트 가능). 정확한 파서는 아니지만
 * {@link SymbolIndex} 휴리스틱으로 정의 줄을 찾고, <b>문자열/문자 리터럴과 주석을 인지하는</b>
 * 중괄호 매칭으로 본문 범위를 정한다(리터럴·주석 안의 {@code { } ;} 는 무시). 약한 로컬 모델이
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
		List<Integer> defLines = new ArrayList<>();
		boolean[] used = new boolean[lines.length];
		for (String sym : SymbolIndex.extractSymbols(content)) {
			int dl = SymbolIndex.defLine(content, sym);
			if (dl < 0 || dl >= lines.length || used[dl]) {
				continue;
			}
			used[dl] = true;
			defLines.add(dl);
		}
		defLines.sort(Integer::compare);
		List<String> out = new ArrayList<>();
		for (int ln : defLines) {
			String text = lines[ln].trim();
			if (text.length() > 160) {
				text = text.substring(0, 160) + "…";
			}
			out.add((ln + 1) + ": " + text);
		}
		return String.join("\n", out);
	}

	/**
	 * 심볼의 정의 줄부터 본문(중괄호 균형) 끝까지를 줄번호와 함께 반환. 본문 중괄호가 없으면(추상/인터페이스
	 * 메서드 등) 선언 줄만 반환한다. 못 찾으면 null.
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
		int[] lineStart = lineStarts(lines);
		int from = lineStart[dl];
		List<int[]> toks = scanTokens(content); // [offset, type] type: 1='{', -1='}', 2=';'

		// 정의 줄 이후 처음 등장하는 '{' 또는 ';' 로 본문 유무를 판단
		int decide = -1;
		for (int t = 0; t < toks.size(); t++) {
			int[] tok = toks.get(t);
			if (tok[0] < from) {
				continue;
			}
			if (tok[1] == 1 || tok[1] == 2) {
				decide = t;
				break;
			}
		}
		if (decide < 0 || toks.get(decide)[1] == 2) {
			// 본문 없음(선언만) — 선언 줄(들)만
			int end = decide < 0 ? dl : lineOf(lineStart, toks.get(decide)[0]);
			return format(lines, dl, Math.max(dl, end));
		}
		// '{' 부터 균형 매칭으로 닫는 '}' 찾기(리터럴/주석 안의 괄호는 toks 에 없음)
		int depth = 0;
		int closeOff = -1;
		for (int t = decide; t < toks.size(); t++) {
			int[] tok = toks.get(t);
			if (tok[1] == 1) {
				depth++;
			} else if (tok[1] == -1) {
				depth--;
				if (depth == 0) {
					closeOff = tok[0];
					break;
				}
			}
		}
		if (closeOff < 0) {
			// 불균형 — 합리적 상한으로 자른다
			return format(lines, Math.max(0, dl - 1), Math.min(lines.length - 1, dl + FALLBACK_RADIUS));
		}
		return format(lines, dl, lineOf(lineStart, closeOff));
	}

	/** 문자열/문자 리터럴과 주석을 건너뛰며 중괄호('{'=1,'}'=-1)와 세미콜론(';'=2) 위치를 수집. */
	static List<int[]> scanTokens(String s) {
		List<int[]> out = new ArrayList<>();
		int n = s.length();
		int i = 0;
		while (i < n) {
			char c = s.charAt(i);
			if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
				i += 2;
				while (i < n && s.charAt(i) != '\n') {
					i++;
				}
			} else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
				i += 2;
				while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
					i++;
				}
				i += 2;
			} else if (c == '"' || c == '\'') {
				char quote = c;
				i++;
				while (i < n) {
					char d = s.charAt(i);
					if (d == '\\') {
						i += 2;
						continue;
					}
					i++;
					if (d == quote) {
						break;
					}
				}
			} else {
				if (c == '{') {
					out.add(new int[] { i, 1 });
				} else if (c == '}') {
					out.add(new int[] { i, -1 });
				} else if (c == ';') {
					out.add(new int[] { i, 2 });
				}
				i++;
			}
		}
		return out;
	}

	private static int[] lineStarts(String[] lines) {
		int[] st = new int[lines.length];
		int off = 0;
		for (int i = 0; i < lines.length; i++) {
			st[i] = off;
			off += lines[i].length() + 1; // '\n'
		}
		return st;
	}

	private static int lineOf(int[] lineStart, int offset) {
		int lo = 0;
		int hi = lineStart.length - 1;
		int ans = 0;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (lineStart[mid] <= offset) {
				ans = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return ans;
	}

	private static String format(String[] lines, int start, int end) {
		StringBuilder sb = new StringBuilder();
		for (int i = start; i <= end && i < lines.length; i++) {
			sb.append(i + 1).append(": ").append(lines[i]).append('\n');
		}
		return sb.toString();
	}
}
