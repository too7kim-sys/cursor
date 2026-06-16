package com.egov.ollama.assist.handlers;

/** 선택 코드 → TestHandler */
public class TestHandler extends AbstractCodeActionHandler {

	@Override
	protected String instruction() {
		return "다음 코드에 대한 JUnit 테스트 코드를 작성해줘. 정상/경계/예외 케이스를 포함하고 한국어 주석을 달아줘.";
	}
}
