package com.egov.ollama.assist.handlers;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.CodeEdit;
import com.egov.ollama.assist.OllamaClient;
import com.egov.ollama.assist.Problems;
import com.egov.ollama.assist.preferences.PreferenceConstants;

/** 오류 수정(Problems 퀵픽스) 공통 로직: 모델 호출 → diff 미리보기 → 제자리 적용. */
public final class FixSupport {

	private FixSupport() {
	}

	/** [offset,length] 영역(original)을 errors 를 해결하도록 모델에게 고치게 한 뒤 diff 확인 후 적용. */
	public static void runFix(final Shell shell, final IDocument doc, final ITextEditor te, final int offset,
			final int length, final String original, final String errors, final String lang) {
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String model = store.getString(PreferenceConstants.P_MODEL);
		final double temperature = CodeEdit.parseTemperature(store.getString(PreferenceConstants.P_TEMPERATURE), 0.1);
		final Display display = shell.getDisplay();

		Job job = new Job("Ollama 오류 수정") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					final StringBuilder sb = new StringBuilder();
					OllamaClient.chatStream(base, model, CodeEdit.editSystemPrompt(),
							CodeEdit.buildFixPrompt(original, errors, lang), temperature, delta -> sb.append(delta));
					final String fixed = CodeEdit.cleanCode(sb.toString());
					if (!fixed.isEmpty() && display != null && !display.isDisposed()) {
						display.asyncExec(() -> EditorPreview.apply(shell, doc, te, offset, length, original, fixed,
								"오류 수정"));
					}
				} catch (Exception ex) {
					Activator.logError("오류 수정 실패", ex);
					if (display != null && !display.isDisposed()) {
						display.asyncExec(() -> MessageDialog.openError(shell, "Ollama 오류 수정",
								"수정 요청 중 오류: " + ex.getMessage()));
					}
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
	}

	/** startLine~endLine(0-based) 범위에 걸린 문제 마커 메시지를 모은다. */
	public static String collectMarkers(IFile file, int startLine, int endLine) {
		StringBuilder sb = new StringBuilder();
		try {
			IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
			for (IMarker m : markers) {
				int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
				if (line < 0) {
					continue;
				}
				int zero = line - 1;
				if (zero < startLine || zero > endLine) {
					continue;
				}
				sb.append(describe(m)).append('\n');
			}
		} catch (Exception e) {
			Activator.logError("마커 수집 실패", e);
		}
		return sb.toString().trim();
	}

	public static String describe(IMarker m) {
		int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
		String kind = severityLabel(m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO));
		String msg = m.getAttribute(IMarker.MESSAGE, "");
		return "줄 " + line + " [" + kind + "] " + msg;
	}

	/** 파일의 모든 문제 마커를 순수 모델(Problems.Item) 목록으로 수집. */
	public static java.util.List<Problems.Item> allProblems(IFile file) {
		java.util.List<Problems.Item> out = new java.util.ArrayList<>();
		try {
			IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
			for (IMarker m : markers) {
				int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
				if (line < 0) {
					continue;
				}
				out.add(new Problems.Item(line, severityLabel(m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)),
						m.getAttribute(IMarker.MESSAGE, "")));
			}
		} catch (Exception e) {
			Activator.logError("마커 수집 실패", e);
		}
		return out;
	}

	private static String severityLabel(int sev) {
		return sev == IMarker.SEVERITY_ERROR ? "오류" : sev == IMarker.SEVERITY_WARNING ? "경고" : "정보";
	}

	public static String fileExtension(String name) {
		int dot = name.lastIndexOf('.');
		return (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
	}
}
