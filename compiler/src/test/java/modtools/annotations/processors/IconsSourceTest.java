package modtools.annotations.processors;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IconsSource} 的输出会被 {@code Filer} 直接写成 {@code .java}，
 * 少个分号或 import 写错只会在下游编译时才炸。
 */
class IconsSourceTest {
	@Test
	void rendersPackageAndImports() {
		String source = render(List.of("a"));

		assertTrue(source.startsWith("package p;\n\n"), source);
		assertTrue(source.contains("import src.HopeIconsc;\n"), source);
		assertTrue(source.contains("import arc.scene.style.TextureRegionDrawable;\n"), source);
	}

	@Test
	void emitsOneFieldPerPngInOrder() {
		String source = render(List.of("foo-bar", "baz"));

		int foo = source.indexOf("public static TextureRegionDrawable fooBar;");
		int baz = source.indexOf("public static TextureRegionDrawable baz;");
		assertTrue(foo >= 0, source);
		assertTrue(baz > foo, "字段顺序应与图片顺序一致: " + source);
	}

	@Test
	void loadBodyAssignsEveryFieldByRawName() {
		String source = render(List.of("foo-bar"));

		// 字段名转 camelCase，但查图集用的是原始文件名
		assertTrue(source.contains("fooBar = n(\"foo-bar\");"), source);
	}

	@Test
	void modNameIsResolvedFromTheMainClass() {
		String source = render(List.of("a"));

		assertTrue(source.contains(
		 "if (modName == null) modName = mindustry.Vars.mods.getMod(mindustry.ModTools.class).name;"), source);
	}

	@Test
	void emptyPngListStillProducesAValidClass() {
		String source = render(List.of());

		assertFalse(source.contains("public static TextureRegionDrawable "), source);
		assertTrue(source.contains("public static void load() {"), source);
		assertEquals(count(source, '{'), count(source, '}'), "大括号应成对: " + source);
	}

	@Test
	void usesLfAndEndsWithNewline() {
		String source = render(List.of("a", "b"));

		assertTrue(source.endsWith("}\n"), source);
		assertFalse(source.contains("\r"), "生成的是 .java 文本，统一用 LF");
	}

	private static String render(List<String> pngs) {
		return IconsSource.render("p", "HopeIcons", "src.HopeIconsc", "mindustry.ModTools", pngs);
	}

	private static int count(String text, char c) {
		return text.length() - text.replace(String.valueOf(c), "").length();
	}
}
