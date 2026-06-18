package com.egov.ollama.assist;

/**
 * 채팅 입력의 슬래시 명령(/test, /explain 등)을 지시문 템플릿으로 확장한다.
 * 순수 JDK 로직이라 단위 테스트가 가능하다. (/clear, /help, /commit 등 특수 동작은 UI에서 처리)
 */
public final class SlashCommands {

	private SlashCommands() {
	}

	/** 입력이 슬래시 명령이면 true. */
	public static boolean isCommand(String input) {
		return input != null && input.trim().startsWith("/");
	}

	/** 명령 토큰(소문자, '/' 포함). 슬래시 명령이 아니면 null. */
	public static String command(String input) {
		if (!isCommand(input)) {
			return null;
		}
		String t = input.trim();
		int sp = t.indexOf(' ');
		return (sp < 0 ? t : t.substring(0, sp)).toLowerCase();
	}

	/** 명령 뒤의 본문(코드/내용). */
	public static String body(String input) {
		String t = input.trim();
		int sp = t.indexOf(' ');
		return sp < 0 ? "" : t.substring(sp + 1).trim();
	}

	/**
	 * 템플릿 명령을 확장한 프롬프트를 반환. 템플릿 명령이 아니면 null
	 * (호출 측에서 /clear, /help, /commit 등 특수 처리 또는 일반 전송).
	 */
	public static String expand(String input) {
		String cmd = command(input);
		if (cmd == null) {
			return null;
		}
		String body = body(input);
		switch (cmd) {
		case "/test":
			return "다음 코드에 대한 JUnit 테스트 코드를 작성해줘. 정상/경계/예외 케이스를 포함하고 한국어 주석을 달아줘:\n\n" + body;
		case "/explain":
			return "다음 코드가 하는 일과 핵심 로직, 잠재적 문제를 한국어로 자세히 설명해줘:\n\n" + body;
		case "/review":
			return "다음 코드를 리뷰해줘. 버그/성능/가독성/보안 관점에서 문제와 개선안을 한국어로 정리해줘:\n\n" + body;
		case "/doc":
			return "다음 코드에 한국어 Javadoc/주석을 추가한 전체 코드를 제시해줘:\n\n" + body;
		case "/egov":
			return "전자정부 표준프레임워크 규칙을 따라 '" + body + "' 기능의 DAO, Service, ServiceImpl, "
					+ "Controller, MyBatis Mapper 를 만들어줘. 먼저 search_text/semantic_search 로 유사 코드를 찾아 "
					+ "프로젝트의 패키지 구조·네이밍·응답 형식 컨벤션을 확인한 뒤, 같은 스타일로 작성할 것.";
		case "/xfdl":
			return "다음은 넥사크로/웹스퀘어 화면 정의(XML)다. 요청대로 분석/수정하되 XML 구조와 속성 형식을 "
					+ "보존하고, 디자이너에서 다시 열 수 있도록 유효한 XML 을 유지할 것:\n\n" + body;
		case "/ui":
			return "현대적 UI/UX 모범사례를 반영해 '" + body + "' 화면/컴포넌트를 구현해줘.\n"
					+ "원칙: 접근성(키보드/라벨/대비), 반응형, 일관된 디자인 토큰(색/간격/타이포), "
					+ "명확한 상태 표시(로딩 스켈레톤·빈 상태·에러·성공), 적절한 피드백/마이크로인터랙션, 과한 장식 배제.\n"
					+ "절차: 먼저 search_text/semantic_search 로 프로젝트의 기존 화면·명명 규칙과 (있다면) UI 지식팩"
					+ "(.ollama-assist/knowledge 또는 프로젝트 내 UI 가이드)을 확인하고 그 규칙을 우선 적용할 것. "
					+ "넥사크로/웹스퀘어 프로젝트면 해당 프레임워크 컴포넌트와 XML 구조를 사용하고 디자이너 호환 형식을 유지할 것.";
		default:
			return null;
		}
	}

	/** /help 출력. */
	public static String help() {
		return "사용 가능한 슬래시 명령:\n"
				+ "  /test <코드>     — JUnit 테스트 생성\n"
				+ "  /explain <코드>  — 코드 설명\n"
				+ "  /review <코드>   — 코드 리뷰\n"
				+ "  /doc <코드>      — 주석/Javadoc 추가\n"
				+ "  /egov <기능명>   — 전자정부 DAO/Service/Controller/Mapper 생성(Agent 모드 권장)\n"
				+ "  /xfdl <XML>      — 넥사크로/웹스퀘어 화면 분석·수정\n"
				+ "  /ui <설명>       — 현대적 UI/UX 화면·컴포넌트 구현(지식팩+RAG 활용)\n"
				+ "  /commit          — 스테이징된 변경(git diff)으로 커밋 메시지 생성\n"
				+ "  /clear           — 대화 지우기(새 대화)\n"
				+ "  /help            — 이 도움말\n"
				+ "팁: 편집기에서 코드 선택 후 Ctrl+Alt+A 로 입력창에 코드를 채운 뒤 /test 등을 앞에 붙이세요.";
	}
}
