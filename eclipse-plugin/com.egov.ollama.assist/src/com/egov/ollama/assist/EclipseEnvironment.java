package com.egov.ollama.assist;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.TextConsole;

/**
 * {@link OllamaAgent.Environment} 의 Eclipse 구현.
 * <ul>
 * <li>Problems: 워크스페이스 마커(IMarker.PROBLEM)에서 오류/경고 수집</li>
 * <li>Console: org.eclipse.ui.console 의 TextConsole 문서 내용(최근 부분)</li>
 * <li>서버: WTP(org.eclipse.wst.server.core)는 선택 의존이라 {@link ServerControl} 로 분리하고
 * 미설치 시 예외를 메시지로 변환</li>
 * </ul>
 */
public class EclipseEnvironment implements OllamaAgent.Environment {

	private static final int MAX_ITEMS = 200;
	private static final int CONSOLE_TAIL = 8000;

	@Override
	public String getProblems() {
		try {
			IMarker[] markers = ResourcesPlugin.getWorkspace().getRoot()
					.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
			int errs = 0;
			int warns = 0;
			int shown = 0;
			StringBuilder sb = new StringBuilder();
			for (IMarker m : markers) {
				int sev = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
				if (sev == IMarker.SEVERITY_ERROR) {
					errs++;
				} else if (sev == IMarker.SEVERITY_WARNING) {
					warns++;
				} else {
					continue; // INFO 는 생략
				}
				if (shown >= MAX_ITEMS) {
					continue;
				}
				shown++;
				String kind = sev == IMarker.SEVERITY_ERROR ? "ERROR" : "WARN";
				String msg = m.getAttribute(IMarker.MESSAGE, "");
				int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
				IResource r = m.getResource();
				String path = (r != null && r.getProjectRelativePath() != null)
						? r.getFullPath().toString()
						: "";
				sb.append(kind).append(' ').append(path).append(line > 0 ? ":" + line : "")
						.append(": ").append(msg).append('\n');
			}
			String head = "오류 " + errs + "개, 경고 " + warns + "개\n";
			return head + (sb.length() == 0 ? "(표시할 오류/경고 없음)" : sb.toString());
		} catch (Exception e) {
			return "Problems 조회 실패: " + e.getMessage();
		}
	}

	@Override
	public String getConsole() {
		final String[] out = { null };
		try {
			Display display = Display.getDefault();
			if (display == null || display.isDisposed()) {
				return "콘솔을 읽을 수 없습니다(디스플레이 없음).";
			}
			display.syncExec(() -> {
				StringBuilder sb = new StringBuilder();
				IConsole[] consoles = ConsolePlugin.getDefault().getConsoleManager().getConsoles();
				for (IConsole c : consoles) {
					if (c instanceof TextConsole) {
						IDocument doc = ((TextConsole) c).getDocument();
						if (doc == null) {
							continue;
						}
						String text = doc.get();
						String tail = text.length() > CONSOLE_TAIL ? text.substring(text.length() - CONSOLE_TAIL) : text;
						sb.append("== ").append(c.getName()).append(" ==\n").append(tail).append('\n');
					}
				}
				out[0] = sb.length() == 0 ? "(콘솔 출력 없음)" : sb.toString();
			});
			return out[0] == null ? "(콘솔 출력 없음)" : out[0];
		} catch (Exception e) {
			return "Console 조회 실패: " + e.getMessage();
		}
	}

	@Override
	public void console(String text) {
		ConsoleUtil.print(text);
	}

	@Override
	public String listServers() {
		try {
			return ServerControl.list();
		} catch (Throwable t) {
			return "서버 기능을 사용할 수 없습니다(WTP 미설치 또는 오류): " + t.getMessage();
		}
	}

	@Override
	public String startServer(String name) {
		try {
			return ServerControl.start(name);
		} catch (Throwable t) {
			return "서버 기동 실패(WTP 미설치 또는 오류): " + t.getMessage();
		}
	}

	@Override
	public String stopServer(String name) {
		try {
			return ServerControl.stop(name);
		} catch (Throwable t) {
			return "서버 중지 실패(WTP 미설치 또는 오류): " + t.getMessage();
		}
	}
}
