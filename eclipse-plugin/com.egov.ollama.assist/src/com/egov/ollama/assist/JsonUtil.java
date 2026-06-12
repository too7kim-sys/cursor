package com.egov.ollama.assist;

/**
 * 외부 의존성 없이(JDK만으로) JSON 문자열을 만들고 읽기 위한 최소 유틸.
 * 폐쇄망에서 추가 라이브러리 반입 없이 빌드되도록 직접 구현했습니다.
 */
public final class JsonUtil {

	private JsonUtil() {
	}

	/** 문자열을 JSON 문자열 리터럴(따옴표 포함)로 이스케이프한다. */
	public static String quote(String s) {
		if (s == null) {
			return "\"\"";
		}
		StringBuilder sb = new StringBuilder(s.length() + 2);
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"':
				sb.append("\\\"");
				break;
			case '\\':
				sb.append("\\\\");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			case '\b':
				sb.append("\\b");
				break;
			case '\f':
				sb.append("\\f");
				break;
			default:
				if (c < 0x20) {
					sb.append(String.format("\\u%04x", (int) c));
				} else {
					sb.append(c);
				}
			}
		}
		sb.append('"');
		return sb.toString();
	}

	/**
	 * JSON 텍스트에서 첫 번째로 등장하는 "key":"value" 의 value(언이스케이프 결과)를 반환한다.
	 * 값이 문자열이 아니거나 key가 없으면 null.
	 */
	public static String extractString(String json, String key) {
		if (json == null) {
			return null;
		}
		String pat = "\"" + key + "\"";
		int i = json.indexOf(pat);
		if (i < 0) {
			return null;
		}
		i += pat.length();
		// 콜론까지 이동
		while (i < json.length() && json.charAt(i) != ':') {
			i++;
		}
		i++; // 콜론 다음
		// 공백 건너뛰기
		while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
			i++;
		}
		if (i >= json.length() || json.charAt(i) != '"') {
			return null; // 문자열 값이 아님(null/숫자 등)
		}
		i++; // 여는 따옴표 다음
		StringBuilder sb = new StringBuilder();
		while (i < json.length()) {
			char c = json.charAt(i++);
			if (c == '\\') {
				if (i >= json.length()) {
					break;
				}
				char n = json.charAt(i++);
				switch (n) {
				case '"':
					sb.append('"');
					break;
				case '\\':
					sb.append('\\');
					break;
				case '/':
					sb.append('/');
					break;
				case 'n':
					sb.append('\n');
					break;
				case 't':
					sb.append('\t');
					break;
				case 'r':
					sb.append('\r');
					break;
				case 'b':
					sb.append('\b');
					break;
				case 'f':
					sb.append('\f');
					break;
				case 'u':
					if (i + 4 <= json.length()) {
						try {
							sb.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
						} catch (NumberFormatException ignore) {
							// 무시
						}
						i += 4;
					}
					break;
				default:
					sb.append(n);
				}
			} else if (c == '"') {
				break; // 닫는 따옴표
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
