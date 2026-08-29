import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.mod.Mods;
import mindustry.mod.Scripts;
import modtools.ui.comp.input.highlight.*;
import rhino.*;

import java.util.ArrayList;
import java.util.List;

public class JSSyntaxTest {

	public static class MockToken {
		public final Color color;
		public final int start;
		public final int end;
		public final String text;

		public MockToken(Color color, int start, int end, String text) {
			this.color = new Color(color);
			this.start = start;
			this.end = end;
			this.text = text;
		}

		@Override
		public String toString() {
			return String.format("[%s: %s (color: %s)]", text, start + ".." + end, color);
		}
	}

	public static class MockDrawable implements SyntaxDrawable {
		public String text = "";
		public final Font dummyFont;
		public final List<MockToken> tokens = new ArrayList<>();
		public int cursor = 0;

		public MockDrawable() {
			Font f;
			try {
				f = new Font(new Font.FontData(), new TextureRegion(), false);
			} catch (Throwable t) {
				try {
					f = (Font) ihope_lib.MyReflect.unsafe.allocateInstance(Font.class);
					arc.util.Reflect.set(Font.class, f, "color", new Color(Color.white));
				} catch (Throwable t2) {
					f = null;
				}
			}
			this.dummyFont = f;
		}

		@Override
		public float alpha() { return 1f; }
		@Override
		public int cursor() { return cursor; }
		@Override
		public Font font() { return dummyFont; }
		@Override
		public String getText() { return text; }

		@Override
		public void drawMultiText(CharSequence displayText, int start, int max) {
			String slice = displayText.subSequence(start, max).toString();
			tokens.add(new MockToken(dummyFont.getColor(), start, max, slice));
		}
	}

	private static void initMindustryEnv() {
		try {
			Version.number = 159;
			Context cx = Context.enter();
			ImporterTopLevel scope = new ImporterTopLevel(cx);

			Vars.mods = (Mods) ihope_lib.MyReflect.unsafe.allocateInstance(Mods.class);
			Scripts scripts = (Scripts) ihope_lib.MyReflect.unsafe.allocateInstance(Scripts.class);
			arc.util.Reflect.set(Scripts.class, scripts, "context", cx);
			arc.util.Reflect.set(Scripts.class, scripts, "scope", scope);
			arc.util.Reflect.set(Mods.class, Vars.mods, "scripts", scripts);
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	public static void main(String[] args) {
		System.out.println("============================================");
		System.out.println("        RUNNING JSSYNTAX UNIT TESTS         ");
		System.out.println("============================================");

		initMindustryEnv();

		MockDrawable drawable = new MockDrawable();
		Context cx = Vars.mods.getScripts().context;
		Scriptable scope = Vars.mods.getScripts().scope;
		JSSyntax syntax = new JSSyntax(drawable, scope);

		int passed = 0;
		int failed = 0;

		// Test 1: Multi-variable declarations
		try {
			System.out.print("Test 1: Multi-variable declaration (let a = 1, b = 2;)... ");
			drawable.text = "let a = 1, b = 2;\n";
			drawable.tokens.clear();
			syntax.highlightingDraw(drawable.text);

			assertTokenColor(drawable.tokens, "let", Syntax.c_keyword);
			assertTokenColor(drawable.tokens, "a", JSSyntax.c_localvar);
			assertTokenColor(drawable.tokens, "1", Syntax.c_number);
			assertTokenColor(drawable.tokens, "b", JSSyntax.c_localvar);
			assertTokenColor(drawable.tokens, "2", Syntax.c_number);

			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			t.printStackTrace(System.out);
			failed++;
		}

		// Test 2: Value expressions in declaration should not register RHS as local vars
		try {
			System.out.print("Test 2: Declaration with RHS expressions (let a = foo + 10;)... ");
			drawable.text = "let a = foo + 10;\n";
			drawable.tokens.clear();
			syntax.highlightingDraw(drawable.text);

			assertTokenColor(drawable.tokens, "a", JSSyntax.c_localvar);
			MockToken fooToken = findToken(drawable.tokens, "foo");
			if (fooToken != null && fooToken.color.equals(JSSyntax.c_localvar)) {
				throw new AssertionError("RHS variable 'foo' was incorrectly highlighted as c_localvar");
			}

			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			t.printStackTrace(System.out);
			failed++;
		}

		// Test 3: Block Scope Isolation
		try {
			System.out.print("Test 3: Block Scope Isolation ({ let inside = 1; } inside;)... ");
			drawable.text = "{\n  let inside = 1;\n}\ninside;\n";
			drawable.tokens.clear();
			syntax.highlightingDraw(drawable.text);

			List<MockToken> insideTokens = findTokens(drawable.tokens, "inside");
			if (insideTokens.size() < 2) {
				throw new AssertionError("Expected 2 'inside' tokens, got " + insideTokens.size());
			}

			MockToken innerInside = insideTokens.get(0);
			MockToken outerInside = insideTokens.get(1);

			if (!innerInside.color.equals(JSSyntax.c_localvar)) {
				throw new AssertionError("Inner 'inside' was not c_localvar! Color was: " + innerInside.color);
			}
			if (outerInside.color.equals(JSSyntax.c_localvar)) {
				throw new AssertionError("Outer 'inside' was incorrectly highlighted as c_localvar after scope closed!");
			}

			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			t.printStackTrace(System.out);
			failed++;
		}

		// Test 4: Function parameters & default value protection
		try {
			System.out.print("Test 4: Function parameters & default values... ");
			drawable.text = "function test(param1, param2 = DEFAULT_VAL) {\n  let result = param1 + param2;\n}\nresult;\n";
			drawable.tokens.clear();
			syntax.highlightingDraw(drawable.text);

			assertTokenColor(drawable.tokens, "test", Syntax.c_functions);
			assertTokenColor(drawable.tokens, "param1", JSSyntax.c_localvar);
			assertTokenColor(drawable.tokens, "param2", JSSyntax.c_localvar);

			MockToken defValToken = findToken(drawable.tokens, "DEFAULT_VAL");
			if (defValToken != null && defValToken.color.equals(JSSyntax.c_localvar)) {
				throw new AssertionError("Default value 'DEFAULT_VAL' was incorrectly recognized as a param localvar!");
			}

			List<MockToken> resultTokens = findTokens(drawable.tokens, "result");
			if (resultTokens.size() >= 2) {
				if (!resultTokens.get(0).color.equals(JSSyntax.c_localvar)) {
					throw new AssertionError("Inner 'result' is not c_localvar");
				}
				if (resultTokens.get(1).color.equals(JSSyntax.c_localvar)) {
					throw new AssertionError("Outer 'result' was incorrectly highlighted as c_localvar after function scope closed!");
				}
			}

			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			t.printStackTrace(System.out);
			failed++;
		}

		// Test 5: Destructuring constants
		try {
			System.out.print("Test 5: Destructuring declaration (const [first, second] = arr;)... ");
			drawable.text = "const [first, second] = arr;\n";
			drawable.tokens.clear();
			syntax.highlightingDraw(drawable.text);

			assertTokenColor(drawable.tokens, "first", JSSyntax.c_constants);
			assertTokenColor(drawable.tokens, "second", JSSyntax.c_constants);

			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			t.printStackTrace(System.out);
			failed++;
		}

		// Test 6: eachLocalName completion enumeration
		try {
			System.out.print("Test 6: eachLocalName auto-completion enumeration... ");
			drawable.text = "let myVar1 = 1;\nconst myConst2 = 2;\nfunction myFunc3() {}\n";
			drawable.tokens.clear();
			syntax.highlightingDraw(drawable.text);

			Seq<String> names = new Seq<>();
			syntax.eachLocalName(names::add);

			if (!names.contains("myVar1") || !names.contains("myConst2") || !names.contains("myFunc3")) {
				throw new AssertionError("eachLocalName missing expected local declarations! Found: " + names);
			}

			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			t.printStackTrace(System.out);
			failed++;
		}

		// Test 7: Object Destructuring with Aliasing (let {ui: x} = Vars;;)
		try {
			System.out.print("Test 7: Object destructuring with aliasing (let {ui: x} = Vars; ui; x;)... ");
			drawable.text = "let {ui: x} = Vars;\nui;\nx;\n";
			drawable.tokens.clear();
			syntax.highlightingDraw(drawable.text);

			// First 'ui' is a property key (not local variable)
			List<MockToken> uiTokens = findTokens(drawable.tokens, "ui");
			if (uiTokens.size() < 2) {
				throw new AssertionError("Expected 2 'ui' tokens, got " + uiTokens.size());
			}
			MockToken propUi = uiTokens.get(0);
			MockToken outerUi = uiTokens.get(1);

			if (propUi.color.equals(JSSyntax.c_localvar)) {
				throw new AssertionError("Property key 'ui' in destructuring was incorrectly highlighted as c_localvar!");
			}
			if (outerUi.color.equals(JSSyntax.c_localvar)) {
				throw new AssertionError("Outer 'ui' was incorrectly highlighted as c_localvar!");
			}

			// 'x' is the actual declared variable!
			List<MockToken> xTokens = findTokens(drawable.tokens, "x");
			if (xTokens.size() < 2) {
				throw new AssertionError("Expected 2 'x' tokens, got " + xTokens.size());
			}
			if (!xTokens.get(0).color.equals(JSSyntax.c_localvar)) {
				throw new AssertionError("Bound variable 'x' in destructuring was not c_localvar!");
			}
			if (!xTokens.get(1).color.equals(JSSyntax.c_localvar)) {
				throw new AssertionError("Usage of 'x' was not highlighted as c_localvar!");
			}

			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			t.printStackTrace(System.out);
			failed++;
		}

		System.out.println("============================================");
		System.out.println(String.format("TEST RESULTS: %d Passed, %d Failed", passed, failed));
		System.out.println("============================================");

		if (failed > 0) {
			System.exit(1);
		}
	}

	private static MockToken findToken(List<MockToken> tokens, String text) {
		for (MockToken t : tokens) {
			if (t.text.equals(text)) return t;
		}
		return null;
	}

	private static List<MockToken> findTokens(List<MockToken> tokens, String text) {
		List<MockToken> result = new ArrayList<>();
		for (MockToken t : tokens) {
			if (t.text.equals(text)) result.add(t);
		}
		return result;
	}

	private static void assertTokenColor(List<MockToken> tokens, String text, Color expectedColor) {
		MockToken token = findToken(tokens, text);
		if (token == null) {
			throw new AssertionError("Token '" + text + "' not found in tokens list: " + tokens);
		}
		if (!token.color.equals(expectedColor)) {
			throw new AssertionError("Token '" + text + "' had color " + token.color + ", expected " + expectedColor);
		}
	}
}
