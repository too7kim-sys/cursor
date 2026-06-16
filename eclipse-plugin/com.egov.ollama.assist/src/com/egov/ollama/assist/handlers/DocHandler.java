package com.egov.ollama.assist.handlers;

/** 선택 코드 → DocHandler */
public class DocHandler extends AbstractCodeActionHandler {

	@Override
	protected String instruction() {
		return "다음 코드에 한국어 Javadoc/주석을 추가한 전체 코드를 제시해줘.";
	}
}
