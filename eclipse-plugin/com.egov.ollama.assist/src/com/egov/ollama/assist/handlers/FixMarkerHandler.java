package com.egov.ollama.assist.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IMarker;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Activator;

/**
 * Problems 뷰 우클릭 → 'AI로 고치기'. 선택한 마커의 파일을 열어 해당 줄을 모델이 수정한다.
 */
public class FixMarkerHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		final Shell shell = HandlerUtil.getActiveShell(event);
		ISelection sel = HandlerUtil.getCurrentSelection(event);
		if (!(sel instanceof IStructuredSelection)) {
			return null;
		}
		Object first = ((IStructuredSelection) sel).getFirstElement();
		IMarker marker = null;
		if (first instanceof IMarker) {
			marker = (IMarker) first;
		} else if (first != null) {
			Object adapted = org.eclipse.core.runtime.Platform.getAdapterManager().getAdapter(first, IMarker.class);
			if (adapted instanceof IMarker) {
				marker = (IMarker) adapted;
			}
		}
		if (marker == null || !marker.exists()) {
			return null;
		}

		int line = marker.getAttribute(IMarker.LINE_NUMBER, -1); // 1-based
		if (line <= 0) {
			MessageDialog.openInformation(shell, "Ollama 오류 수정", "줄 정보가 없는 마커는 수정할 수 없습니다.");
			return null;
		}

		try {
			IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
			IEditorPart editor = IDE.openEditor(page, marker); // 마커 위치로 이동/선택
			if (!(editor instanceof ITextEditor)) {
				MessageDialog.openInformation(shell, "Ollama 오류 수정", "텍스트 편집기에서 열리는 파일만 수정할 수 있습니다.");
				return null;
			}
			ITextEditor te = (ITextEditor) editor;
			IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
			if (doc == null) {
				return null;
			}
			int zeroLine = line - 1;
			IRegion region = doc.getLineInformation(zeroLine);
			int offset = region.getOffset();
			int length = region.getLength();
			String original = doc.get(offset, length);
			String errors = FixSupport.describe(marker);
			String lang = "";
			IEditorInput in = te.getEditorInput();
			if (in != null && in.getName() != null) {
				lang = FixSupport.fileExtension(in.getName());
			}
			FixSupport.runFix(shell, doc, te, offset, length, original, errors, lang);
		} catch (BadLocationException e) {
			Activator.logError("마커 줄 정보 오류", e);
		} catch (Exception e) {
			Activator.logError("마커 수정 실패", e);
			throw new ExecutionException("오류 수정 실패", e);
		}
		return null;
	}
}
