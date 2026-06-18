package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 입력의 @멘션 파싱(순수 로직). {@code @selection}, {@code @경로/파일.java} 형태를 추출한다.
 * 이메일(예: a@b.com)처럼 단어 중간의 '@'는 멘션으로 보지 않는다.
 */
public final class Mentions {

	public static final String SELECTION = "selection";

	// '@' 앞이 단어문자/'@'가 아니어야 멘션(이메일 회피). 이후 경로/파일명에 쓰이는 문자들.
	private static final Pattern P = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_./\\\\-]+)");

	private Mentions() {
	}

	/** 입력에서 멘션 토큰(‘@’ 제외)을 등장 순서대로, 중복 없이 반환. */
	public static List<String> parse(String text) {
		List<String> out = new ArrayList<>();
		if (text == null) {
			return out;
		}
		Matcher m = P.matcher(text);
		while (m.find()) {
			String name = m.group(1);
			// 끝에 붙은 문장부호 정리(예: "@Foo.java," → "Foo.java")
			while (name.length() > 1 && (name.endsWith(".") || name.endsWith(",") || name.endsWith(")"))) {
				name = name.substring(0, name.length() - 1);
			}
			if (!name.isEmpty() && !out.contains(name)) {
				out.add(name);
			}
		}
		return out;
	}

	public static boolean isSelection(String name) {
		return SELECTION.equalsIgnoreCase(name);
	}
}
