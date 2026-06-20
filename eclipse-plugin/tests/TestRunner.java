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
		globMatcher();
		textSearch();
		contextManager();
		planRenderer();
		symbolReader();
		lineEdit();
		repeatTracker();
		nearestHint();
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
		// 보정 매칭 애매성: 들여쓰기만 다른 동일 블록이 두 곳 → ambiguous
		EditMatch.Result amb = EditMatch.find("  if(x){\n    y();\n  }\nzz\n  if(x){\n    y();\n  }",
				"if(x){\n  y();\n}");
		ck("em: fuzzy ambiguous", amb != null && !amb.mode.equals("exact") && amb.ambiguous);
		// 보정 매칭이 한 곳뿐이면 ambiguous=false
		ck("em: fuzzy unique", r3 != null && !r3.ambiguous);
		ck("em: exact not ambiguous", r1 != null && !r1.ambiguous);
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
		h.truncateTo(-1); // 음수 인덱스는 전체 삭제 대신 무시
		ck("eh: truncate negative no-op", h.size()==1 && h.get(0).label.equals("r1"));
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
		// 생성된 파일(되돌리면 삭제): restoreStateFrom 값이 null, JSON 라운드트립 보존
		EditHistory hcrt = new EditHistory();
		List<FileChange> cr = new ArrayList<>(); cr.add(FileChange.created("New.java","content"));
		hcrt.push("create", cr);
		java.util.Map<String,String> rs = hcrt.restoreStateFrom(0);
		ck("eh: created -> delete null", rs.containsKey("New.java") && rs.get("New.java")==null);
		EditHistory hcrt2 = new EditHistory(); hcrt2.loadJson(hcrt.toJson());
		ck("eh: created flag persisted", hcrt2.restoreStateFrom(0).containsKey("New.java") && hcrt2.restoreStateFrom(0).get("New.java")==null);
		// 구버전(3요소) JSON 은 existedBefore=true 로 로드(복원값 non-null)
		EditHistory legacy = new EditHistory();
		legacy.loadJson("[{\"label\":\"x\",\"time\":1,\"edits\":[[\"A.java\",\"b0\",\"a1\"]]}]");
		ck("eh: legacy 3-elem existed", "b0".equals(legacy.restoreStateFrom(0).get("A.java")));
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
		// merge 가 "생성" 플래그를 보존해야 함(생성 후 편집 → 되돌리면 삭제 대상)
		List<FileChange> rawc = new ArrayList<>();
		rawc.add(FileChange.created("F.java","v1"));
		rawc.add(new FileChange("F.java","v1","v2"));
		List<FileChange> mg = AgentEditController.merge(rawc);
		ck("aec: merge keeps created flag", mg.size()==1 && !mg.get(0).existedBefore && mg.get(0).after.equals("v2"));
		ck("aec: merge default existed", AgentEditController.merge(java.util.Arrays.asList(new FileChange("G.java","g0","g1"))).get(0).existedBefore);
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
		// 형제 디렉터리 접두어 우회("/root" vs "/rootX") 차단
		ck("aec: writeFile sibling-prefix blocked",
				!AgentEditController.writeFile(proj, "../" + proj.getName() + "-evil/x.txt", "no"));
		c.truncateAndSave(0);
		ck("aec: truncate", c.history().size()==0);
	}

	static void globMatcher() {
		ck("gm: name only", GlobMatcher.matches("*.java", "src/main/A.java"));
		ck("gm: name no match ext", !GlobMatcher.matches("*.java", "src/A.txt"));
		ck("gm: suffix name", GlobMatcher.matches("*Service.java", "x/y/UserService.java"));
		ck("gm: not suffix", !GlobMatcher.matches("*Service.java", "x/UserDao.java"));
		ck("gm: star no slash", !GlobMatcher.matches("src/*.java", "src/sub/A.java"));
		ck("gm: doublestar deep", GlobMatcher.matches("src/**/*.java", "src/a/b/C.java"));
		ck("gm: doublestar zero dir", GlobMatcher.matches("src/**/*.java", "src/C.java"));
		ck("gm: doublestar wrong root", !GlobMatcher.matches("src/**/*.java", "test/C.java"));
		ck("gm: question one char", GlobMatcher.matches("A?.java", "src/Ab.java"));
		ck("gm: dot literal", !GlobMatcher.matches("a.b", "axb"));
		ck("gm: empty matches all", GlobMatcher.matches("", "anything"));
	}

	static void textSearch() {
		String c = "alpha\nbeta foo\ngamma\nfoo bar\ndelta";
		List<String> r = TextSearch.search(c, "foo", false, 0, 100);
		ck("ts: two hits", r.size()==2);
		ck("ts: line no + sep", r.get(0).equals("2: beta foo"));
		ck("ts: second hit", r.get(1).equals("4: foo bar"));
		// 정규식
		List<String> rx = TextSearch.search(c, "^foo", true, 0, 100);
		ck("ts: regex anchored one", rx.size()==1 && rx.get(0).equals("4: foo bar"));
		// 컨텍스트 1줄
		List<String> ctx = TextSearch.search("a\nHIT\nb\nc", "HIT", false, 1, 100);
		ck("ts: context before", ctx.contains("1- a"));
		ck("ts: context match sep", ctx.contains("2: HIT"));
		ck("ts: context after", ctx.contains("3- b"));
		// max 제한
		ck("ts: max limit", TextSearch.search(c, "foo", false, 0, 1).size()==1);
		// 인접한 두 매치는 둘 다 ':'(매치)로 표시되어야 함(컨텍스트로 흡수 금지)
		List<String> adj = TextSearch.search("a\nfoo\nfoo\nb", "foo", false, 1, 100);
		long matchLines = adj.stream().filter(TextSearch::isMatchLine).count();
		ck("ts: adjacent both matches", matchLines == 2);
		ck("ts: adjacent line2", adj.contains("2: foo") && adj.contains("3: foo"));
		// 일치 줄 판별
		ck("ts: isMatchLine match", TextSearch.isMatchLine("12: x"));
		ck("ts: isMatchLine context", !TextSearch.isMatchLine("12- x"));
		ck("ts: isMatchLine sep", !TextSearch.isMatchLine("--"));
	}

	static void contextManager() {
		List<Object> msgs = new ArrayList<>();
		msgs.add(mkMsg("system", "sys"));
		msgs.add(mkMsg("user", "do it"));
		String big = rep("X", 5000);
		for (int i=0;i<6;i++) { msgs.add(mkMsg("assistant", "call")); msgs.add(mkMsg("tool", big)); }
		int before = ContextManager.estimateChars(msgs);
		int saved = ContextManager.compactToolOutputs(msgs, 2, 600);
		ck("cm: saved positive", saved > 0);
		ck("cm: estimate dropped", ContextManager.estimateChars(msgs) < before);
		// 최근 2개 tool 은 보존
		int kept = 0;
		for (int i=msgs.size()-1;i>=0 && kept<2;i--) {
			Map<?,?> m = (Map<?,?>) msgs.get(i);
			if ("tool".equals(m.get("role"))) { ck("cm: recent kept", ((String)m.get("content")).length()==5000); kept++; }
		}
		// 사용자/시스템 메시지는 손대지 않음
		ck("cm: user intact", "do it".equals(((Map<?,?>)msgs.get(1)).get("content")));
		ck("cm: null safe", ContextManager.compactToolOutputs(null, 2, 600)==0);
		// 1회성 구조 힌트 압축: 접두어로 식별, 다른 system 메시지는 보존
		List<Object> hm = new ArrayList<>();
		String hint = "프로젝트 구조(일부). " + rep("Z", 1000);
		String other = "다른 시스템 메시지 " + rep("Q", 1000);
		hm.add(mkMsg("system", hint));
		hm.add(mkMsg("system", other));
		int sv = ContextManager.compactStaleHints(hm, "프로젝트 구조(일부)", 200);
		ck("cm: hint trimmed", sv > 0 && ((String)((Map<?,?>)hm.get(0)).get("content")).length() < 400);
		ck("cm: other hint intact", other.equals(((Map<?,?>)hm.get(1)).get("content")));
		ck("cm: hint null safe", ContextManager.compactStaleHints(null, "x", 10)==0);
	}

	static void planRenderer() {
		List<Object> steps = new ArrayList<>();
		steps.add("탐색");
		Map<String,Object> s2 = new LinkedHashMap<>(); s2.put("step","수정"); s2.put("status","done"); steps.add(s2);
		Map<String,Object> s3 = new LinkedHashMap<>(); s3.put("step","검증"); s3.put("status","in_progress"); steps.add(s3);
		String out = PlanRenderer.render(steps);
		ck("pr: has header", out.contains("작업 계획"));
		ck("pr: pending box", out.contains("☐ 탐색"));
		ck("pr: done box", out.contains("☑ 수정"));
		ck("pr: active box", out.contains("▶ 검증"));
		ck("pr: empty -> empty", PlanRenderer.render(new ArrayList<>()).isEmpty());
		ck("pr: null -> empty", PlanRenderer.render(null).isEmpty());
	}

	static Map<String,Object> mkMsg(String role, String content) {
		Map<String,Object> m = new LinkedHashMap<>(); m.put("role", role); m.put("content", content); return m;
	}
	static String rep(String s, int n) { StringBuilder b=new StringBuilder(); for(int i=0;i<n;i++) b.append(s); return b.toString(); }

	static void symbolReader() {
		String c = "package x;\npublic class Foo {\n  public void bar() {\n    int y=1;\n  }\n  public int baz(int n){ return n; }\n}";
		String out = SymbolReader.outline(c);
		ck("sr: outline Foo", out.contains("Foo"));
		ck("sr: outline bar", out.contains("bar"));
		ck("sr: outline baz", out.contains("baz"));
		ck("sr: outline has line no", out.contains(": "));
		String bar = SymbolReader.read(c, "bar");
		ck("sr: read bar body", bar != null && bar.contains("int y=1;") && bar.contains("void bar()"));
		ck("sr: read bar balanced", bar != null && bar.contains("}") && !bar.contains("int baz"));
		String baz = SymbolReader.read(c, "baz");
		ck("sr: read one-line baz", baz != null && baz.contains("return n;"));
		ck("sr: read missing", SymbolReader.read(c, "nope") == null);
		ck("sr: outline empty", SymbolReader.outline("").isEmpty());
		// 문자열 안의 중괄호로 본문이 일찍 끝나지 않아야 함
		String strBrace = "class C {\n  String f() {\n    return \"a{b}c\";\n  }\n  int g(){ return 9; }\n}";
		String f = SymbolReader.read(strBrace, "f");
		ck("sr: string-brace body", f != null && f.contains("return \"a{b}c\";"));
		ck("sr: string-brace stops right", f != null && !f.contains("int g()"));
		// 줄 주석 안의 중괄호도 무시
		String cmtBrace = "class D {\n  void h() {\n    // closing } here\n    int z=1;\n  }\n}";
		String h = SymbolReader.read(cmtBrace, "h");
		ck("sr: comment-brace body", h != null && h.contains("int z=1;") && h.contains("void h()"));
		// 인터페이스 추상 메서드(본문 없음) → 선언 줄만
		String iface = "interface I {\n  void doX();\n  void doY();\n}";
		String dx = SymbolReader.read(iface, "doX");
		ck("sr: abstract decl only", dx != null && dx.contains("doX();") && !dx.contains("doY"));
		// 블록 주석 안의 중괄호 무시
		String blk = "class E {\n  int k() {\n    /* { not real ; */\n    return 1;\n  }\n}";
		String kk = SymbolReader.read(blk, "k");
		ck("sr: block-comment brace", kk != null && kk.contains("return 1;") && kk.trim().endsWith("}"));
	}

	static void lineEdit() {
		ck("le: replace mid", "a\nB\nc".equals(LineEdit.replace("a\nb\nc", 2, 2, "B")));
		ck("le: replace range", "a\nX".equals(LineEdit.replace("a\nb\nc", 2, 3, "X")));
		ck("le: multi-line repl", "a\nX\nY\nc".equals(LineEdit.replace("a\nb\nc", 2, 2, "X\nY")));
		ck("le: delete via empty", "a\nc".equals(LineEdit.replace("a\nb\nc", 2, 2, "")));
		ck("le: first line", "Z\nb".equals(LineEdit.replace("a\nb", 1, 1, "Z")));
		ck("le: trailing nl kept", "Z\nb\n".equals(LineEdit.replace("a\nb\n", 1, 1, "Z")));
		ck("le: end clamps", "a\nX".equals(LineEdit.replace("a\nb\nc", 2, 99, "X")));
		ck("le: bad start", LineEdit.replace("a\nb", 0, 1, "x") == null);
		ck("le: start gt len", LineEdit.replace("a\nb", 5, 6, "x") == null);
		ck("le: end lt start", LineEdit.replace("a\nb", 2, 1, "x") == null);
	}

	static void repeatTracker() {
		RepeatTracker t = new RepeatTracker();
		String s = RepeatTracker.signature("read_file", "path=A.java");
		ck("rt: first", t.record(s) == 1);
		ck("rt: second", t.record(s) == 2);
		ck("rt: third", t.record(s) == 3);
		ck("rt: other independent", t.record(RepeatTracker.signature("read_file", "path=B.java")) == 1);
		ck("rt: count query", t.count(s) == 3);
		ck("rt: sig null safe", RepeatTracker.signature(null, null).equals("|"));
		// 연속 스트릭: 같은 서명 연달아 → 증가, 다른 서명 끼면 초기화
		RepeatTracker st = new RepeatTracker();
		String a = RepeatTracker.signature("read_file", "path=A");
		String b = RepeatTracker.signature("apply_edit", "path=A");
		ck("rt: streak 1", st.recordStreak(a) == 1);
		ck("rt: streak 2", st.recordStreak(a) == 2);
		ck("rt: streak resets on diff", st.recordStreak(b) == 1);
		ck("rt: streak restart", st.recordStreak(a) == 1); // a 다시 → 비연속이므로 1
		ck("rt: streak grows again", st.recordStreak(a) == 2);
	}

	static void nearestHint() {
		String c = "int total = 0;\nfor (int i=0;i<n;i++) {\n  total += arr[i];\n}\nreturn total;";
		String hint = EditMatch.nearestHint(c, "for (int i = 0; i < n; i++) {");
		ck("nh: finds for line", hint != null && hint.contains("2행"));
		ck("nh: no shared tokens -> null", EditMatch.nearestHint(c, "zzz qqq www vvv") == null);
		ck("nh: blank old -> null", EditMatch.nearestHint(c, "   \n  ") == null);
	}
}
