package modtools.annotations.unsafe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SyntaxErrorPatcher} 会**直接改写源文件**，所以每条失败路径都必须安全退出。
 */
class SyntaxErrorPatcherTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("纯函数：越界返回 null，不做任何裁剪")
	void pureFunctionsAreBoundsChecked() {
		assertEquals("axb", SyntaxErrorPatcher.insert("ab", 1, 'x'));
		assertEquals("xab", SyntaxErrorPatcher.insert("ab", 0, 'x'));
		assertEquals("abx", SyntaxErrorPatcher.insert("ab", 2, 'x'));
		assertNull(SyntaxErrorPatcher.insert("ab", 3, 'x'));
		assertNull(SyntaxErrorPatcher.insert("ab", -1, 'x'));
		assertNull(SyntaxErrorPatcher.insert(null, 0, 'x'));

		assertEquals("ac", SyntaxErrorPatcher.delete("abc", 1));
		assertEquals("bc", SyntaxErrorPatcher.delete("abc", 0));
		assertNull(SyntaxErrorPatcher.delete("abc", 3));
		assertNull(SyntaxErrorPatcher.delete("", 0));
		assertNull(SyntaxErrorPatcher.delete(null, 0));
	}

	@Test
	@DisplayName("写回必须保持非 ASCII 内容（字符集与 javac 一致）")
	void writesBackWithoutCorruptingNonAscii() throws Exception {
		Path file = tempDir.resolve("A.java");
		String original = "// 中文注释，含全角标点：（）\nclass A { int x = ; }\n";
		Files.writeString(file, original, StandardCharsets.UTF_8);

		SyntaxErrorPatcher patcher = new SyntaxErrorPatcher(StandardCharsets.UTF_8);
		int index = original.indexOf('=') + 2; // 指向 ';'

		assertTrue(patcher.fix(javaFile(file), index, '0'));

		String patched = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(patched.contains("中文注释，含全角标点：（）"), "非 ASCII 内容被写坏了: " + patched);
		assertTrue(patched.contains("int x = 0;"), "补丁没有落到位: " + patched);
	}

	@Test
	@DisplayName("位置越界时不动文件")
	void outOfRangeLeavesFileUntouched() throws Exception {
		Path file = tempDir.resolve("B.java");
		Files.writeString(file, "class B {}", StandardCharsets.UTF_8);

		SyntaxErrorPatcher patcher = new SyntaxErrorPatcher(StandardCharsets.UTF_8);
		assertFalse(patcher.fix(javaFile(file), 9999, 'x'));
		assertFalse(patcher.drop(javaFile(file), -5));
		assertEquals("class B {}", Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("诊断位置基于原始文件，编辑后要按累计位移修正")
	void offsetsAccumulate() throws Exception {
		Path file = tempDir.resolve("C.java");
		Files.writeString(file, "x", StandardCharsets.UTF_8);

		SyntaxErrorPatcher patcher = new SyntaxErrorPatcher(StandardCharsets.UTF_8);
		assertTrue(patcher.fix(javaFile(file), 0, '0'));
		assertEquals(1, patcher.offset());
		assertTrue(patcher.fix(javaFile(file), 0, '1'));
		assertEquals(2, patcher.offset());

		assertEquals("01x", Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("该位置是 ')' 时改为删除而不是插入")
	void removesStrayClosingParen() throws Exception {
		Path file = tempDir.resolve("D.java");
		String original = "class D { int x = 0); }";
		Files.writeString(file, original, StandardCharsets.UTF_8);

		SyntaxErrorPatcher patcher = new SyntaxErrorPatcher(StandardCharsets.UTF_8);
		assertTrue(patcher.fix(javaFile(file), original.indexOf(')'), ')'));

		assertEquals("class D { int x = 0; }", Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("非 file scheme 的 JavaFileObject 直接放弃")
	void nonFileSchemeIsRejected() {
		JavaFileObject inMemory = new SimpleJavaFileObject(URI.create("string:///E.java"), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return "abc";
			}
		};
		SyntaxErrorPatcher patcher = new SyntaxErrorPatcher(StandardCharsets.UTF_8);
		assertDoesNotThrow(() -> assertFalse(patcher.fix(inMemory, 0, 'x')));
	}

	private static JavaFileObject javaFile(Path path) {
		return new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
				return Files.readString(path, StandardCharsets.UTF_8);
			}
		};
	}
}
