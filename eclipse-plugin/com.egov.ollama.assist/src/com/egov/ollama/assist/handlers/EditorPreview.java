package com.egov.ollama.assist.handlers;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.TextDiff;
import com.egov.ollama.assist.preferences.PreferenceConstants;
import com.egov.ollama.assist.ui.DiffConfirmDialog;

/**
 * 변경 적용 공통 UI. 환경설정에 따라 (1) 에디터에 변경을 직접 반영해 보여주고 확인(취소 시 되돌림),
 * 또는 (2) 별도 diff 미리보기 창으로 확인 후 적용한다. UI 스레드에서 호출한다.
 */
public final class EditorPreview {

	private EditorPreview() {
	}

	public static void apply(Shell shell, IDocument doc, ITextEditor te, int offset, int length, String original,
			String edited, String title) {
		if (doc == null || edited == null) {
			return;
		}
		if (original != null && original.equals(edited)) {
			MessageDialog.openInformation(shell, title, "변경 사항이 없습니다.");
			return;
		}
		// 적용 직전 영역 무결성 확인
		try {
			if (offset < 0 || offset + length > doc.getLength()
					|| (original != null && !original.equals(doc.get(offset, length)))) {
				MessageDialog.openWarning(shell, title, "문서가 변경되어 적용을 취소했습니다. 다시 시도하세요.");
				return;
			}
		} catch (BadLocationException e) {
			return;
		}

		if (inlinePreview()) {
			applyInline(shell, doc, te, offset, length, original, edited, title);
		} else {
			applyDialog(shell, doc, te, offset, length, edited, title);
		}
	}

	private static void applyInline(Shell shell, IDocument doc, ITextEditor te, int offset, int length,
			String original, String edited, String title) {
		try {
			doc.replace(offset, length, edited);
			te.selectAndReveal(offset, edited.length());
		} catch (BadLocationException e) {
			Activator.logError("인라인 미리보기 적용 실패", e);
			return;
		}
		boolean keep = MessageDialog.openConfirm(shell, title,
				"변경을 에디터에 적용했습니다. 유지할까요?\n(취소를 누르면 되돌립니다)");
		if (!keep) {
			try {
				doc.replace(offset, edited.length(), original == null ? "" : original);
				te.selectAndReveal(offset, original == null ? 0 : original.length());
			} catch (BadLocationException e) {
				Activator.logError("인라인 미리보기 되돌리기 실패", e);
			}
		}
	}

	private static void applyDialog(Shell shell, IDocument doc, ITextEditor te, int offset, int length, String edited,
			String title) {
		DiffConfirmDialog diff = new DiffConfirmDialog(shell, title,
				TextDiff.unified(safeGet(doc, offset, length), edited));
		if (diff.open() != Window.OK) {
			return;
		}
		try {
			if (offset + length <= doc.getLength()) {
				doc.replace(offset, length, edited);
				te.selectAndReveal(offset, edited.length());
			}
		} catch (BadLocationException e) {
			Activator.logError("변경 적용 실패", e);
		}
	}

	private static String safeGet(IDocument doc, int offset, int length) {
		try {
			return doc.get(offset, length);
		} catch (BadLocationException e) {
			return "";
		}
	}

	private static boolean inlinePreview() {
		try {
			IPreferenceStore store = Activator.getDefault().getPreferenceStore();
			return store.getBoolean(PreferenceConstants.P_INLINE_PREVIEW);
		} catch (Exception e) {
			return false;
		}
	}
}
