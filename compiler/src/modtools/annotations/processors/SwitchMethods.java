package modtools.annotations.processors;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;
import modtools.annotations.PrintHelper.SPrinter;
import modtools.annotations.settings.Switch;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static modtools.annotations.NameString.ns0;

/**
 * 把 Settings 里标了 {@link Switch} 的字段翻译成三个覆写方法：
 *
 * <pre>
 * public boolean hasSwitch() {
 *   if (this == aaa) return true;
 *   ...
 *   return false;
 * }
 * public String switchKey() {
 *   if (this == bbb) return "some$enabled";
 *   ...
 *   return ISettings.super.switchKey();
 * }
 * public String[] switchKeys() {
 *   if (this == ccc) return new String[]{"a", "b"};
 *   ...
 *   return ISettings.super.switchKeys();
 * }
 * </pre>
 * <p>
 * {@code @Switch(dependency = {...})} 里的依赖是「与」关系：{@code ISettings.isSwitchOn()}
 * 会遍历 {@code switchKeys()}，任何一个为 false 就整体关闭。{@code switchKey()} 只有一个键，
 * 多依赖时取第一个当代表（UI 判断要不要画开关用的是它）。
 * <p>
 * {@link #collect} 在遍历字段时逐条调用（只读注解，不碰 AST），
 * {@link #build} 在字段全部收集完之后调用一次，负责生成 AST。
 * 拆开的原因是前者依赖“字段还没被改过”，后者依赖“类里的方法已经就位”。
 */
public final class SwitchMethods {
	/**
	 * 必须与 {@code ISettings.SUFFIX_ENABLED} 一致。
	 * compiler 模块看不到主工程，只能各写一份。
	 */
	public static final String SWITCH_SUFFIX = "$enabled";

	private final Map<VarSymbol, Switch> switches = new LinkedHashMap<>();

	/** 处理器实例跨多个 Settings 类复用，换类时必须清空，否则上一类的开关会漏到下一类。 */
	public void clear() {
		switches.clear();
	}

	public boolean isEmpty() {
		return switches.isEmpty();
	}

	public int size() {
		return switches.size();
	}

	/** 只有带 {@link Switch} 的字段才算开关；同一个字段重复调用只保留一条。 */
	public void collect(VarSymbol symbol) {
		Switch aSwitch = symbol.getAnnotation(Switch.class);
		if (aSwitch == null) return;
		switches.put(symbol, aSwitch);
	}

	/**
	 * 校验每个 dependency 指向的字段确实存在。
	 * <p>
	 * 写错名字的代价是静默的：{@code data().getBool("typo", true)} 永远返回默认值 true，
	 * 于是这个开关永远不会把它控制的那一项关掉，查起来毫无线索。所以在编译期直接报错。
	 * <p>
	 * {@code "xxx$enabled"} 视为「xxx 这个开关自己的开启键」，只校验前缀 {@code xxx}。
	 *
	 * @return 报出的错误条数
	 */
	public int validate(JCClassDecl classDecl, ClassSymbol settings, Log log, JavacTrees trees) {
		if (switches.isEmpty()) return 0;

		Set<String> fields = fieldNames(classDecl);

		int errors = 0;
		for (Map.Entry<VarSymbol, Switch> entry : switches.entrySet()) {
			VarSymbol symbol = entry.getKey();
			for (String dependency : entry.getValue().dependency()) {
				String message = checkDependency(dependency, fields, settings);
				if (message == null) continue;

				report(log, settings, trees.getTree(symbol), message);
				errors++;
			}
		}
		return errors;
	}

	private static void report(Log log, ClassSymbol settings, JCTree pos, String message) {
		// 增量编译时可能既拿不到 tree 也没有 sourcefile：这时退回不带位置的诊断。
		// 硬带上位置的话，javac 在算行号时会因为没有 source 而 NPE。
		if (settings.sourcefile != null) {
			log.useSource(settings.sourcefile);
			if (pos != null) {
				log.error(pos, SPrinter.err(message));
				return;
			}
		}
		log.error(SPrinter.err(message));
	}

	/** @return 有问题就返回错误信息，没问题返回 null */
	private static String checkDependency(String dependency, Set<String> fields, ClassSymbol settings) {
		if (dependency.isBlank()) {
			return "@Switch 的 dependency 不能是空字符串；不需要依赖请直接省略这个属性";
		}

		String base = dependency.endsWith(SWITCH_SUFFIX)
		 ? dependency.substring(0, dependency.length() - SWITCH_SUFFIX.length())
		 : dependency;
		if (fields.contains(base)) return null;

		String owner = settings.getSimpleName().toString();
		if (base.equals(dependency)) {
			return "@Switch(dependency = \"" + dependency + "\") 找不到对应字段：" + owner
			       + " 里没有叫 \"" + base + "\" 的枚举常量或字段";
		}
		return "@Switch(dependency = \"" + dependency + "\") 的前缀 \"" + base + "\" 不是 " + owner
		       + " 的字段（\"" + SWITCH_SUFFIX + "\" 后缀表示「那个开关自己的开启键」）";
	}

	/** 枚举常量和普通字段都算；这里是纯 AST 扫描，不依赖 symbols 是否已 complete。 */
	private static Set<String> fieldNames(JCClassDecl classDecl) {
		Set<String> names = new LinkedHashSet<>();
		for (JCTree def : classDecl.defs) {
			if (def instanceof JCVariableDecl variable) names.add(variable.name.toString());
		}
		return names;
	}

	/**
	 * @param classDecl 方法挂到哪个类上（通常就是 settings 的 tree）
	 * @param settings  三个新方法的 owner
	 * @param iSettings 用于生成 {@code ISettings.super.switchKey()}
	 */
	public void build(JCClassDecl classDecl, ClassSymbol settings, ClassSymbol iSettings,
	                  TreeMaker maker, Symtab symtab) {
		if (switches.isEmpty()) return;
		appendHasSwitch(classDecl, settings, maker, symtab);
		appendSwitchKey(classDecl, settings, iSettings, maker, symtab);
		appendSwitchKeys(classDecl, settings, iSettings, maker, symtab);
	}

	private void appendHasSwitch(JCClassDecl classDecl, ClassSymbol settings,
	                             TreeMaker maker, Symtab symtab) {
		maker.at(classDecl.defs.last());
		ListBuffer<JCStatement> body = new ListBuffer<>();
		switches.forEach((symbol, aSwitch) -> body.append(
		 maker.If(isSelf(symbol, settings, maker), maker.Return(maker.Literal(true)), null)));
		body.append(maker.Return(maker.Literal(false)));
		append(classDecl, settings, "hasSwitch", symtab.booleanType, body.toList(), maker);
	}

	private void appendSwitchKey(JCClassDecl classDecl, ClassSymbol settings, ClassSymbol iSettings,
	                             TreeMaker maker, Symtab symtab) {
		maker.at(classDecl.defs.last());
		ListBuffer<JCStatement> body = new ListBuffer<>();
		switches.forEach((symbol, aSwitch) -> {
			// 没写 dependency 的字段不生成分支，落到 ISettings 的默认实现（name() + "$enabled"）
			String[] deps = aSwitch.dependency();
			if (deps.length == 0) return;
			// switchKey() 只有一个键，多依赖时取第一个当代表（全部依赖见 switchKeys()）
			body.append(maker.If(isSelf(symbol, settings, maker),
			 maker.Return(maker.Literal(deps[0])), null));
		});
		body.append(maker.Return(maker.Apply(List.nil(),
		 maker.Select(maker.Select(maker.Type(iSettings.type), ns0("super")), ns0("switchKey")),
		 List.nil())));
		append(classDecl, settings, "switchKey", symtab.stringType, body.toList(), maker);
	}

	/** {@code String[] switchKeys()}：isSwitchOn() 要求这里面每个键都是 true。 */
	private void appendSwitchKeys(JCClassDecl classDecl, ClassSymbol settings, ClassSymbol iSettings,
	                              TreeMaker maker, Symtab symtab) {
		maker.at(classDecl.defs.last());
		ListBuffer<JCStatement> body = new ListBuffer<>();
		switches.forEach((symbol, aSwitch) -> {
			String[] deps = aSwitch.dependency();
			if (deps.length == 0) return;
			body.append(maker.If(isSelf(symbol, settings, maker),
			 maker.Return(stringArray(maker, symtab, deps)), null));
		});
		body.append(maker.Return(maker.Apply(List.nil(),
		 maker.Select(maker.Select(maker.Type(iSettings.type), ns0("super")), ns0("switchKeys")),
		 List.nil())));
		append(classDecl, settings, "switchKeys", arrayOf(symtab, symtab.stringType), body.toList(), maker);
	}

	/** {@code new String[]{a, b, ...}} */
	private static JCExpression stringArray(TreeMaker maker, Symtab symtab, String[] values) {
		ListBuffer<JCExpression> elems = new ListBuffer<>();
		for (String value : values) elems.append(maker.Literal(value));
		return maker.NewArray(maker.Type(symtab.stringType), List.nil(), elems.toList());
	}

	private static ArrayType arrayOf(Symtab symtab, Type elemtype) {
		return new ArrayType(elemtype, symtab.arrayClass);
	}

	/** {@code this == %symbol%} */
	private static JCExpression isSelf(VarSymbol symbol, ClassSymbol settings, TreeMaker maker) {
		return maker.Binary(Tag.EQ, maker.Ident(symbol), maker.This(settings.type));
	}

	private static void append(JCClassDecl classDecl, ClassSymbol settings, String name, Type restype,
	                           List<JCStatement> body, TreeMaker maker) {
		// MethodSymbol 的 type 必须是真正的 MethodType（owner + 返回类型 + 参数表）。
		// 之前这里直接把 booleanType 当方法类型塞进去，和 buildFlushField 里 set(Object) 的
		// 构造方式不一致；javac 目前不读它所以没炸，但属于埋雷。
		MethodSymbol ms = new MethodSymbol(Flags.PUBLIC, ns0(name),
		 new MethodType(List.nil(), restype, List.nil(), settings), settings);

		JCMethodDecl method = maker.MethodDef(ms, maker.Block(0, body));
		method.restype = typeTree(maker, restype);

		settings.members().enter(ms);
		classDecl.defs = classDecl.defs.append(method);
	}

	/** 数组类型要用 {@code JCArrayTypeTree}，直接塞 {@code TypeTree} 打印出来是怪东西。 */
	private static JCExpression typeTree(TreeMaker maker, Type type) {
		if (type instanceof ArrayType array) return maker.TypeArray(typeTree(maker, array.elemtype));
		return maker.Type(type);
	}
}
