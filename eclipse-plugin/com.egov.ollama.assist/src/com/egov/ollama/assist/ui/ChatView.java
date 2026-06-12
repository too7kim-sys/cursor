package com.egov.ollama.assist.ui;

import java.io.File;

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
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.OllamaAgent;
import com.egov.ollama.assist.OllamaClient;
import com.egov.ollama.assist.WorkspaceUtil;
import com.egov.ollama.assist.preferences.PreferenceConstants;

/**
 * Ollama 채팅/에이전트 뷰.
 * <ul>
 * <li>일반 모드: /api/chat 스트리밍 채팅</li>
 * <li>Agent 모드: 도구 호출 루프로 프로젝트 파일을 탐색/수정(write 시 확인 다이얼로그)</li>
 * </ul>
 * 모든 네트워크 작업은 백그라운드 Job 에서 수행하고, 출력은 UI 스레드로 안전하게 전달한다.
 */
public class ChatView extends ViewPart {

	public static final String ID = "com.egov.ollama.assist.chatView";

	private StyledText output;
	private Text input;
	private Button sendBtn;
	private Button agentCheck;
	private volatile boolean busy;

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(2, false));

		output = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
		output.setText("Ollama Assist 준비됨.\n"
				+ "· 일반 질문: 입력 후 [보내기] 또는 Ctrl+Enter\n"
				+ "· Agent 모드 체크 시: AI가 프로젝트 파일을 직접 읽고 수정합니다(수정 전 확인).\n"
				+ "· 서버/모델: Window > Preferences > Ollama Assist\n");

		agentCheck = new Button(parent, SWT.CHECK);
		agentCheck.setText("Agent 모드 (파일 자동 탐색·수정)");
		agentCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

		input = new Text(parent, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		GridData inData = new GridData(SWT.FILL, SWT.FILL, true, false);
		inData.heightHint = 60;
		input.setLayoutData(inData);
		input.addKeyListener(new org.eclipse.swt.events.KeyAdapter() {
			@Override
			public void keyPressed(org.eclipse.swt.events.KeyEvent e) {
				// Ctrl+Enter 전송 (멀티라인 Text 에서는 KeyListener 가 안정적)
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

	/** 코드 컨텍스트를 곁들여 질문(핸들러에서 호출). */
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
		startBusy();
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

		append("\n\n🧑 나(Agent):\n" + userPrompt + "\n\n🤖 " + model + " [Agent @ " + root.getName() + "]:\n");
		startBusy();
		Job job = new Job("Ollama Agent") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					OllamaAgent agent = new OllamaAgent(base, model, system, root,
							text -> appendAsync(text),
							(path, oldC, newC) -> confirmWrite(path, oldC, newC));
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
		job.setUser(true);
		job.schedule();
	}

	/** write_file 확인 다이얼로그(UI 스레드 동기 실행). */
	private boolean confirmWrite(final String relPath, final String oldContent, final String newContent) {
		final boolean[] result = { false };
		Display display = Display.getDefault();
		if (display == null || display.isDisposed()) {
			return false;
		}
		display.syncExec(() -> {
			Shell shell = null;
			try {
				shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
			} catch (Exception ignore) {
				// null shell 허용
			}
			String preview = newContent.length() > 1500 ? newContent.substring(0, 1500) + "\n...(미리보기 생략)"
					: newContent;
			String head = oldContent.isEmpty() ? "[새 파일 생성]\n" : "[기존 파일 덮어쓰기]\n";
			result[0] = MessageDialog.openConfirm(shell, "Ollama Agent — 파일 수정 확인",
					head + relPath + "\n\n[새 내용 미리보기]\n" + preview);
		});
		return result[0];
	}

	// ===================== 출력/상태 =====================

	private void startBusy() {
		busy = true;
		setSendEnabled(false);
	}

	private void endBusyAsync() {
		busy = false;
		setSendEnabledAsync(true);
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

	@Override
	public void setFocus() {
		if (input != null && !input.isDisposed()) {
			input.setFocus();
		}
	}
}
