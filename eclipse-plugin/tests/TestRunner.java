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
}
