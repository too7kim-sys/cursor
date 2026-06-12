package com.egov.ollama.assist.handlers;

/** 선택 코드 설명 요청. */
public class ExplainHandler extends AbstractCodeActionHandler {

	@Override
	protected String instruction() {
		return "다음 코드가 하는 일을 한국어로 자세히 설명해줘. 주요 로직과 잠재적 문제점도 함께 알려줘.";
	}
}
