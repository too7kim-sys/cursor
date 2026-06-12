package com.egov.ollama.assist.ui;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
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
import org.eclipse.swt.widgets.Text;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.OllamaClient;
import com.egov.ollama.assist.preferences.PreferenceConstants;

/**
 * Ollama 채팅 뷰. 질문을 보내면 백그라운드 Job 에서 스트리밍 응답을 받아
 * UI 스레드로 안전하게 출력한다.
 */
public class ChatView extends org.eclipse.ui.part.ViewPart {

	public static final String ID = "com.egov.ollama.assist.chatView";

	private StyledText output;
	private Text input;
	private Button sendBtn;
	private volatile boolean busy;

	@Override
	public void createPartControl(Composite parent) {
		GridLayout layout = new GridLayout(2, false);
		parent.setLayout(layout);

		output = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		GridData outData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
		output.setLayoutData(outData);
		output.setText("Ollama Assist 준비됨.\n질문을 입력하고 [보내기] 또는 Ctrl+Enter 를 누르세요.\n"
				+ "서버/모델 변경: Window > Preferences > Ollama Assist\n");

		input = new Text(parent, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		GridData inData = new GridData(SWT.FILL, SWT.FILL, true, false);
		inData.heightHint = 60;
		input.setLayoutData(inData);
		input.addKeyListener(new org.eclipse.swt.events.KeyAdapter() {
			@Override
			public void keyPressed(org.eclipse.swt.events.KeyEvent e) {
				// Ctrl+Enter 로 전송 (멀티라인 Text 에서는 KeyListener 가 안정적)
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
		if (text.isEmpty()) {
			return;
		}
		input.setText("");
		ask(text);
	}

	/**
	 * 코드 컨텍스트를 곁들여 질문한다(핸들러에서 호출).
	 */
	public void askWithCode(String instruction, String code, String lang) {
		String fence = lang == null ? "" : lang;
		String prompt = instruction + "\n\n```" + fence + "\n" + code + "\n```";
		ask(prompt);
	}

	/** 프롬프트를 보내고 스트리밍 응답을 출력한다. */
	public void ask(final String userPrompt) {
		if (busy) {
			append("\n[안내] 이전 요청이 진행 중입니다. 잠시 후 다시 시도하세요.\n");
			return;
		}
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String model = store.getString(PreferenceConstants.P_MODEL);
		final String system = store.getString(PreferenceConstants.P_SYSTEM);

		append("\n\n🧑 나:\n" + userPrompt + "\n\n🤖 " + model + ":\n");
		busy = true;
		setSendEnabled(false);

		Job job = new Job("Ollama 요청") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					OllamaClient.chatStream(base, model, system, userPrompt, delta -> appendAsync(delta));
				} catch (Exception ex) {
					appendAsync("\n\n[오류] " + ex.getMessage()
							+ "\n서버 설정(Window > Preferences > Ollama Assist)과 네트워크 연결을 확인하세요.");
				} finally {
					appendAsync("\n");
					busy = false;
					setSendEnabledAsync(true);
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
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
