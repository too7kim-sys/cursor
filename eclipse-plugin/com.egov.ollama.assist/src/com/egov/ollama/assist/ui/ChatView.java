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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.CodebaseIndex;
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

	private Combo projectCombo;
	private StyledText output;
	private Text input;
	private Button sendBtn;
	private Button stopBtn;
	private Button agentCheck;
	private Button indexBtn;
	private Button indexDelBtn;
	private volatile boolean busy;
	private volatile AtomicBoolean currentCancel;
	private volatile CodebaseIndex index;
	private volatile File indexRoot;
	private final java.util.List<Object> history = new java.util.ArrayList<>();
	private static final int MAX_HISTORY = 16;

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(3, false));

		projectCombo = new Combo(parent, SWT.READ_ONLY | SWT.DROP_DOWN);
		projectCombo.setToolTipText("작업 대상 프로젝트 (Agent·색인이 이 프로젝트에 적용됩니다)");
		projectCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
		projectCombo.addFocusListener(new org.eclipse.swt.events.FocusAdapter() {
			@Override
			public void focusGained(org.eclipse.swt.events.FocusEvent e) {
				populateProjects();
			}
		});
		populateProjects();

		output = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
		output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1));
		output.setText("Ollama Assist 준비됨.\n"
				+ "· 일반 질문: 입력 후 [보내기] 또는 Ctrl+Enter\n"
				+ "· Agent 모드: AI가 프로젝트를 직접 검색/읽기/수정/생성합니다(변경 전 확인).\n"
				+ "· 서버/모델/명령실행 허용: Window > Preferences > Ollama Assist\n");

		agentCheck = new Button(parent, SWT.CHECK);
		agentCheck.setText("Agent 모드");
		agentCheck.setToolTipText("파일 자동 탐색·수정(검색/읽기/부분수정/생성/명령/검증)");
		agentCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		indexBtn = new Button(parent, SWT.PUSH);
		indexBtn.setText("색인");
		indexBtn.setToolTipText("활성 프로젝트를 임베딩으로 색인(RAG). Agent 의 semantic_search 에 사용");
		indexBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
		indexBtn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				buildIndex();
			}
		});

		indexDelBtn = new Button(parent, SWT.PUSH);
		indexDelBtn.setText("색인삭제");
		indexDelBtn.setToolTipText("저장된 코드 색인(.ollama-assist/index.json)과 메모리 색인을 삭제");
		indexDelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
		indexDelBtn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				deleteIndex();
			}
		});

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

		// 뷰 툴바: 대화 지우기 / 저장
		org.eclipse.jface.action.IToolBarManager tb = getViewSite().getActionBars().getToolBarManager();
		tb.add(new org.eclipse.jface.action.Action("지우기") {
			@Override
			public void run() {
				if (output != null && !output.isDisposed()) {
					output.setText("");
				}
				history.clear();
			}
		});
		tb.add(new org.eclipse.jface.action.Action("저장") {
			@Override
			public void run() {
				saveConversation();
			}
		});
	}

	/** 대화 내용을 파일로 저장. */
	private void saveConversation() {
		org.eclipse.swt.widgets.FileDialog fd = new org.eclipse.swt.widgets.FileDialog(output.getShell(), SWT.SAVE);
		fd.setFileName("ollama-chat.md");
		fd.setFilterExtensions(new String[] { "*.md", "*.txt", "*.*" });
		fd.setOverwrite(true);
		String path = fd.open();
		if (path == null) {
			return;
		}
		try {
			java.nio.file.Files.write(new java.io.File(path).toPath(),
					output.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			append("\n💾 저장됨: " + path + "\n");
		} catch (Exception e) {
			append("\n[저장 실패] " + e.getMessage() + "\n");
		}
	}

	/** 편집기에서 선택한 코드를 입력창에 코드블록으로 채우고 포커스(핸들러에서 호출). */
	public void prefillFromEditor(String code, String lang) {
		if (input == null || input.isDisposed()) {
			return;
		}
		String fence = lang == null ? "" : lang;
		input.setText("```" + fence + "\n" + code + "\n```\n" + input.getText());
		input.setFocus();
	}

	private void doSend() {
		String text = input.getText().trim();
		if (text.isEmpty() || busy) {
			return;
		}
		input.setText("");
		// 슬래시 명령 처리
		if (SlashCommands.isCommand(text)) {
			String cmd = SlashCommands.command(text);
			if ("/clear".equals(cmd)) {
				if (output != null && !output.isDisposed()) {
					output.setText("");
				}
				history.clear();
				return;
			}
			if ("/help".equals(cmd)) {
				append("\n" + SlashCommands.help() + "\n");
				return;
			}
			if ("/commit".equals(cmd)) {
				runCommitMessage();
				return;
			}
			String expanded = SlashCommands.expand(text);
			if (expanded != null) {
				text = expanded;
			}
			// 알 수 없는 슬래시 명령은 그대로 전송
		}
		if (agentCheck.getSelection()) {
			runAgent(text);
		} else {
			ask(text);
		}
	}

	private static java.util.Map<String, Object> msg(String role, String content) {
		java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("role", role);
		m.put("content", content);
		return m;
	}

	private void addHistory(String user, String assistant) {
		history.add(msg("user", user));
		history.add(msg("assistant", assistant));
		while (history.size() > MAX_HISTORY) {
			history.remove(0);
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
		final double temperature = parseTemp(store.getString(PreferenceConstants.P_TEMPERATURE));
		final boolean chatRag = store.getBoolean(PreferenceConstants.P_CHAT_RAG);
		final String embedModel = store.getString(PreferenceConstants.P_EMBED_MODEL);
		final File root = selectedProjectDir();

		append("\n\n🧑 나:\n" + userPrompt + "\n\n🤖 " + model + ":\n");
		startBusy(false);
		Job job = new Job("Ollama 요청") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					// 프로젝트 규칙(AGENTS.md) 주입
					String effSystem = system;
					if (root != null) {
						String rules = OllamaAgent.readProjectRules(root);
						if (rules != null) {
							effSystem = effSystem + "\n\n[프로젝트 규칙(AGENTS.md)]\n" + rules;
						}
					}
					// 코드 자동 참고(RAG): 색인이 있으면 관련 코드 발췌를 질문에 첨부
					String effUser = userPrompt;
					if (chatRag && root != null) {
						ensureIndex(root, base, embedModel);
						if (index != null && index.size() > 0 && root.equals(indexRoot)) {
							try {
								String ctx = index.search(userPrompt, 4);
								effUser = "다음은 프로젝트에서 관련성 높은 코드 발췌입니다. 참고해 한국어로 답하세요:\n\n"
										+ ctx + "\n[질문]\n" + userPrompt;
								appendAsync("(관련 코드 참고됨)\n");
							} catch (Exception ignore) {
								// 검색 실패 시 일반 질문으로 진행
							}
						}
					}
					// 멀티턴: 시스템 + 이전 대화 + 현재 질문
					java.util.List<Object> messages = new java.util.ArrayList<>();
					messages.add(msg("system", effSystem));
					messages.addAll(history);
					messages.add(msg("user", effUser));
					final StringBuilder asst = new StringBuilder();
					OllamaClient.chatStreamMessages(base, model, messages, temperature, delta -> {
						asst.append(delta);
						appendAsync(delta);
					});
					addHistory(userPrompt, asst.toString());
				} catch (Exception ex) {
					appendAsync("\n\n[오류] " + ex.getMessage()
							+ "\n서버 설정(Window > Preferences > Ollama Assist)과 연결을 확인하세요.");
					Activator.logError("채팅 요청 실패", ex);
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

	/** git 변경분(diff)으로 커밋 메시지를 생성. */
	private void runCommitMessage() {
		if (busy) {
			return;
		}
		final File root = selectedProjectDir();
		if (root == null) {
			append("\n[안내] 활성 프로젝트를 찾을 수 없습니다.\n");
			return;
		}
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String model = store.getString(PreferenceConstants.P_MODEL);
		final String system = store.getString(PreferenceConstants.P_SYSTEM);
		final double temperature = parseTemp(store.getString(PreferenceConstants.P_TEMPERATURE));

		append("\n\n🧑 나:\n/commit\n\n🤖 " + model + ":\n");
		startBusy(false);
		Job job = new Job("Ollama 커밋 메시지") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					String diff = GitUtil.diff(root);
					if (diff == null || diff.trim().isEmpty()) {
						appendAsync("\n[안내] git 변경분이 없습니다. 변경을 스테이징(git add) 후 다시 시도하세요.\n");
						return Status.OK_STATUS;
					}
					if (diff.length() > 15000) {
						diff = diff.substring(0, 15000) + "\n...(생략)";
					}
					String prompt = "다음 git diff 에 대한 커밋 메시지를 한국어로 작성해줘. "
							+ "첫 줄 제목(50자 이내) + 빈 줄 + 본문(변경 이유/요약) 형식으로:\n\n" + diff;
					OllamaClient.chatStream(base, model, system, prompt, temperature, delta -> appendAsync(delta));
				} catch (Exception ex) {
					appendAsync("\n\n[오류] " + ex.getMessage() + "\n");
					Activator.logError("커밋 메시지 생성 실패", ex);
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
		final File root = selectedProjectDir(); // UI 스레드에서 계산(드롭다운 선택 우선)
		if (root == null) {
			append("\n[안내] 활성 프로젝트를 찾을 수 없습니다. 편집기에서 프로젝트 파일을 연 뒤 다시 시도하세요.\n");
			return;
		}
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String model = store.getString(PreferenceConstants.P_MODEL);
		final String system = store.getString(PreferenceConstants.P_SYSTEM);
		final boolean enableRun = store.getBoolean(PreferenceConstants.P_ENABLE_RUN);
		final String embedModel = store.getString(PreferenceConstants.P_EMBED_MODEL);
		final double temperature = parseTemp(store.getString(PreferenceConstants.P_TEMPERATURE));
		final String verifyCmd = store.getString(PreferenceConstants.P_VERIFY_CMD);

		final AtomicBoolean cancel = new AtomicBoolean(false);
		currentCancel = cancel;

		append("\n\n🧑 나(Agent):\n" + userPrompt + "\n\n🤖 " + model + " [Agent @ " + root.getName() + "]:\n");
		startBusy(true);
		Job job = new Job("Ollama Agent") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					ensureIndex(root, base, embedModel); // 저장된 색인이 있으면 로드
					final OllamaAgent.Retriever retriever = (index != null && index.size() > 0
							&& root.equals(indexRoot)) ? (q -> index.search(q, 5)) : null;
					OllamaAgent agent = new OllamaAgent(base, model, system, root, enableRun,
							temperature, verifyCmd,
							text -> appendAsync(text),
							(title, message) -> confirm(title, message),
							cancel::get,
							new EclipseEnvironment(),
							retriever);
					agent.run(userPrompt);
					WorkspaceUtil.refresh();
				} catch (Exception ex) {
					appendAsync("\n\n[오류] " + ex.getMessage() + "\n");
					Activator.logError("Agent 실행 실패", ex);
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

	// ===================== 코드 색인(RAG) =====================

	private void buildIndex() {
		if (busy) {
			return;
		}
		final File root = selectedProjectDir();
		if (root == null) {
			append("\n[안내] 활성 프로젝트를 찾을 수 없습니다.\n");
			return;
		}
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String embedModel = store.getString(PreferenceConstants.P_EMBED_MODEL);

		final AtomicBoolean cancel = new AtomicBoolean(false);
		currentCancel = cancel;
		append("\n\n📚 코드 색인 시작: " + root.getName() + " (임베딩 모델: " + embedModel + ")\n");
		startBusy(true);
		Job job = new Job("Ollama 코드 색인") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					CodebaseIndex idx = new CodebaseIndex(root,
							text -> OllamaClient.embed(base, embedModel, text));
					// 기존 색인이 있으면 로드해 변경분만 재임베딩(증분)
					boolean incremental = false;
					try {
						incremental = idx.load(new File(root, ".ollama-assist/index.json"));
					} catch (Exception ignore) {
						// 손상된 색인이면 전체 재색인
					}
					if (incremental) {
						appendAsync("\n(기존 색인 로드 — 변경분만 갱신)\n");
					}
					int n = idx.build(incremental, cancel::get, m -> appendAsync("\n" + m));
					if (n > 0) {
						idx.save(new File(root, ".ollama-assist/index.json"));
						index = idx;
						indexRoot = root;
						appendAsync("\n✅ 색인 완료 및 저장: " + n + " 청크 (.ollama-assist/index.json)\n");
					} else {
						appendAsync("\n[안내] 색인된 내용이 없습니다(취소되었거나 대상 파일 없음).\n");
					}
				} catch (Exception ex) {
					appendAsync("\n\n[색인 오류] " + ex.getMessage()
							+ "\n임베딩 모델이 서버에 있는지(ollama list), Preferences 의 임베딩 모델명을 확인하세요.\n");
					Activator.logError("코드 색인 실패", ex);
				} finally {
					endBusyAsync();
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.schedule();
	}

	/** 저장된 색인 파일과 메모리 색인을 삭제. */
	private void deleteIndex() {
		if (busy) {
			return;
		}
		File root = selectedProjectDir();
		if (root == null) {
			append("\n[안내] 활성 프로젝트를 찾을 수 없습니다.\n");
			return;
		}
		File f = new File(root, ".ollama-assist/index.json");
		boolean exists = f.isFile();
		boolean ok = MessageDialog.openConfirm(output.getShell(), "색인 삭제",
				"코드 색인을 삭제합니다:\n" + f.getAbsolutePath()
						+ (exists ? "" : "\n\n(저장된 파일이 없어 메모리 색인만 해제합니다)"));
		if (!ok) {
			return;
		}
		boolean deleted = !exists || f.delete();
		index = null;
		indexRoot = null;
		append("\n🗑 색인 삭제됨" + (exists && !deleted ? " (파일 삭제 실패 — 사용 중이거나 권한 확인)" : "") + "\n");
		WorkspaceUtil.refresh();
	}

	/** 메모리에 색인이 없으면 디스크에서 로드 시도(Job 스레드에서 호출). */
	private void ensureIndex(File root, String base, String embedModel) {
		if (index != null && root.equals(indexRoot)) {
			return;
		}
		File f = new File(root, ".ollama-assist/index.json");
		CodebaseIndex idx = new CodebaseIndex(root, text -> OllamaClient.embed(base, embedModel, text));
		try {
			if (idx.load(f)) {
				index = idx;
				indexRoot = root;
				appendAsync("(저장된 코드 색인 로드: " + idx.size() + " 청크)\n");
			}
		} catch (Exception ignore) {
			// 색인 없으면 무시
		}
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
				DiffConfirmDialog dlg = new DiffConfirmDialog(shell, title, message);
				result[0] = dlg.open() == org.eclipse.jface.window.Window.OK;
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
		if (indexBtn != null && !indexBtn.isDisposed()) {
			indexBtn.setEnabled(enabled);
		}
		if (indexDelBtn != null && !indexDelBtn.isDisposed()) {
			indexDelBtn.setEnabled(enabled);
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

	/** 대상 프로젝트 드롭다운을 열린 프로젝트로 채운다(UI 스레드). */
	private void populateProjects() {
		if (projectCombo == null || projectCombo.isDisposed()) {
			return;
		}
		String prev = projectCombo.getText();
		java.util.List<String> names = WorkspaceUtil.openProjectNames();
		projectCombo.setItems(names.toArray(new String[0]));
		String target = prev;
		if (target == null || target.isEmpty() || !names.contains(target)) {
			target = WorkspaceUtil.activeProjectName();
		}
		if (target != null && names.contains(target)) {
			projectCombo.setText(target);
		} else if (!names.isEmpty()) {
			projectCombo.select(0);
		}
	}

	/** 드롭다운에서 선택한 프로젝트 디렉터리(없으면 활성 편집기 기준). */
	private File selectedProjectDir() {
		String name = (projectCombo != null && !projectCombo.isDisposed()) ? projectCombo.getText() : null;
		if (name != null && !name.isEmpty()) {
			File f = WorkspaceUtil.projectDir(name);
			if (f != null) {
				return f;
			}
		}
		return WorkspaceUtil.activeProjectDir();
	}

	/** temperature 문자열을 0.0~2.0 범위 double 로 파싱(잘못되면 0.2). */
	private static double parseTemp(String s) {
		try {
			double t = Double.parseDouble(s.trim());
			if (t < 0) {
				return 0;
			}
			if (t > 2) {
				return 2;
			}
			return t;
		} catch (Exception e) {
			return 0.2;
		}
	}

	@Override
	public void setFocus() {
		if (input != null && !input.isDisposed()) {
			input.setFocus();
		}
	}
}
