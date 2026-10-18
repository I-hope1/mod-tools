package modtools.annotations.processors;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import modtools.annotations.settings.Switch;

import java.util.LinkedHashMap;
import java.util.Map;

import static modtools.annotations.NameString.ns0;

/**
 * 把 Settings 里标了 {@link Switch} 的字段翻译成两个覆写方法：
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
 * </pre>
 * <p>
 * {@link #collect} 在遍历字段时逐条调用（只读注解，不碰 AST），
 * {@link #build} 在字段全部收集完之后调用一次，负责生成 AST。
 * 拆开的原因是前者依赖“字段还没被改过”，后者依赖“类里的方法已经就位”。
 */
public final class SwitchMethods {
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
	 * @param classDecl 方法挂到哪个类上（通常就是 settings 的 tree）
	 * @param settings  两个新方法的 owner
	 * @param iSettings 用于生成 {@code ISettings.super.switchKey()}
	 */
	public void build(JCClassDecl classDecl, ClassSymbol settings, ClassSymbol iSettings,
	                  TreeMaker maker, Symtab symtab) {
		if (switches.isEmpty()) return;
		appendHasSwitch(classDecl, settings, maker, symtab);
		appendSwitchKey(classDecl, settings, iSettings, maker, symtab);
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
			if (aSwitch.dependency().isEmpty()) return;
			body.append(maker.If(isSelf(symbol, settings, maker),
			 maker.Return(maker.Literal(aSwitch.dependency())), null));
		});
		body.append(maker.Return(maker.Apply(List.nil(),
		 maker.Select(maker.Select(maker.Type(iSettings.type), ns0("super")), ns0("switchKey")),
		 List.nil())));
		append(classDecl, settings, "switchKey", symtab.stringType, body.toList(), maker);
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
		method.restype = maker.Type(restype);

		settings.members().enter(ms);
		classDecl.defs = classDecl.defs.append(method);
	}
}
