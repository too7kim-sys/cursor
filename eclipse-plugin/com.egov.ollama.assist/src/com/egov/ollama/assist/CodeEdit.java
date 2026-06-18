package com.egov.ollama.assist;

/**
 * 인라인 편집(Ctrl+I)용 순수 로직: 모델 프롬프트 작성 + 모델 응답에서 코드만 추출.
 * SWT/Eclipse 비의존이라 단위 테스트가 가능하다.
 */
public final class CodeEdit {

	private CodeEdit() {
	}

	/** 선택 코드를 지시대로 고치도록 지시하는 사용자 프롬프트. 설명/펜스 없이 코드만 받도록 유도. */
	public static String buildEditPrompt(String code, String instruction, String lang) {
		StringBuilder sb = new StringBuilder();
		sb.append("아래 코드를 지시에 따라 수정하라. ");
		sb.append("수정된 코드 전체만 출력하고, 설명·마크다운·코드펜스(```)는 절대 붙이지 마라. ");
		sb.append("들여쓰기와 코드 스타일은 원본을 따른다.\n");
		if (lang != null && !lang.isEmpty()) {
			sb.append("언어: ").append(lang).append('\n');
		}
		sb.append("지시: ").append(instruction == null ? "" : instruction).append('\n');
		sb.append("\n----- 원본 코드 -----\n").append(code == null ? "" : code);
		return sb.toString();
	}

	/** 인라인 편집용 시스템 프롬프트. */
	public static String editSystemPrompt() {
		return "당신은 코드 편집기다. 요청된 수정만 적용한 코드를 그대로 반환한다. "
				+ "여분의 설명, 인사, 마크다운 코드펜스를 출력하지 않는다.";
	}

	/**
	 * 모델 출력에서 실제 코드만 추출. 코드펜스(```)로 감싸진 경우 첫 블록 내용을 반환하고,
	 * 아니면 앞뒤 공백만 정리해 그대로 반환한다.
	 */
	public static String cleanCode(String modelOutput) {
		if (modelOutput == null) {
			return "";
		}
		String out = modelOutput;
		int fence = out.indexOf("```");
		if (fence >= 0) {
			int nl = out.indexOf('\n', fence);
			if (nl >= 0) {
				int close = out.indexOf("```", nl + 1);
				if (close >= 0) {
					return stripTrailingNewlines(out.substring(nl + 1, close));
				}
				// 닫는 펜스가 없으면 여는 펜스 이후 전체
				return stripTrailingNewlines(out.substring(nl + 1));
			}
		}
		return out.trim();
	}

	private static String stripTrailingNewlines(String s) {
		int end = s.length();
		while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
			end--;
		}
		int start = 0;
		// 앞쪽 빈 줄만 제거(선행 들여쓰기는 보존)
		while (start < end && (s.charAt(start) == '\n' || s.charAt(start) == '\r')) {
			start++;
		}
		return s.substring(start, end);
	}
}
