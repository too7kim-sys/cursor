package com.egov.ollama.assist.ui;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.EclipseEnvironment;
import com.egov.ollama.assist.OllamaAgent;
import com.egov.ollama.assist.OllamaClient;
import com.egov.ollama.assist.WorkspaceUtil;
import com.egov.ollama.assist.preferences.PreferenceConstants;

/**
 * Ollama 채팅/에이전트 뷰.
 * <ul>
 * <li>일반 모드: /api/chat 스트리밍 채팅</li>
 * <li>Agent 모드: 도구 호출 루프(검색/읽기/부분수정/생성/덮어쓰기/명령실행)로 프로젝트를 직접 다룸</li>
 * </ul>
 */
public class ChatView extends ViewPart {

	public static final String ID = "com.egov.ollama.assist.chatView";

	private StyledText output;
	private Text input;
	private Button sendBtn;
	private Button stopBtn;
	private Button agentCheck;
	private volatile boolean busy;
	private volatile AtomicBoolean currentCancel;

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(3, false));

		output = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1));
		output.setText("Ollama Assist 준비됨.\n"
				+ "· 일반 질문: 입력 후 [보내기] 또는 Ctrl+Enter\n"
				+ "· Agent 모드: AI가 프로젝트를 직접 검색/읽기/수정/생성합니다(변경 전 확인).\n"
				+ "· 서버/모델/명령실행 허용: Window > Preferences > Ollama Assist\n");

		agentCheck = new Button(parent, SWT.CHECK);
		agentCheck.setText("Agent 모드 (파일 자동 탐색·수정)");
		agentCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

		input = new Text(parent, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		GridData inData = new GridData(SWT.FILL, SWT.FILL, true, false);
		inData.heightHint = 60;
		input.setLayoutData(inData);
		input.addKeyListener(new org.eclipse.swt.events.KeyAdapter() {
			@Override
			public void keyPressed(org.eclipse.swt.events.KeyEvent e) {
				if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.CTRL) != 0) {
					e.doit = false;
					doSend();
				}
			}
		});

		sendBtn = new Button(parent, SWT.PUSH);
		sendBtn.setText("보내기");
		sendBtn.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, false));
		sendBtn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				doSend();
			}
		});

		stopBtn = new Button(parent, SWT.PUSH);
		stopBtn.setText("중지");
		stopBtn.setEnabled(false);
		stopBtn.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, false));
		stopBtn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				if (currentCancel != null) {
					currentCancel.set(true);
					append("\n[중지 요청됨 — 현재 단계 후 멈춥니다]\n");
				}
			}
		});
	}

	private void doSend() {
		String text = input.getText().trim();
		if (text.isEmpty() || busy) {
			return;
		}
		input.setText("");
		if (agentCheck.getSelection()) {
			runAgent(text);
		} else {
			ask(text);
		}
	}

	public void askWithCode(String instruction, String code, String lang) {
		String fence = lang == null ? "" : lang;
		ask(instruction + "\n\n```" + fence + "\n" + code + "\n```");
	}

	// ===================== 일반 채팅 =====================

	public void ask(final String userPrompt) {
		if (busy) {
			append("\n[안내] 이전 요청이 진행 중입니다.\n");
			return;
		}
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String model = store.getString(PreferenceConstants.P_MODEL);
		final String system = store.getString(PreferenceConstants.P_SYSTEM);

		append("\n\n🧑 나:\n" + userPrompt + "\n\n🤖 " + model + ":\n");
		startBusy(false);
		Job job = new Job("Ollama 요청") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					OllamaClient.chatStream(base, model, system, userPrompt, delta -> appendAsync(delta));
				} catch (Exception ex) {
					appendAsync("\n\n[오류] " + ex.getMessage()
							+ "\n서버 설정(Window > Preferences > Ollama Assist)과 연결을 확인하세요.");
				} finally {
					appendAsync("\n");
					endBusyAsync();
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
	}

	// ===================== Agent =====================

	private void runAgent(final String userPrompt) {
		final File root = WorkspaceUtil.activeProjectDir(); // UI 스레드에서 계산
		if (root == null) {
			append("\n[안내] 활성 프로젝트를 찾을 수 없습니다. 편집기에서 프로젝트 파일을 연 뒤 다시 시도하세요.\n");
			return;
		}
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String model = store.getString(PreferenceConstants.P_MODEL);
		final String system = store.getString(PreferenceConstants.P_SYSTEM);
		final boolean enableRun = store.getBoolean(PreferenceConstants.P_ENABLE_RUN);

		final AtomicBoolean cancel = new AtomicBoolean(false);
		currentCancel = cancel;

		append("\n\n🧑 나(Agent):\n" + userPrompt + "\n\n🤖 " + model + " [Agent @ " + root.getName() + "]:\n");
		startBusy(true);
		Job job = new Job("Ollama Agent") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					OllamaAgent agent = new OllamaAgent(base, model, system, root, enableRun,
							text -> appendAsync(text),
							(title, message) -> confirm(title, message),
							cancel::get,
							new EclipseEnvironment());
					agent.run(userPrompt);
					WorkspaceUtil.refresh();
				} catch (Exception ex) {
					appendAsync("\n\n[오류] " + ex.getMessage() + "\n");
				} finally {
					appendAsync("\n");
					endBusyAsync();
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.schedule();
	}

	/** 변경/명령 확인 다이얼로그(UI 스레드 동기 실행). */
	private boolean confirm(final String title, final String message) {
		final boolean[] result = { false };
		Display display = Display.getDefault();
		if (display == null || display.isDisposed()) {
			return false;
		}
		display.syncExec(() -> {
			Shell shell = display.getActiveShell();
			boolean tempShell = false;
			if (shell == null) {
				shell = new Shell(display);
				tempShell = true;
			}
			try {
				result[0] = MessageDialog.openConfirm(shell, title, message);
			} finally {
				if (tempShell) {
					shell.dispose();
				}
			}
		});
		return result[0];
	}

	// ===================== 출력/상태 =====================

	private void startBusy(boolean agent) {
		busy = true;
		setSendEnabled(false);
		setStopEnabled(agent);
	}

	private void endBusyAsync() {
		busy = false;
		currentCancel = null;
		setSendEnabledAsync(true);
		setStopEnabledAsync(false);
	}

	private void append(String s) {
		if (output != null && !output.isDisposed()) {
			output.append(s);
			output.setTopIndex(output.getLineCount() - 1);
		}
	}

	private void appendAsync(final String s) {
		Display display = Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> append(s));
	}

	private void setSendEnabled(boolean enabled) {
		if (sendBtn != null && !sendBtn.isDisposed()) {
			sendBtn.setEnabled(enabled);
		}
	}

	private void setSendEnabledAsync(final boolean enabled) {
		Display display = Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> setSendEnabled(enabled));
	}

	private void setStopEnabled(boolean enabled) {
		if (stopBtn != null && !stopBtn.isDisposed()) {
			stopBtn.setEnabled(enabled);
		}
	}

	private void setStopEnabledAsync(final boolean enabled) {
		Display display = Display.getDefault();
		if (display == null || display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> setStopEnabled(enabled));
	}

	@Override
	public void setFocus() {
		if (input != null && !input.isDisposed()) {
			input.setFocus();
		}
	}
}
