package com.egov.ollama.assist.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Activator;

/**
 * GitHub Copilot 식 "고스트 텍스트" 미리보기. 에디터의 StyledText 위에 제안 코드를 회색으로 그려 보여주고,
 * Tab 으로 수락(문서에 실제 삽입), Esc/다른 키/클릭으로 취소한다.
 *
 * <p>주의: 클래식 소스 에디터에는 표준 인라인 제안 API 가 없어 PaintListener 로 직접 그리는 방식이다.
 * 커서가 줄 끝일 때 가장 자연스럽고, 줄 중간/멀티라인에서는 기존 텍스트 위에 겹쳐 그려질 수 있다(실험적).</p>
 */
public final class GhostTextController {

	private final ITextEditor editor;
	private final StyledText st;
	private final IDocument doc;
	private final int offset;
	private final String suggestion;

	private Color ghostColor;
	private PaintListener painter;
	private VerifyKeyListener keys;
	private MouseListener mouse;
	private boolean active;

	private GhostTextController(ITextEditor editor, StyledText st, IDocument doc, int offset, String suggestion) {
		this.editor = editor;
		this.st = st;
		this.doc = doc;
		this.offset = offset;
		this.suggestion = suggestion;
	}

	/** 제안을 고스트로 표시. UI 스레드에서 호출한다. */
	public static void show(ITextEditor editor, StyledText st, IDocument doc, int offset, String suggestion) {
		if (st == null || st.isDisposed() || doc == null || suggestion == null || suggestion.isEmpty()) {
			return;
		}
		new GhostTextController(editor, st, doc, offset, suggestion).install();
	}

	private void install() {
		ghostColor = new Color(st.getDisplay(), 150, 150, 150);
		active = true;

		painter = new PaintListener() {
			@Override
			public void paintControl(PaintEvent e) {
				paint(e);
			}
		};
		keys = new VerifyKeyListener() {
			@Override
			public void verifyKey(org.eclipse.swt.events.VerifyEvent e) {
				if (!active) {
					return;
				}
				if (e.keyCode == SWT.TAB && (e.stateMask & SWT.MODIFIER_MASK) == 0) {
					accept();
					e.doit = false;
				} else if (e.keyCode == SWT.ESC) {
					dismiss();
					e.doit = false;
				} else {
					dismiss(); // 다른 키 입력은 고스트를 닫고 정상 진행
				}
			}
		};
		mouse = new MouseAdapter() {
			@Override
			public void mouseDown(MouseEvent e) {
				dismiss();
			}
		};
		st.addPaintListener(painter);
		st.addVerifyKeyListener(keys);
		st.addMouseListener(mouse);
		st.redraw();
	}

	private void paint(PaintEvent e) {
		if (!active || st.isDisposed()) {
			return;
		}
		try {
			Point loc = st.getLocationAtOffset(offset);
			GC gc = e.gc;
			gc.setForeground(ghostColor);
			gc.setFont(st.getFont());
			int lineH = st.getLineHeight();
			String[] lines = suggestion.split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				int x = (i == 0) ? loc.x : st.getLeftMargin();
				int y = loc.y + i * lineH;
				gc.drawText(lines[i], x, y, true); // 배경 투명
			}
		} catch (Exception ex) {
			// 페인트 중 오류는 무시(레이아웃 변경 등)
			dismiss();
		}
	}

	private void accept() {
		try {
			if (offset <= doc.getLength()) {
				doc.replace(offset, 0, suggestion);
				int end = offset + suggestion.length();
				if (editor != null) {
					editor.selectAndReveal(end, 0);
				}
			}
		} catch (BadLocationException ex) {
			Activator.logError("고스트 텍스트 적용 실패", ex);
		} finally {
			dismiss();
		}
	}

	private void dismiss() {
		if (!active) {
			return;
		}
		active = false;
		if (st != null && !st.isDisposed()) {
			st.removePaintListener(painter);
			st.removeVerifyKeyListener(keys);
			st.removeMouseListener(mouse);
			st.redraw();
		}
		if (ghostColor != null && !ghostColor.isDisposed()) {
			ghostColor.dispose();
		}
	}
}
