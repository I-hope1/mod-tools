package modtools.annotations.processors;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 拼出 {@code R_XXX} 中间类的源码文本。
 * <p>
 * 只负责“拼字符串”，落盘由 {@link ContentProcessor} 拿 {@code Filer} 做。
 * 分开的好处是这段可以当纯函数测——生成的源码一旦少了分号或包声明，症状是
 * “处理器自己的输出编译不过”，现场很难看出是哪一行拼错了。
 */
final class RefSourceGenerator {
	private RefSourceGenerator() {}

	/**
	 * @param unit        settings 类所在的编译单元（借用它的包声明与 import）
	 * @param settings    持有 key 的 settings 类
	 * @param literalName 生成类的名字后缀，最终是 {@code R_<literalName>}
	 * @param fields      字段名 → 字段类型字符串
	 */
	static String render(JCCompilationUnit unit, ClassSymbol settings,
	                     Object literalName, Map<VarSymbol, String> fields) {
		StringBuilder out = new StringBuilder();

		out.append(unit.getPackage());
		out.append(unit.getImports().stream()
		 .map(String::valueOf)
		 .collect(Collectors.joining("", "\n", "\n")));

		// 导入原本类的所有子类
		ClassSymbol curr = settings;
		while (true) {
			out.append("import ").append(curr.getQualifiedName()).append(".*;\n");
			if (!(curr.owner instanceof ClassSymbol owner)) break;
			curr = owner;
		}

		out.append("/** @see ").append(settings.getQualifiedName()).append("*/\n");
		out.append('@').append(RefRewriter.REF_OF_ANNO)
		 .append('(').append(settings.getQualifiedName()).append(".class)\n");
		out.append("public class ").append(RefRewriter.REF_PREFIX).append(literalName).append(" {\n");

		fields.forEach((symbol, type) -> out.append("\tpublic static ")
		 .append(type).append(' ').append(symbol.getSimpleName()).append(";\n"));

		out.append("\n}");
		return out.toString();
	}
}
