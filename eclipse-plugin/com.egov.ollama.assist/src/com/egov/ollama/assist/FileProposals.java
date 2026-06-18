package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;

/**
 * 채팅 입력의 @파일 자동완성용 순수 로직: 커서 앞의 '@토큰' 추출 + 후보 경로 필터.
 * SWT 비의존이라 단위 테스트가 가능하다.
 */
public final class FileProposals {

	private FileProposals() {
	}

	/** 커서(pos) 바로 앞의 '@토큰' 문자열(‘@’ 제외)을 반환. 멘션 컨텍스트가 아니면 null. */
	public static String tokenAt(String text, int pos) {
		if (text == null || pos < 0 || pos > text.length()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		int i = pos - 1;
		while (i >= 0) {
			char c = text.charAt(i);
			if (c == '@') {
				if (i > 0) {
					char p = text.charAt(i - 1);
					if (Character.isLetterOrDigit(p) || p == '@') {
						return null; // 이메일 등 단어 중간 '@' 회피
					}
				}
				return sb.reverse().toString();
			}
			if (Character.isWhitespace(c) || !isPathChar(c)) {
				return null;
			}
			sb.append(c);
			i--;
		}
		return null;
	}

	private static boolean isPathChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == '\\';
	}

	/**
	 * 후보 상대경로들 중 token 에 맞는 것을 최대 max 개 반환. 경로/파일명 접두어 우선, 그다음 부분일치.
	 * token 이 비면 앞에서부터 max 개.
	 */
	public static List<String> match(List<String> relPaths, String token, int max) {
		List<String> prefix = new ArrayList<>();
		List<String> contains = new ArrayList<>();
		String t = token == null ? "" : token.toLowerCase();
		for (String p : relPaths) {
			if (p == null || p.isEmpty()) {
				continue;
			}
			if (t.isEmpty()) {
				prefix.add(p);
			} else {
				String low = p.toLowerCase();
				String name = fileName(low);
				if (low.startsWith(t) || name.startsWith(t)) {
					prefix.add(p);
				} else if (low.contains(t)) {
					contains.add(p);
				}
			}
			if (prefix.size() >= max) {
				break;
			}
		}
		List<String> out = new ArrayList<>(prefix);
		for (String p : contains) {
			if (out.size() >= max) {
				break;
			}
			out.add(p);
		}
		return out;
	}

	private static String fileName(String path) {
		int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return slash >= 0 ? path.substring(slash + 1) : path;
	}
}
