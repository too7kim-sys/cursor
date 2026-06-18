package com.egov.ollama.assist.ui;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.fieldassist.ContentProposal;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.fieldassist.IContentProposalProvider;
import org.eclipse.jface.fieldassist.TextContentAdapter;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.ChangeParser;
import com.egov.ollama.assist.CodebaseIndex;
import com.egov.ollama.assist.EclipseEnvironment;
import com.egov.ollama.assist.FileProposals;
import com.egov.ollama.assist.GitUtil;
import com.egov.ollama.assist.MarkdownScanner;
import com.egov.ollama.assist.Mentions;
import com.egov.ollama.assist.OllamaAgent;
import com.egov.ollama.assist.OllamaClient;
import com.egov.ollama.assist.SessionStore;
import com.egov.ollama.assist.SlashCommands;
import com.egov.ollama.assist.SymbolIndex;
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
	private boolean dark;
	private boolean showProgress = true;
	private Color codeBgLight;
	private Color codeBgDark;
	private Color darkBg;
	private Color darkFg;
	private Color dimLight;
	private Color dimDark;
	private SessionStore sessions;
	private String sessionId;
	private String sessionName = "대화 1";
	private java.util.List<String> cachedFiles;
	private File cachedFilesRoot;
	private java.util.Map<String, String> cachedSymbols;
	private File cachedSymbolsRoot;

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
				+ "· 일반 질문: 입력 후 Enter(전송), Shift+Enter(줄바꿈)\n"
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
				// Enter = 전송, Shift+Enter = 줄바꿈
				if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
					if ((e.stateMask & SWT.SHIFT) != 0) {
						return; // 줄바꿈 허용
					}
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
		org.eclipse.jface.action.Action sessionAction = new org.eclipse.jface.action.Action("세션",
				org.eclipse.jface.action.IAction.AS_DROP_DOWN_MENU) {
			@Override
			public void run() {
				newSessionPrompt();
			}
		};
		sessionAction.setToolTipText("대화 세션 — 클릭: 새 세션 / 드롭다운: 전환·이름변경·삭제");
		sessionAction.setMenuCreator(new IMenuCreator() {
			private Menu menu;

			@Override
			public void dispose() {
				if (menu != null && !menu.isDisposed()) {
					menu.dispose();
				}
			}

			@Override
			public Menu getMenu(Menu parent) {
				return null;
			}

			@Override
			public Menu getMenu(Control parent) {
				if (menu != null && !menu.isDisposed()) {
					menu.dispose();
				}
				menu = new Menu(parent);
				buildSessionMenu(menu);
				return menu;
			}
		});
		tb.add(sessionAction);
		tb.add(new org.eclipse.jface.action.Action("저장") {
			@Override
			public void run() {
				saveConversation();
			}
		});
		tb.add(new org.eclipse.jface.action.Action("코드복사") {
			@Override
			public void run() {
				copyLastCode();
			}
		});
		org.eclipse.jface.action.Action applyAction = new org.eclipse.jface.action.Action("에디터적용") {
			@Override
			public void run() {
				applyLastCodeToEditor();
			}
		};
		applyAction.setToolTipText("마지막 코드블록을 현재 편집기에 적용(선택 영역 교체 또는 커서 삽입, diff 확인)");
		tb.add(applyAction);
		org.eclipse.jface.action.Action applyChangesAction = new org.eclipse.jface.action.Action("변경적용") {
			@Override
			public void run() {
				applyChangesFromChat();
			}
		};
		applyChangesAction.setToolTipText("답변의 파일 지정 코드블록들을 모아 멀티파일 일괄 적용(검토 패널)");
		tb.add(applyChangesAction);
		org.eclipse.jface.action.Action darkAction = new org.eclipse.jface.action.Action("다크",
				org.eclipse.jface.action.IAction.AS_CHECK_BOX) {
			@Override
			public void run() {
				applyTheme(isChecked());
			}
		};
		darkAction.setToolTipText("다크 테마 켜기/끄기");
		tb.add(darkAction);
		org.eclipse.jface.action.Action hideProgressAction = new org.eclipse.jface.action.Action("진행감추기",
				org.eclipse.jface.action.IAction.AS_CHECK_BOX) {
			@Override
			public void run() {
				showProgress = !isChecked();
			}
		};
		hideProgressAction.setToolTipText("단계별 진행 과정(도구 호출 등)을 숨기고 답변만 표시");
		tb.add(hideProgressAction);

		// 구문강조용 색상 생성 + 정리
		Color disp1 = new Color(parent.getDisplay(), 240, 240, 240);
		Color disp2 = new Color(parent.getDisplay(), 55, 55, 60);
		darkBg = new Color(parent.getDisplay(), 30, 30, 34);
		darkFg = new Color(parent.getDisplay(), 220, 220, 220);
		dimLight = new Color(parent.getDisplay(), 140, 140, 140);
		dimDark = new Color(parent.getDisplay(), 120, 120, 128);
		codeBgLight = disp1;
		codeBgDark = disp2;
		// Eclipse CSS 테마 엔진이 우리 색을 흰색으로 덮어쓰지 않도록 위젯 CSS 적용을 해제
		output.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);
		input.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);
		output.addDisposeListener(e -> {
			codeBgLight.dispose();
			codeBgDark.dispose();
			darkBg.dispose();
			darkFg.dispose();
			dimLight.dispose();
			dimDark.dispose();
		});

		// @파일 자동완성
		setupFileAutocomplete();

		// 세션 저장소 초기화 + 현재 세션 복원
		sessions = new SessionStore(new File(Activator.getDefault().getStateLocation().toFile(), "sessions"));
		sessionId = sessions.getCurrent();
		if (sessionId == null || sessions.load(sessionId) == null) {
			sessionId = sessions.create(sessionName);
			sessions.setCurrent(sessionId);
		}
		loadSession();
		restyle();
	}

	// ===================== 표시(구문강조/테마/복사) =====================

	/** 출력 전체를 다시 스캔해 코드블록/굵게/헤더 스타일을 적용. */
	private void restyle() {
		if (output == null || output.isDisposed()) {
			return;
		}
		output.setStyleRanges(new StyleRange[0]);
		String text = output.getText();
		Font mono = JFaceResources.getTextFont();
		Color cbg = dark ? codeBgDark : codeBgLight;
		Color dim = dark ? dimDark : dimLight;
		for (MarkdownScanner.Span sp : MarkdownScanner.scan(text)) {
			int len = Math.min(sp.length, text.length() - sp.start);
			if (len <= 0) {
				continue;
			}
			StyleRange r = new StyleRange();
			r.start = sp.start;
			r.length = len;
			switch (sp.kind) {
			case CODE:
				r.font = mono;
				r.background = cbg;
				break;
			case HEADER:
			case BOLD:
				r.fontStyle = SWT.BOLD;
				break;
			case PROGRESS:
				r.foreground = dim; // 진행/상태 줄은 흐리게 → 답변이 도드라지게
				break;
			default:
				break;
			}
			output.setStyleRange(r);
		}
		saveSession(); // 완료된 대화/히스토리 영속화(재시작 복원용)
	}

	private void restyleAsync() {
		Display d = Display.getDefault();
		if (d != null && !d.isDisposed()) {
			d.asyncExec(this::restyle);
		}
	}

	private void applyTheme(boolean on) {
		dark = on;
		if (output != null && !output.isDisposed()) {
			output.setBackground(dark ? darkBg : null);
			output.setForeground(dark ? darkFg : null);
		}
		if (input != null && !input.isDisposed()) {
			input.setBackground(dark ? darkBg : null);
			input.setForeground(dark ? darkFg : null);
		}
		restyle();
	}

	/** 캐럿이 코드블록 안이면 그 블록, 아니면 마지막 코드블록을 반환. */
	private String currentCodeBlock() {
		if (output == null || output.isDisposed()) {
			return null;
		}
		String text = output.getText();
		String code = MarkdownScanner.codeBlockAt(text, output.getCaretOffset());
		return code != null ? code : MarkdownScanner.lastCodeBlock(text);
	}

	private void copyLastCode() {
		String code = currentCodeBlock();
		if (code == null || code.isEmpty()) {
			append("\n[안내] 복사할 코드블록이 없습니다.\n");
			return;
		}
		Clipboard cb = new Clipboard(output.getDisplay());
		try {
			cb.setContents(new Object[] { code }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			cb.dispose();
		}
		append("\n📋 코드블록을 클립보드에 복사했습니다.\n");
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

	// ===================== 에디터 적용 / @멘션 / 세션 =====================

	/** 캐럿 위치(또는 마지막) 코드블록을 활성 편집기에 적용(선택 영역 교체/커서 삽입). */
	private void applyLastCodeToEditor() {
		String code = currentCodeBlock();
		if (code == null || code.isEmpty()) {
			append("\n[안내] 적용할 코드블록이 없습니다.\n");
			return;
		}
		IEditorPart ep = activeEditor();
		if (!(ep instanceof ITextEditor)) {
			MessageDialog.openInformation(output.getShell(), "에디터 적용", "먼저 코드를 적용할 편집기를 여세요.");
			return;
		}
		ITextEditor te = (ITextEditor) ep;
		IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
		ISelection sel = te.getSelectionProvider().getSelection();
		if (doc == null || !(sel instanceof ITextSelection)) {
			return;
		}
		ITextSelection ts = (ITextSelection) sel;
		int offset = ts.getOffset();
		int length = ts.getLength();
		String original;
		try {
			original = (length > 0) ? doc.get(offset, length) : "";
		} catch (BadLocationException e) {
			return;
		}
		com.egov.ollama.assist.handlers.EditorPreview.apply(output.getShell(), doc, te, offset, length, original, code,
				"에디터 적용");
	}

	/** 답변에서 파일 지정 코드블록들을 모아 멀티파일 검토 패널로 일괄 적용. 없으면 단일 블록 에디터 적용으로 대체. */
	private void applyChangesFromChat() {
		if (output == null || output.isDisposed()) {
			return;
		}
		java.util.List<ChangeParser.Change> changes = ChangeParser.parse(output.getText());
		if (changes.isEmpty()) {
			applyLastCodeToEditor();
			return;
		}
		File root = selectedProjectDir();
		if (root == null) {
			MessageDialog.openInformation(output.getShell(), "변경 적용", "먼저 대상 프로젝트를 선택하세요.");
			return;
		}
		ChangeReviewDialog dlg = new ChangeReviewDialog(output.getShell(), changes, root);
		if (dlg.open() != org.eclipse.jface.window.Window.OK) {
			return;
		}
		java.util.List<ChangeParser.Change> accepted = dlg.getAccepted();
		int ok = 0;
		for (ChangeParser.Change ch : accepted) {
			if (writeProjectFile(root, ch.path, ch.content)) {
				ok++;
			}
		}
		WorkspaceUtil.refresh();
		cachedFiles = null; // 새 파일 반영되도록 캐시 무효화
		cachedSymbols = null;
		append("\n✅ 멀티파일 변경 적용: " + ok + "/" + accepted.size() + " 파일\n");
	}

	/** 에이전트 실행 후 변경 파일 요약을 출력하고, 2개 이상이면 되돌리기 검토 패널을 띄운다. */
	private void reviewAgentChangesAsync(java.util.List<String[]> raw, File root) {
		if (raw == null || raw.isEmpty() || root == null) {
			return;
		}
		// 경로별로 합침: 최초 'before' 유지, 최종 'after' 갱신
		java.util.LinkedHashMap<String, String[]> map = new java.util.LinkedHashMap<>();
		for (String[] c : raw) {
			if (map.containsKey(c[0])) {
				map.get(c[0])[2] = c[2];
			} else {
				map.put(c[0], new String[] { c[0], c[1], c[2] });
			}
		}
		final java.util.List<String[]> distinct = new java.util.ArrayList<>(map.values());
		StringBuilder sb = new StringBuilder("\n📝 에이전트가 변경한 파일 " + distinct.size() + "개:\n");
		for (String[] c : distinct) {
			sb.append("  • ").append(c[0]).append('\n');
		}
		appendAsync(sb.toString());
		if (distinct.size() < 2) {
			return; // 단일 파일은 패널 생략(이미 변경 시 확인함)
		}
		Display d = Display.getDefault();
		if (d == null || d.isDisposed()) {
			return;
		}
		d.asyncExec(() -> {
			java.util.List<ChangeParser.Change> reverts = new java.util.ArrayList<>();
			for (String[] c : distinct) {
				reverts.add(new ChangeParser.Change(c[0], c[1])); // content=변경 전(되돌림 대상)
			}
			ChangeReviewDialog dlg = new ChangeReviewDialog(output.getShell(), reverts, root,
					"에이전트 변경 검토 — 되돌릴 파일 선택(" + distinct.size() + ")", false);
			if (dlg.open() != org.eclipse.jface.window.Window.OK) {
				return;
			}
			int n = 0;
			for (ChangeParser.Change ch : dlg.getAccepted()) {
				if (writeProjectFile(root, ch.path, ch.content)) {
					n++;
				}
			}
			if (n > 0) {
				WorkspaceUtil.refresh();
				append("\n↩️ 되돌린 파일: " + n + "개\n");
			}
		});
	}

	private boolean writeProjectFile(File root, String rel, String content) {
		try {
			File f = new File(root, rel);
			if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) {
				return false; // 프로젝트 밖 경로 차단
			}
			File parent = f.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			java.nio.file.Files.write(f.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return true;
		} catch (Exception e) {
			Activator.logError("파일 쓰기 실패: " + rel, e);
			return false;
		}
	}

	/** 입력의 @파일/@선택 멘션을 컨텍스트(코드블록)로 첨부한 프롬프트를 반환. */
	private String resolveMentions(String text) {
		java.util.List<String> names = Mentions.parse(text);
		if (names.isEmpty()) {
			return text;
		}
		StringBuilder ctx = new StringBuilder();
		for (String name : names) {
			if (Mentions.isSelection(name)) {
				String selCode = activeEditorSelection();
				if (selCode != null && !selCode.isEmpty()) {
					ctx.append("\n[@selection 현재 편집기 선택]\n```\n").append(selCode).append("\n```\n");
				}
			} else {
				String content = readProjectFile(name);
				if (content != null) {
					ctx.append("\n[@").append(name).append("]\n```\n").append(content).append("\n```\n");
				} else {
					// 파일이 아니면 심볼로 간주: 정의가 있는 파일의 주변 스니펫 첨부
					String rel = projectSymbols().get(name);
					String fc = rel == null ? null : readProjectFile(rel);
					if (fc != null) {
						int line = SymbolIndex.defLine(fc, name);
						String snip = line >= 0 ? SymbolIndex.snippet(fc, line, 25) : fc;
						if (snip.length() > 4000) {
							snip = snip.substring(0, 4000) + "\n...(생략)";
						}
						ctx.append("\n[@").append(name).append(" — ").append(rel).append("]\n```\n").append(snip)
								.append("\n```\n");
					}
				}
			}
		}
		if (ctx.length() == 0) {
			return text;
		}
		return text + "\n\n----- 참고 컨텍스트 -----" + ctx;
	}

	private IEditorPart activeEditor() {
		try {
			return getSite().getWorkbenchWindow().getActivePage().getActiveEditor();
		} catch (Exception e) {
			return null;
		}
	}

	private String activeEditorSelection() {
		IEditorPart ep = activeEditor();
		if (ep instanceof ITextEditor) {
			ISelection sel = ((ITextEditor) ep).getSelectionProvider().getSelection();
			if (sel instanceof ITextSelection) {
				return ((ITextSelection) sel).getText();
			}
		}
		return null;
	}

	private String readProjectFile(String relPath) {
		File root = selectedProjectDir();
		if (root == null) {
			return null;
		}
		try {
			File f = new File(root, relPath);
			if (!f.isFile()) {
				return null;
			}
			if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) {
				return null; // 프로젝트 밖 경로 차단
			}
			String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
					java.nio.charset.StandardCharsets.UTF_8);
			final int limit = 8000;
			if (s.length() > limit) {
				s = s.substring(0, limit) + "\n...(이하 생략)";
			}
			return s;
		} catch (Exception e) {
			return null;
		}
	}

	/** 현재 대화 내용과 멀티턴 히스토리를 현재 세션에 저장(재시작 후 복원용). */
	private void saveSession() {
		if (sessions == null || sessionId == null) {
			return;
		}
		try {
			SessionStore.Session s = new SessionStore.Session();
			s.name = sessionName;
			s.transcript = (output != null && !output.isDisposed()) ? output.getText() : "";
			s.history = new java.util.ArrayList<>(history);
			sessions.save(sessionId, s);
		} catch (Exception e) {
			Activator.logError("세션 저장 실패", e);
		}
	}

	private void loadSession() {
		if (sessions == null || sessionId == null) {
			return;
		}
		SessionStore.Session s = sessions.load(sessionId);
		if (s == null) {
			return;
		}
		sessionName = (s.name == null || s.name.isEmpty()) ? "대화" : s.name;
		if (output != null && !output.isDisposed()) {
			output.setText(s.transcript == null ? "" : s.transcript);
		}
		history.clear();
		if (s.history != null) {
			history.addAll(s.history);
		}
	}

	private void buildSessionMenu(Menu menu) {
		if (sessions == null) {
			return;
		}
		for (SessionStore.Info info : sessions.list()) {
			MenuItem mi = new MenuItem(menu, SWT.RADIO);
			mi.setText(info.name == null || info.name.isEmpty() ? info.id : info.name);
			mi.setSelection(info.id.equals(sessionId));
			final String id = info.id;
			mi.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if (((MenuItem) e.widget).getSelection()) {
						switchSession(id);
					}
				}
			});
		}
		new MenuItem(menu, SWT.SEPARATOR);
		addPush(menu, "새 세션…", this::newSessionPrompt);
		addPush(menu, "이름 변경…", this::renameSessionPrompt);
		addPush(menu, "세션 검색…", this::searchSessionPrompt);
		addPush(menu, "현재 세션 내보내기(.md)", this::exportSession);
		addPush(menu, "현재 세션 삭제", this::deleteCurrentSession);
	}

	private void searchSessionPrompt() {
		InputDialog dlg = new InputDialog(output.getShell(), "세션 검색", "이름/내용 검색어:", "", null);
		if (dlg.open() != org.eclipse.jface.window.Window.OK) {
			return;
		}
		java.util.List<SessionStore.Info> hits = sessions.search(dlg.getValue());
		if (hits.isEmpty()) {
			MessageDialog.openInformation(output.getShell(), "세션 검색", "일치하는 세션이 없습니다.");
			return;
		}
		org.eclipse.ui.dialogs.ElementListSelectionDialog sel = new org.eclipse.ui.dialogs.ElementListSelectionDialog(
				output.getShell(), new org.eclipse.jface.viewers.LabelProvider() {
					@Override
					public String getText(Object e) {
						return ((SessionStore.Info) e).name;
					}
				});
		sel.setTitle("세션 검색 결과");
		sel.setMessage("전환할 세션을 선택하세요(" + hits.size() + "건):");
		sel.setElements(hits.toArray());
		if (sel.open() == org.eclipse.jface.window.Window.OK && sel.getFirstResult() instanceof SessionStore.Info) {
			switchSession(((SessionStore.Info) sel.getFirstResult()).id);
		}
	}

	private void exportSession() {
		org.eclipse.swt.widgets.FileDialog fd = new org.eclipse.swt.widgets.FileDialog(output.getShell(), SWT.SAVE);
		fd.setFileName((sessionName == null ? "session" : sessionName.replaceAll("[\\\\/:*?\"<>|]", "_")) + ".md");
		fd.setFilterExtensions(new String[] { "*.md", "*.txt", "*.*" });
		fd.setOverwrite(true);
		String path = fd.open();
		if (path == null) {
			return;
		}
		try {
			String body = "# " + sessionName + "\n\n" + (output == null ? "" : output.getText());
			java.nio.file.Files.write(new java.io.File(path).toPath(),
					body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			append("\n💾 세션 내보내기: " + path + "\n");
		} catch (Exception e) {
			append("\n[내보내기 실패] " + e.getMessage() + "\n");
		}
	}

	private void addPush(Menu menu, String text, Runnable action) {
		MenuItem mi = new MenuItem(menu, SWT.PUSH);
		mi.setText(text);
		mi.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				action.run();
			}
		});
	}

	private void switchSession(String id) {
		if (id == null || id.equals(sessionId)) {
			return;
		}
		saveSession();
		sessionId = id;
		sessions.setCurrent(id);
		loadSession();
		restyle();
	}

	private void newSessionPrompt() {
		InputDialog dlg = new InputDialog(output.getShell(), "새 세션", "새 대화 이름:", "대화", null);
		if (dlg.open() != org.eclipse.jface.window.Window.OK) {
			return;
		}
		saveSession();
		String name = dlg.getValue();
		sessionId = sessions.create(name);
		sessions.setCurrent(sessionId);
		sessionName = (name == null || name.trim().isEmpty()) ? "새 대화" : name.trim();
		history.clear();
		if (output != null && !output.isDisposed()) {
			output.setText("");
		}
	}

	private void renameSessionPrompt() {
		InputDialog dlg = new InputDialog(output.getShell(), "세션 이름 변경", "새 이름:", sessionName, null);
		if (dlg.open() != org.eclipse.jface.window.Window.OK) {
			return;
		}
		String name = dlg.getValue();
		if (name != null && !name.trim().isEmpty()) {
			sessionName = name.trim();
			saveSession();
		}
	}

	private void deleteCurrentSession() {
		if (!MessageDialog.openConfirm(output.getShell(), "세션 삭제", "현재 세션 '" + sessionName + "' 을 삭제할까요?")) {
			return;
		}
		sessions.delete(sessionId);
		java.util.List<SessionStore.Info> rest = sessions.list();
		if (rest.isEmpty()) {
			sessionId = sessions.create("대화 1");
		} else {
			sessionId = rest.get(0).id;
		}
		sessions.setCurrent(sessionId);
		loadSession();
		restyle();
	}

	// ----- @파일 자동완성 -----

	private void setupFileAutocomplete() {
		if (input == null || input.isDisposed()) {
			return;
		}
		ContentProposalAdapter adapter = new ContentProposalAdapter(input, new TextContentAdapter(),
				fileProposalProvider(), null, new char[] { '@' });
		adapter.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_IGNORE);
		adapter.addContentProposalListener(this::acceptFileProposal);
	}

	private IContentProposalProvider fileProposalProvider() {
		return (contents, position) -> {
			String token = FileProposals.tokenAt(contents, position);
			if (token == null) {
				return new IContentProposal[0];
			}
			java.util.List<IContentProposal> props = new java.util.ArrayList<>();
			if (Mentions.SELECTION.startsWith(token.toLowerCase())) {
				props.add(new ContentProposal("selection", "@selection (현재 편집기 선택)", "현재 편집기에서 선택한 코드를 첨부"));
			}
			for (String p : FileProposals.match(projectFiles(), token, 40)) {
				props.add(new ContentProposal(p, p, null));
			}
			java.util.Map<String, String> syms = projectSymbols();
			for (String s : FileProposals.match(new java.util.ArrayList<>(syms.keySet()), token, 20)) {
				props.add(new ContentProposal(s, s + "  (심볼)", "심볼 정의: " + syms.get(s)));
			}
			return props.toArray(new IContentProposal[0]);
		};
	}

	private void acceptFileProposal(IContentProposal proposal) {
		if (input == null || input.isDisposed() || proposal == null) {
			return;
		}
		String text = input.getText();
		int caret = input.getCaretPosition();
		String token = FileProposals.tokenAt(text, caret);
		if (token == null) {
			return;
		}
		int at = caret - token.length() - 1; // '@' 위치
		if (at < 0) {
			return;
		}
		String insert = "@" + proposal.getContent();
		input.setText(text.substring(0, at) + insert + text.substring(caret));
		input.setSelection(at + insert.length());
		input.setFocus();
	}

	private java.util.List<String> projectFiles() {
		File root = selectedProjectDir();
		if (root == null) {
			return java.util.Collections.emptyList();
		}
		if (cachedFiles != null && root.equals(cachedFilesRoot)) {
			return cachedFiles;
		}
		java.util.List<String> list = new java.util.ArrayList<>();
		collectFiles(root, root, list, 0);
		cachedFiles = list;
		cachedFilesRoot = root;
		return list;
	}

	/** 심볼→상대경로 맵(클래스/메서드/함수 정의 위치). 최초 호출 시 일부 소스를 스캔해 캐시. */
	private java.util.Map<String, String> projectSymbols() {
		File root = selectedProjectDir();
		if (root == null) {
			return java.util.Collections.emptyMap();
		}
		if (cachedSymbols != null && root.equals(cachedSymbolsRoot)) {
			return cachedSymbols;
		}
		java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
		int scanned = 0;
		for (String rel : projectFiles()) {
			if (scanned >= 600 || map.size() >= 20000) {
				break;
			}
			if (!sourceLike(rel)) {
				continue;
			}
			File f = new File(root, rel);
			if (!f.isFile() || f.length() > 200_000) {
				continue;
			}
			try {
				String content = new String(java.nio.file.Files.readAllBytes(f.toPath()),
						java.nio.charset.StandardCharsets.UTF_8);
				for (String s : SymbolIndex.extractSymbols(content)) {
					map.putIfAbsent(s, rel);
				}
				scanned++;
			} catch (Exception ignore) {
				// 읽기 실패 무시
			}
		}
		cachedSymbols = map;
		cachedSymbolsRoot = root;
		return map;
	}

	private static boolean sourceLike(String rel) {
		String l = rel.toLowerCase();
		return l.endsWith(".java") || l.endsWith(".js") || l.endsWith(".ts") || l.endsWith(".py") || l.endsWith(".cs")
				|| l.endsWith(".go") || l.endsWith(".kt") || l.endsWith(".jsp") || l.endsWith(".xfdl")
				|| l.endsWith(".xjs");
	}

	private void collectFiles(File base, File dir, java.util.List<String> out, int depth) {
		if (out.size() >= 3000 || depth > 12) {
			return;
		}
		File[] kids = dir.listFiles();
		if (kids == null) {
			return;
		}
		for (File f : kids) {
			String name = f.getName();
			if (name.startsWith(".") || name.equals("bin") || name.equals("node_modules") || name.equals("target")
					|| name.equals("build")) {
				continue;
			}
			if (f.isDirectory()) {
				collectFiles(base, f, out, depth + 1);
			} else {
				out.add(base.toPath().relativize(f.toPath()).toString().replace('\\', '/'));
			}
			if (out.size() >= 3000) {
				return;
			}
		}
	}

	@Override
	public void dispose() {
		saveSession();
		super.dispose();
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
		// @파일 / @선택 멘션을 컨텍스트로 첨부
		text = resolveMentions(text);
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
		int vr = 3;
		try {
			vr = Integer.parseInt(store.getString(PreferenceConstants.P_VERIFY_ROUNDS).trim());
		} catch (Exception ignore) {
			// 기본 3
		}
		final int verifyRounds = vr;

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
							new OllamaAgent.Logger() {
								@Override
								public void log(String text) {
									appendAsync(text);
								}

								@Override
								public void progress(String text) {
									if (showProgress) {
										appendAsync(text);
									}
								}
							},
							(title, message) -> confirm(title, message),
							cancel::get,
							new EclipseEnvironment(),
							retriever);
					agent.setMaxVerify(verifyRounds);
					agent.run(userPrompt);
					WorkspaceUtil.refresh();
					reviewAgentChangesAsync(agent.getAppliedChanges(), root);
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
		restyleAsync();
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
