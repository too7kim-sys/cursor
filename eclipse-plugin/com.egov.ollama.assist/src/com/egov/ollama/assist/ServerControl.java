package com.egov.ollama.assist;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * WTP(org.eclipse.wst.server.core) 기반 서버 제어 — <b>리플렉션</b>으로 구현.
 * <p>
 * WTP 는 Eclipse 배포본마다 유무/버전이 달라 컴파일 의존을 두면 플러그인 빌드 자체가 깨질 수 있다.
 * 따라서 컴파일 의존 없이 리플렉션으로 호출하고, 미설치/시그니처 불일치 시 예외는
 * 호출 측({@link EclipseEnvironment})에서 Throwable 로 잡아 안내 메시지로 바꾼다.
 */
final class ServerControl {

	private static final String SERVER_CORE = "org.eclipse.wst.server.core.ServerCore";

	private ServerControl() {
	}

	private static Object[] getServers() throws Exception {
		Class<?> serverCore = Class.forName(SERVER_CORE);
		Object result = serverCore.getMethod("getServers").invoke(null);
		return (result instanceof Object[]) ? (Object[]) result : new Object[0];
	}

	private static String name(Object server) throws Exception {
		Object n = server.getClass().getMethod("getName").invoke(server);
		return n == null ? "" : n.toString();
	}

	private static int state(Object server) throws Exception {
		Object s = server.getClass().getMethod("getServerState").invoke(server);
		return (s instanceof Integer) ? (Integer) s : -1;
	}

	static String list() throws Exception {
		Object[] servers = getServers();
		if (servers.length == 0) {
			return "등록된 서버가 없습니다.";
		}
		StringBuilder sb = new StringBuilder();
		for (Object s : servers) {
			sb.append(name(s)).append(" — 상태: ").append(stateText(state(s))).append('\n');
		}
		return sb.toString();
	}

	static String start(String wanted) throws Exception {
		Object s = find(wanted);
		if (s == null) {
			return "서버를 찾을 수 없습니다: " + wanted;
		}
		Method m = s.getClass().getMethod("start", String.class, IProgressMonitor.class);
		m.invoke(s, "run", new NullProgressMonitor());
		return "기동 요청 완료: " + name(s) + " (상태: " + stateText(state(s)) + ")";
	}

	static String stop(String wanted) throws Exception {
		Object s = find(wanted);
		if (s == null) {
			return "서버를 찾을 수 없습니다: " + wanted;
		}
		s.getClass().getMethod("stop", boolean.class).invoke(s, false);
		return "중지 요청 완료: " + name(s);
	}

	private static Object find(String wanted) throws Exception {
		Object[] servers = getServers();
		for (Object s : servers) {
			if (name(s).equalsIgnoreCase(wanted)) {
				return s;
			}
		}
		for (Object s : servers) {
			if (name(s).toLowerCase().contains(wanted.toLowerCase())) {
				return s;
			}
		}
		return null;
	}

	/** WTP IServer.STATE_* 상수값(0~4)을 한국어로. */
	private static String stateText(int state) {
		switch (state) {
		case 1:
			return "기동중";
		case 2:
			return "실행중";
		case 3:
			return "중지중";
		case 4:
			return "중지됨";
		default:
			return "알수없음";
		}
	}
}
