package com.egov.ollama.assist.handlers;

/** 선택 코드 리팩터링 제안 요청. */
public class RefactorHandler extends AbstractCodeActionHandler {

	@Override
	protected String instruction() {
		return "다음 코드를 더 읽기 쉽고 안전하게 리팩터링한 전체 코드를 제시하고, 변경 이유를 한국어로 설명해줘.";
	}
}
