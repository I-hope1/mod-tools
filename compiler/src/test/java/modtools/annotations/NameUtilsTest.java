package modtools.annotations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link NameUtils} 的行为约束。纯字符串运算，不需要初始化任何 javac 状态。
 * <p>
 * {@code toUnderlineName} 不能越界探测下一个字符：字段名以大写结尾时，
 * {@code charAt(i + 1)} 会抛 {@link StringIndexOutOfBoundsException}，
 * 该异常经 {@code dealElement} 冒泡后被转成编译错误，直接打断整次编译。
 */
class NameUtilsTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
		// 原有行为，不能改
		"foo,          foo",
		"fooBar,       foo_bar",
		"dataInit,     data_init",
		"someField,    some_field",
		"a,            a",
		// 回归：末尾大写曾越界
		"fooX,         foo_x",
		"FOO,          foo",
		"AB,           ab",
		// 连续大写后接小写，只在词边界断一次
		"fooBAR,       foo_bar",
		"HTTPServer,   http_server",
		"aB,           a_b",
		"field2Value,  field2_value",
	})
	@DisplayName("camelCase -> snake_case")
	void underlineName(String input, String expected) {
		assertEquals(expected, NameUtils.toUnderlineName(input));
	}

	@Test
	@DisplayName("空串不再抛 charAt(0)")
	void underlineNameEmpty() {
		assertEquals("", NameUtils.toUnderlineName(""));
	}

	@ParameterizedTest
	@ValueSource(strings = {"fooX", "FOO", "AB", "x", "XML"})
	@DisplayName("任何输入都不应抛异常")
	void underlineNameNeverThrows(String input) {
		NameUtils.toUnderlineName(input);
	}

	@Test
	@DisplayName("越界探测会在末尾大写上崩溃（对照实现）")
	void oldImplementationCrashesOnTrailingUppercase() {
		assertThrows(StringIndexOutOfBoundsException.class, () -> oldUnderlineName("fooX"));
		assertThrows(StringIndexOutOfBoundsException.class, () -> oldUnderlineName("FOO"));
	}

	@Test
	void kebabToCamel() {
		assertEquals("testUi", NameUtils.kebabToCamel("test_ui"));
		assertEquals("testUi", NameUtils.kebabToCamel("test-ui"));
		assertEquals("plain", NameUtils.kebabToCamel("plain"));
		assertEquals("", NameUtils.kebabToCamel(""));
	}

	@Test
	void kebabToBigCamel() {
		assertEquals("TestUI", NameUtils.kebabToBigCamel("test_ui"));
		assertEquals("Plain", NameUtils.kebabToBigCamel("plain"));
		assertEquals("", NameUtils.kebabToBigCamel(""));
	}

	@Test
	void capitalize() {
		assertEquals("Test Ui", NameUtils.capitalize("test_ui"));
		assertEquals("", NameUtils.capitalize(""));
	}

	/** 修复前的实现，仅用于证明缺陷真实存在 */
	private static String oldUnderlineName(String fieldName) {
		StringBuilder sb = new StringBuilder();
		sb.append(Character.toLowerCase(fieldName.charAt(0)));
		for (int i = 1, len = fieldName.length(); i < len; i++) {
			if (Character.isUpperCase(fieldName.charAt(i)) && !Character.isUpperCase(fieldName.charAt(i + 1))) {
				sb.append("_");
			}
			sb.append(Character.toLowerCase(fieldName.charAt(i)));
		}
		return sb.toString();
	}
}
