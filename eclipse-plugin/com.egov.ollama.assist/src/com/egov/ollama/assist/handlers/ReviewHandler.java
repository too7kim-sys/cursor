package com.egov.ollama.assist.handlers;

/** 선택 코드 → ReviewHandler */
public class ReviewHandler extends AbstractCodeActionHandler {

	@Override
	protected String instruction() {
		return "다음 코드를 리뷰해줘. 버그/성능/가독성/보안 관점에서 문제와 개선안을 한국어로 정리해줘.";
	}
}
