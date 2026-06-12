package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부 의존성 없는 최소 JSON 파서/라이터.
 * Agent 의 중첩 응답(message.tool_calls[].function.arguments{}) 처리를 위해 필요하다.
 * 값 타입: Map(object), List(array), String, Long/Double(number), Boolean, null.
 */
public final class Json {

	private Json() {
	}

	// ===================== Parser =====================

	public static Object parse(String s) {
		return new Parser(s).parseTop();
	}

	private static final class Parser {
		private final String s;
		private int i;

		Parser(String s) {
			this.s = s;
		}

		Object parseTop() {
			skipWs();
			Object v = parseValue();
			return v;
		}

		Object parseValue() {
			skipWs();
			char c = peek();
			switch (c) {
			case '{':
				return parseObject();
			case '[':
				return parseArray();
			case '"':
				return parseString();
			case 't':
				expectWord("true");
				return Boolean.TRUE;
			case 'f':
				expectWord("false");
				return Boolean.FALSE;
			case 'n':
				expectWord("null");
				return null;
			default:
				return parseNumber();
			}
		}

		Map<String, Object> parseObject() {
			Map<String, Object> m = new LinkedHashMap<>();
			expectChar('{');
			skipWs();
			if (peek() == '}') {
				i++;
				return m;
			}
			while (true) {
				skipWs();
				String key = parseString();
				skipWs();
				expectChar(':');
				Object val = parseValue();
				m.put(key, val);
				skipWs();
				char c = next();
				if (c == '}') {
					break;
				}
				if (c != ',') {
					throw err("expected , or }");
				}
			}
			return m;
		}

		List<Object> parseArray() {
			List<Object> l = new ArrayList<>();
			expectChar('[');
			skipWs();
			if (peek() == ']') {
				i++;
				return l;
			}
			while (true) {
				l.add(parseValue());
				skipWs();
				char c = next();
				if (c == ']') {
					break;
				}
				if (c != ',') {
					throw err("expected , or ]");
				}
			}
			return l;
		}

		String parseString() {
			expectChar('"');
			StringBuilder sb = new StringBuilder();
			while (true) {
				char c = next();
				if (c == '"') {
					break;
				}
				if (c == '\\') {
					char n = next();
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
						sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
						break;
					default:
						sb.append(n);
					}
				} else {
					sb.append(c);
				}
			}
			return sb.toString();
		}

		Object parseNumber() {
			int start = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
				i++;
			}
			String num = s.substring(start, i);
			if (num.isEmpty()) {
				throw err("invalid value");
			}
			if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
				return Double.parseDouble(num);
			}
			try {
				return Long.parseLong(num);
			} catch (NumberFormatException e) {
				return Double.parseDouble(num);
			}
		}

		void expectWord(String w) {
			if (!s.startsWith(w, i)) {
				throw err("expected " + w);
			}
			i += w.length();
		}

		void expectChar(char c) {
			if (next() != c) {
				throw err("expected '" + c + "'");
			}
		}

		char peek() {
			return i < s.length() ? s.charAt(i) : '\0';
		}

		char next() {
			if (i >= s.length()) {
				throw err("unexpected end");
			}
			return s.charAt(i++);
		}

		void skipWs() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
		}

		RuntimeException err(String m) {
			return new RuntimeException("JSON 파싱 오류 @" + i + ": " + m);
		}
	}

	// ===================== Writer =====================

	public static String write(Object o) {
		StringBuilder sb = new StringBuilder();
		writeValue(sb, o);
		return sb.toString();
	}

	private static void writeValue(StringBuilder sb, Object o) {
		if (o == null) {
			sb.append("null");
		} else if (o instanceof String) {
			sb.append(JsonUtil.quote((String) o));
		} else if (o instanceof Boolean || o instanceof Number) {
			sb.append(o.toString());
		} else if (o instanceof Map) {
			writeObject(sb, (Map<?, ?>) o);
		} else if (o instanceof List) {
			writeArray(sb, (List<?>) o);
		} else {
			sb.append(JsonUtil.quote(o.toString()));
		}
	}

	private static void writeObject(StringBuilder sb, Map<?, ?> m) {
		sb.append('{');
		boolean first = true;
		for (Map.Entry<?, ?> e : m.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(JsonUtil.quote(String.valueOf(e.getKey()))).append(':');
			writeValue(sb, e.getValue());
		}
		sb.append('}');
	}

	private static void writeArray(StringBuilder sb, List<?> l) {
		sb.append('[');
		boolean first = true;
		for (Object e : l) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			writeValue(sb, e);
		}
		sb.append(']');
	}
}
