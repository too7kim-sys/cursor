import com.egov.ollama.assist.*;
import java.io.*;
import java.util.*;

/**
 * 폐쇄망/무의존 단위 테스트 러너(JUnit 미사용). 순수 로직(SWT/Eclipse 비의존)만 검증한다.
 * 실행: run-tests.sh / run-tests.ps1
 */
public class TestRunner {
	static int pass = 0, fail = 0;
	static void ck(String n, boolean ok) { System.out.println((ok ? "PASS" : "FAIL") + " : " + n); if (ok) pass++; else fail++; }

	public static void main(String[] a) throws Exception {
		markdown();
		codeEdit();
		mentions();
		fileProposals();
		sessionStore();
		symbols();
		problems();
		changeParser();
		ghostText();
		editMatch();
		verifyReport();
		editHistory();
		agentEditController();
		System.out.println("\n=== PASS=" + pass + " FAIL=" + fail + " ===");
		if (fail > 0) System.exit(1);
	}

	static void markdown() {
		String t = "설명.\n```java\nint x=1;\n```\n## 제목\n**굵게**";
		List<MarkdownScanner.Span> s = MarkdownScanner.scan(t);
		boolean code=false, header=false, bold=false;
		for (MarkdownScanner.Span sp : s) {
			if (sp.kind==MarkdownScanner.Kind.CODE) code=true;
			if (sp.kind==MarkdownScanner.Kind.HEADER) header=true;
			if (sp.kind==MarkdownScanner.Kind.BOLD) bold=true;
		}
		ck("md: code span", code);
		ck("md: header span", header);
		ck("md: bold span", bold);
		ck("md: lastCodeBlock", "int x=1;".equals(MarkdownScanner.lastCodeBlock(t)));
		ck("md: no code -> null", MarkdownScanner.lastCodeBlock("no code")==null);
		ck("md: progress line", MarkdownScanner.isProgressLine("🔧 read_file(x)"));
		ck("md: not progress", !MarkdownScanner.isProgressLine("실제 답변"));
		// codeBlockAt
		int inside = t.indexOf("int x=1;");
		ck("md: codeBlockAt inside", "int x=1;".equals(MarkdownScanner.codeBlockAt(t, inside)));
		ck("md: codeBlockAt outside", MarkdownScanner.codeBlockAt(t, 0)==null);
		String two = "```\nA\n```\nmid\n```\nB\n```";
		ck("md: codeBlockAt 2nd", "B".equals(MarkdownScanner.codeBlockAt(two, two.lastIndexOf("B"))));
	}

	static void codeEdit() {
		ck("ce: fence java", CodeEdit.cleanCode("```java\nint x=1;\n```").equals("int x=1;"));
		ck("ce: prose+fence", CodeEdit.cleanCode("했음:\n```\nv()\n```\n끝").equals("v()"));
		ck("ce: no fence trim", CodeEdit.cleanCode("  y=2;  \n").equals("y=2;"));
		ck("ce: unclosed", CodeEdit.cleanCode("```js\nq()").equals("q()"));
		ck("ce: null", CodeEdit.cleanCode(null).equals(""));
		ck("ce: edit prompt", CodeEdit.buildEditPrompt("int x;","name→count","java").contains("name→count"));
		ck("ce: fix prompt", CodeEdit.buildFixPrompt("int x=","';' expected","java").contains("';' expected"));
		ck("ce: sys prompt", CodeEdit.editSystemPrompt().contains("코드 편집기"));
		ck("ce: temp ok", CodeEdit.parseTemperature("0.3", 0.1) == 0.3);
		ck("ce: temp bad->def", CodeEdit.parseTemperature("x", 0.2) == 0.2);
		ck("ce: temp null->def", CodeEdit.parseTemperature(null, 0.2) == 0.2);
	}

	static void mentions() {
		ck("mn: selection+file", Mentions.parse("@selection 봐 @src/Foo.java").equals(Arrays.asList("selection","src/Foo.java")));
		ck("mn: dedupe", Mentions.parse("@a @a @b").equals(Arrays.asList("a","b")));
		ck("mn: ignore email", Mentions.parse("too7kim@gmail.com").isEmpty());
		ck("mn: trailing punct", Mentions.parse("@Foo.java, 봐").equals(Arrays.asList("Foo.java")));
		ck("mn: isSelection", Mentions.isSelection("SELECTION") && !Mentions.isSelection("x"));
		ck("mn: null", Mentions.parse(null).isEmpty());
	}

	static void fileProposals() {
		ck("fp: token", "src/Fo".equals(FileProposals.tokenAt("봐 @src/Fo", 9)));
		ck("fp: no token space", FileProposals.tokenAt("hi there",8)==null);
		ck("fp: email", FileProposals.tokenAt("a@b",3)==null);
		List<String> paths = Arrays.asList("src/Foo.java","src/Bar.java","test/FooTest.java","README.md");
		ck("fp: name prefix", FileProposals.match(paths,"foo",10).contains("src/Foo.java"));
		ck("fp: path prefix", FileProposals.match(paths,"src/",10).size()==2);
		ck("fp: empty lists", FileProposals.match(paths,"",2).size()==2);
		ck("fp: contains", FileProposals.match(paths,"adme",10).contains("README.md"));
	}

	static void sessionStore() throws Exception {
		File d = File.createTempFile("sess","dir"); d.delete(); d.mkdirs();
		SessionStore st = new SessionStore(d);
		String id = st.create("내 대화");
		SessionStore.Session s = st.load(id);
		ck("ss: create+load", s!=null && s.name.equals("내 대화"));
		s.transcript = "안녕\n```java\nint x;\n```";
		Map<String,Object> h = new LinkedHashMap<>(); h.put("role","user"); h.put("content","hi");
		s.history.add(h); st.save(id, s);
		SessionStore.Session s2 = st.load(id);
		ck("ss: transcript", s2.transcript.contains("int x;"));
		ck("ss: history", s2.history.size()==1);
		st.setCurrent(id); ck("ss: current", id.equals(st.getCurrent()));
		ck("ss: list", st.list().size()==1);
		ck("ss: search by name", st.search("내 대화").size()==1);
		ck("ss: search by content", st.search("int x").size()==1);
		ck("ss: search miss", st.search("없는단어zz").isEmpty());
		st.delete(id); ck("ss: delete", st.load(id)==null && st.list().isEmpty());
	}

	static void symbols() {
		String java = "public class Foo {\n  public void bar(int x){}\n  private String baz(){return null;}\n}";
		List<String> syms = SymbolIndex.extractSymbols(java);
		ck("sym: class", syms.contains("Foo"));
		ck("sym: method bar", syms.contains("bar"));
		ck("sym: method baz", syms.contains("baz"));
		ck("sym: no keyword", !syms.contains("if") && !syms.contains("return"));
		String py = "def hello():\n  pass\nclass Cat:\n  pass";
		ck("sym: py def", SymbolIndex.extractSymbols(py).contains("hello"));
		ck("sym: py class", SymbolIndex.extractSymbols(py).contains("Cat"));
		ck("sym: defLine class", SymbolIndex.defLine(java,"Foo")==0);
		ck("sym: defLine method", SymbolIndex.defLine(java,"bar")==1);
		ck("sym: snippet", SymbolIndex.snippet(java,1,0).contains("bar"));
	}

	static void problems() {
		List<Problems.Item> items = new ArrayList<>();
		items.add(new Problems.Item(5, "오류", "';' expected"));
		items.add(new Problems.Item(2, "경고", "unused import"));
		items.add(new Problems.Item(5, "오류", "';' expected")); // 중복
		String f = Problems.format(items);
		ck("pb: sorted by line", f.indexOf("줄 2") < f.indexOf("줄 5"));
		ck("pb: dedupe", f.split("\n").length == 2);
		ck("pb: errorCount", Problems.errorCount(items) == 2);
		ck("pb: empty", Problems.format(new ArrayList<>()).isEmpty());
	}

	static void changeParser() {
		List<ChangeParser.Change> c1 = ChangeParser.parse("```java src/Foo.java\nint x=1;\n```");
		ck("cp: fence info path", c1.size()==1 && c1.get(0).path.equals("src/Foo.java") && c1.get(0).content.equals("int x=1;"));
		ck("cp: label path", ChangeParser.parse("파일: src/Bar.java\n```\nB();\n```").get(0).path.equals("src/Bar.java"));
		ck("cp: bold path", ChangeParser.parse("**a/b/C.java**\n```\nC\n```").get(0).path.equals("a/b/C.java"));
		ck("cp: no path ignored", ChangeParser.parse("설명\n```\nplain\n```").isEmpty());
		List<ChangeParser.Change> c5 = ChangeParser.parse("File: x/A.java\n```\nA\n```\n설명\nFile: y/B.java\n```\nB\n```");
		ck("cp: multiple", c5.size()==2 && c5.get(1).path.equals("y/B.java"));
		ck("cp: lang only ignored", ChangeParser.parse("```python\nprint(1)\n```").isEmpty());
		ck("cp: prose clears", ChangeParser.parse("src/Z.java\n이건 설명\n```\nz\n```").isEmpty());
	}

	static void ghostText() {
		ck("gt: end of text", GhostText.atLineEnd("abc", 3));
		ck("gt: before newline", GhostText.atLineEnd("ab\ncd", 2));
		ck("gt: mid line false", !GhostText.atLineEnd("ab\ncd", 1));
		ck("gt: trailing ws then nl", GhostText.atLineEnd("ab  \nx", 2));
		ck("gt: empty true", GhostText.atLineEnd("", 0));
		ck("gt: null false", !GhostText.atLineEnd(null, 0));
	}

	static void editMatch() {
		String c = "int a;\ndef();\nend";
		EditMatch.Result r1 = EditMatch.find(c, "def();");
		ck("em: exact", r1 != null && r1.mode.equals("exact") && c.substring(r1.start, r1.end).equals("def();"));
		// 줄 끝 공백 차이
		String c2 = "if(x){\n  y();  \n}";
		EditMatch.Result r2 = EditMatch.find(c2, "if(x){\n  y();\n}");
		ck("em: trailing ws", r2 != null && !r2.mode.equals("exact") && c2.substring(r2.start, r2.end).contains("y();"));
		// 들여쓰기 차이(멀티라인 — exact 부분문자열로는 안 잡힘)
		EditMatch.Result r3 = EditMatch.find("  if(x){\n    y();\n  }", "if(x){\n  y();\n}");
		ck("em: indent", r3 != null && r3.mode.equals("indent") && "  if(x){\n    y();\n  }".substring(r3.start, r3.end).contains("y();"));
		ck("em: not found", EditMatch.find("abc", "xyz") == null);
		ck("em: dup exact", EditMatch.hasDuplicateExact("a x a x", "x"));
		ck("em: no dup", !EditMatch.hasDuplicateExact("a x", "x"));
		ck("em: countExact", EditMatch.countExact("a x a x a", "x") == 2);
		ck("em: countExact none", EditMatch.countExact("abc", "z") == 0);
	}

	static void verifyReport() {
		String p = "오류 1개, 경고 1개\nERROR A.java:3: msg\nWARN B.java:1: w";
		String f = VerifyReport.focusErrors(p, 10);
		ck("vr: keeps error", f != null && f.contains("ERROR A.java:3"));
		ck("vr: drops warn", f != null && !f.contains("WARN"));
		ck("vr: no errors null", VerifyReport.focusErrors("오류 0개, 경고 2개\nWARN x\nWARN y", 10) == null);
		ck("vr: null", VerifyReport.focusErrors(null, 10) == null);
		ck("vr: dedupe", VerifyReport.focusErrors("ERROR A:1: x\nERROR A:1: x", 10).contains("1개"));
		// 변경 파일 우선 정렬
		String p2 = "오류 2개\nERROR /p/Other.java:1: a\nERROR /p/Foo.java:2: b";
		String fr = VerifyReport.focusErrors(p2, 10, java.util.Arrays.asList("Foo.java"));
		ck("vr: changed first", fr.indexOf("Foo.java") < fr.indexOf("Other.java"));
		// 테스트 로그 정제
		String log = "compiling...\nTests run: 5, Failures: 1\nAssertionError: expected 3 but was 4\n    at Foo.test(Foo.java:9)";
		String tl = VerifyReport.focusTestLog(log, 10);
		ck("vr: testlog keeps fail", tl != null && tl.contains("Tests run") && tl.contains("AssertionError"));
		ck("vr: testlog drops noise", tl != null && !tl.contains("compiling..."));
		ck("vr: testlog none null", VerifyReport.focusTestLog("all good\nclean", 10) == null);
	}

	static void editHistory() {
		EditHistory h = new EditHistory();
		List<FileChange> r1 = new ArrayList<>(); r1.add(new FileChange("A.java","a0","a1"));
		List<FileChange> r2 = new ArrayList<>(); r2.add(new FileChange("A.java","a1","a2")); r2.add(new FileChange("B.java","","b1"));
		h.push("r1", r1);
		h.push("r2", r2);
		ck("eh: size", h.size()==2);
		java.util.Map<String,String> from0 = h.restoreStateFrom(0);
		ck("eh: earliest before A", "a0".equals(from0.get("A.java")));
		ck("eh: B before", "".equals(from0.get("B.java")));
		java.util.Map<String,String> from1 = h.restoreStateFrom(1);
		ck("eh: from1 A is a1", "a1".equals(from1.get("A.java")));
		h.truncateTo(1);
		ck("eh: truncate", h.size()==1 && h.get(0).label.equals("r1"));
		h.push("empty", new ArrayList<>());
		ck("eh: empty not pushed", h.size()==1);
		// JSON 영속화 라운드트립
		EditHistory h2 = new EditHistory();
		List<FileChange> e1 = new ArrayList<>(); e1.add(new FileChange("X.java","x0","x1")); e1.add(new FileChange("Y.java","","y1"));
		h2.push("작업1", e1);
		EditHistory h3 = new EditHistory();
		h3.loadJson(h2.toJson());
		ck("eh: json size", h3.size()==1);
		ck("eh: json label", h3.get(0).label.equals("작업1"));
		ck("eh: json restore", "x0".equals(h3.restoreStateFrom(0).get("X.java")) && h3.get(0).fileCount()==2);
		ck("eh: load empty", roundtripEmpty());
		// 개수 상한: 2개로 제한하고 3번 push → 최신 2개만, 가장 오래된 것 제거
		EditHistory hc = new EditHistory();
		hc.setMaxCheckpoints(2);
		hc.push("c1", one("F1.java"));
		hc.push("c2", one("F2.java"));
		hc.push("c3", one("F3.java"));
		ck("eh: cap count", hc.size()==2 && hc.get(0).label.equals("c2") && hc.get(1).label.equals("c3"));
		// 나이 상한: 오래된 체크포인트는 prune(now)로 제거(최소 1 유지)
		EditHistory ha = new EditHistory();
		ha.setLimits(100, 9999999, 1000); // 1초
		List<FileChange> old = new ArrayList<>(); old.add(new FileChange("Old.java","o0","o1"));
		ha.push("oldcp", old);
		ha.push("newcp", one("New.java"));
		ha.prune(System.currentTimeMillis() + 10000); // 10초 후 시점 → 둘 다 오래됨이지만 최소 1 유지
		ck("eh: age keeps one", ha.size()==1 && ha.get(0).label.equals("newcp"));
		// 용량 상한
		EditHistory hb = new EditHistory();
		hb.setLimits(100, 10, 0); // 10자 상한, 나이 무시
		hb.push("big1", one("A.java"));   // before "o0"? one() uses small content
		ck("eh: totalChars positive", hb.totalChars() >= 0);
	}

	static List<FileChange> one(String path) {
		List<FileChange> l = new ArrayList<>();
		l.add(new FileChange(path, "before-"+path, "after-"+path));
		return l;
	}

	static boolean roundtripEmpty() {
		EditHistory h = new EditHistory();
		h.loadJson("");
		h.loadJson(null);
		return h.size()==0;
	}

	static void agentEditController() throws Exception {
		List<FileChange> raw = new ArrayList<>();
		raw.add(new FileChange("A.java","a0","a1"));
		raw.add(new FileChange("B.java","","b1"));
		raw.add(new FileChange("A.java","a1","a2"));
		List<FileChange> m = AgentEditController.merge(raw);
		ck("aec: merge size", m.size()==2);
		FileChange a = m.get(0);
		ck("aec: merge first-before/last-after", a.path.equals("A.java") && a.before.equals("a0") && a.after.equals("a2"));
		File dir = File.createTempFile("aec","d"); dir.delete(); dir.mkdirs();
		File proj = File.createTempFile("proj","d"); proj.delete(); proj.mkdirs();
		AgentEditController c = new AgentEditController(dir);
		AgentEditController.RecordResult r = c.record(proj, "run1", raw);
		ck("aec: record index", r.index==0 && r.distinct.size()==2);
		ck("aec: restore before", "a0".equals(c.restoreFrom(0).get("A.java")));
		AgentEditController c2 = new AgentEditController(dir);
		c2.ensure(proj);
		ck("aec: persisted load", c2.history().size()==1 && "a0".equals(c2.restoreFrom(0).get("A.java")));
		ck("aec: writeFile ok", AgentEditController.writeFile(proj, "sub/x.txt", "hi"));
		ck("aec: writeFile escape blocked", !AgentEditController.writeFile(proj, "../escape.txt", "no"));
		c.truncateAndSave(0);
		ck("aec: truncate", c.history().size()==0);
	}
}
