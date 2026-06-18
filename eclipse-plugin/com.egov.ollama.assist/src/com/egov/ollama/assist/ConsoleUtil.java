package com.egov.ollama.assist;

import java.io.IOException;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

/** "Ollama Assist" 전용 Eclipse Console 패널에 출력하고 뷰를 띄운다. */
public final class ConsoleUtil {

	private static final String NAME = "Ollama Assist";

	private ConsoleUtil() {
	}

	public static void print(final String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		try {
			Display d = Display.getDefault();
			if (d == null || d.isDisposed()) {
				return;
			}
			d.asyncExec(() -> {
				MessageConsole c = console();
				MessageConsoleStream s = c.newMessageStream();
				try {
					s.print(text);
				} finally {
					try {
						s.close();
					} catch (IOException ignore) {
						// 무시
					}
				}
				try {
					ConsolePlugin.getDefault().getConsoleManager().showConsoleView(c);
				} catch (Exception ignore) {
					// 콘솔 뷰 표시 실패는 무시
				}
			});
		} catch (Exception ignore) {
			// 무시
		}
	}

	private static MessageConsole console() {
		IConsoleManager mgr = ConsolePlugin.getDefault().getConsoleManager();
		for (IConsole c : mgr.getConsoles()) {
			if (NAME.equals(c.getName()) && c instanceof MessageConsole) {
				return (MessageConsole) c;
			}
		}
		MessageConsole c = new MessageConsole(NAME, null);
		mgr.addConsoles(new IConsole[] { c });
		return c;
	}
}
