package com.egov.ollama.assist;

import java.util.regex.Pattern;

/**
 * 단순 glob 패턴 매칭(SWT/Eclipse 비의존, 테스트 가능).
 * <ul>
 * <li>{@code *} : 경로 구분자('/')를 제외한 임의 문자열</li>
 * <li>{@code **} : 구분자를 포함한 임의 깊이. {@code **}{@code /} 는 0개 이상의 디렉터리</li>
 * <li>{@code ?} : 구분자를 제외한 한 글자</li>
 * </ul>
 * 패턴에 '/' 가 없으면 경로의 파일명만 비교한다(어느 디렉터리든 일치).
 */
public final class GlobMatcher {

	private GlobMatcher() {
	}

	/** glob 을 정규식으로 변환한다(전체 일치). */
	public static Pattern compile(String glob) {
		StringBuilder sb = new StringBuilder("^");
		int n = glob.length();
		for (int i = 0; i < n; i++) {
			char c = glob.charAt(i);
			switch (c) {
			case '*':
				if (i + 1 < n && glob.charAt(i + 1) == '*') {
					i++; // 두 번째 '*' 소비
					if (i + 1 < n && glob.charAt(i + 1) == '/') {
						i++; // 슬래시 소비 → 0개 이상 디렉터리
						sb.append("(?:.*/)?");
					} else {
						sb.append(".*");
					}
				} else {
					sb.append("[^/]*");
				}
				break;
			case '?':
				sb.append("[^/]");
				break;
			case '.':
			case '\\':
			case '+':
			case '(':
			case ')':
			case '[':
			case ']':
			case '{':
			case '}':
			case '^':
			case '$':
			case '|':
				sb.append('\\').append(c);
				break;
			default:
				sb.append(c);
			}
		}
		sb.append('$');
		return Pattern.compile(sb.toString());
	}

	/** path 가 glob 에 일치하는지. glob 이 비면 항상 true. */
	public static boolean matches(String glob, String path) {
		if (glob == null || glob.isEmpty()) {
			return true;
		}
		if (path == null) {
			return false;
		}
		String p = path.replace('\\', '/');
		if (glob.indexOf('/') < 0) {
			int s = p.lastIndexOf('/');
			String name = s >= 0 ? p.substring(s + 1) : p;
			return compile(glob).matcher(name).matches();
		}
		return compile(glob).matcher(p).matches();
	}
}
