package com.egov.ollama.assist.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * 변경 미리보기 확인 다이얼로그. 본문을 등폭 글꼴로 보여주고
 * "- "(삭제)는 빨강, "+ "(추가)는 초록으로 표시한다. OK/Cancel 반환.
 */
public class DiffConfirmDialog extends Dialog {

	private final String title;
	private final String body;

	public DiffConfirmDialog(Shell parentShell, String title, String body) {
		super(parentShell);
		this.title = title;
		this.body = body == null ? "" : body;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	@Override
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		newShell.setText(title);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite container = (Composite) super.createDialogArea(parent);
		StyledText st = new StyledText(container,
				SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
		GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
		gd.widthHint = 720;
		gd.heightHint = 460;
		st.setLayoutData(gd);
		st.setFont(JFaceResources.getTextFont());
		st.setText(body);
		colorizeDiff(st);
		return container;
	}

	/** "- "(삭제)는 빨강, "+ "(추가)는 초록으로 표시. 다른 diff 뷰에서도 재사용. */
	public static void colorizeDiff(StyledText st) {
		Display d = st.getDisplay();
		Color red = d.getSystemColor(SWT.COLOR_RED);
		Color green = d.getSystemColor(SWT.COLOR_DARK_GREEN);
		String text = st.getText();
		int offset = 0;
		for (String line : text.split("\n", -1)) {
			int len = line.length();
			if (len > 0) {
				if (line.startsWith("- ")) {
					st.setStyleRange(new StyleRange(offset, len, red, null));
				} else if (line.startsWith("+ ")) {
					st.setStyleRange(new StyleRange(offset, len, green, null));
				}
			}
			offset += len + 1; // 줄바꿈 포함
		}
	}
}
