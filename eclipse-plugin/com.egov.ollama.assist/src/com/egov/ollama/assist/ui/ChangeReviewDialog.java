package com.egov.ollama.assist.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.egov.ollama.assist.ChangeParser;
import com.egov.ollama.assist.TextDiff;

/**
 * 멀티파일 변경 검토/일괄 적용 패널. 위쪽 체크 목록(파일별 신규/덮어쓰기)과 아래쪽 diff 미리보기.
 * OK 시 체크된 변경만 {@link #getAccepted()} 로 반환한다(파일 쓰기는 호출측 담당).
 */
public class ChangeReviewDialog extends Dialog {

	private final List<ChangeParser.Change> changes;
	private final File root;
	private final List<ChangeParser.Change> accepted = new ArrayList<>();
	private Table table;
	private StyledText diffArea;

	public ChangeReviewDialog(Shell parentShell, List<ChangeParser.Change> changes, File root) {
		super(parentShell);
		this.changes = changes;
		this.root = root;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	@Override
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		newShell.setText("멀티파일 변경 적용 — " + changes.size() + "개 파일");
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite container = (Composite) super.createDialogArea(parent);
		SashForm sash = new SashForm(container, SWT.VERTICAL);
		GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
		gd.widthHint = 760;
		gd.heightHint = 520;
		sash.setLayoutData(gd);

		table = new Table(sash, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
		for (ChangeParser.Change ch : changes) {
			TableItem it = new TableItem(table, SWT.NONE);
			boolean exists = new File(root, ch.path).isFile();
			it.setText(ch.path + "   — " + (exists ? "덮어쓰기" : "신규"));
			it.setChecked(true);
			it.setData(ch);
		}
		table.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				showDiff();
			}
		});

		diffArea = new StyledText(sash, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
		diffArea.setFont(JFaceResources.getTextFont());

		sash.setWeights(new int[] { 35, 65 });
		if (table.getItemCount() > 0) {
			table.select(0);
			showDiff();
		}
		return container;
	}

	private void showDiff() {
		int idx = table.getSelectionIndex();
		if (idx < 0) {
			return;
		}
		ChangeParser.Change ch = (ChangeParser.Change) table.getItem(idx).getData();
		String old = readFile(new File(root, ch.path));
		diffArea.setText(TextDiff.unified(old, ch.content));
		colorize(diffArea);
	}

	private void colorize(StyledText st) {
		Color red = st.getDisplay().getSystemColor(SWT.COLOR_RED);
		Color green = st.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN);
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
			offset += len + 1;
		}
	}

	private static String readFile(File f) {
		if (f == null || !f.isFile()) {
			return "";
		}
		try {
			return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	@Override
	protected void okPressed() {
		accepted.clear();
		for (TableItem it : table.getItems()) {
			if (it.getChecked()) {
				accepted.add((ChangeParser.Change) it.getData());
			}
		}
		super.okPressed();
	}

	public List<ChangeParser.Change> getAccepted() {
		return accepted;
	}
}
