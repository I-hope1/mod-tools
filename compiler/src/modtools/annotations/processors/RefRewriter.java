package modtools.annotations.processors;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Attribute.Compound;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import modtools.annotations.settings.RefOf;
import modtools.annotations.unsafe.TopTranslator;
import modtools.annotations.unsafe.TopTranslator.ToTranslate;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Supplier;

import static modtools.annotations.BaseProcessor.*;
import static modtools.annotations.NameString.ns0;

/**
 * 把 {@code R_XXX.field} 的读写改写成 settings key 的读写：
 * <pre>
 * R_XXX.field     -&gt; E_XXX.field.getXxx()
 * R_XXX.field = v -&gt; E_XXX.field.set(v)
 * </pre>
 * {@code R_XXX} 是 {@link ContentProcessor} 生成的中间类，带 {@link RefOf}，
 * 注解的 {@code value()} 指向真正持有 key 的 settings 类。
 */
public final class RefRewriter {
	public static final String REF_PREFIX  = "R_";
	public static final String REF_OF_ANNO = RefOf.class.getName();

	private RefRewriter() {}

	/** 注册两条 AST 改写规则；由 {@code Replace.extendingFunc0} 在处理器初始化时调用 */
	public static void register(Context context) {
		// 懒加载符号：第一次匹配时才 resolve
		ClassSymbol[] iSettingsRef = {null};

		Supplier<ClassSymbol> getISettings = () -> {
			if (iSettingsRef[0] == null) {
				iSettingsRef[0] = mSymtab.enterClass(
				 mSymtab.unnamedModule, ns0("modtools.events.ISettings"));
			}
			return iSettingsRef[0];
		};
		var translator = TopTranslator.instance(context);

		// R_XXX.field 读取 -> E_XXX.field.getXxx()
		translator.addToDo(new ToTranslate(JCFieldAccess.class, access -> {
			if (!(access.selected instanceof JCIdent i
			      && i.name.toString().startsWith(REF_PREFIX))) { return null; }
			ClassSymbol iSettings = getISettings.get();
			if (iSettings == null) return null;

			ClassSymbol symbol = refTarget(i);
			if (symbol == null) return null;

			JCFieldAccess enumField = translator.makeSelect(
			 mMaker.QualIdent(symbol), ns0(access.name.toString()), symbol);
			String s = "" + access.type.tsym.getSimpleName();
			String getter = switch (s) {
				case "boolean" -> "enabled";
				default -> access.type.tsym.isEnum()
				 ? "getEnum"
				 : "get" + s.substring(0, 1).toUpperCase() + s.substring(1);
			};
			JCFieldAccess fn = translator.makeSelect(enumField, ns0(getter), iSettings);
			MethodSymbol  ms = (MethodSymbol) fn.sym;
			return mMaker.Apply(List.nil(), fn,
			 ms.params.isEmpty() ? List.nil() : List.of(mMaker.ClassLiteral(access.type))
			).setType(access.type);
		}));

		// R_XXX.field = val -> E_XXX.field.set(val)
		translator.addToDo(new ToTranslate(JCAssign.class, tree -> {
			if (!(tree.lhs instanceof JCFieldAccess access
			      && access.selected instanceof JCIdent i
			      && i.name.toString().startsWith(REF_PREFIX))) { return null; }
			ClassSymbol iSettings = getISettings.get();
			if (iSettings == null) return null;

			ClassSymbol symbol = refTarget(i);
			if (symbol == null) return null;
			JCFieldAccess enumField = translator.makeSelect(
			 mMaker.QualIdent(symbol), access.name, symbol);
			JCFieldAccess fn = translator.makeSelect(
			 enumField, ns0("set"), iSettings);
			return mMaker.Apply(List.nil(), fn, List.of(tree.rhs)).setType(tree.type);
		}));
	}

	/**
	 * 读 {@code R_XXX} 上的 {@link RefOf}，返回它指向的 settings 类。
	 *
	 * @return 不是类、没标注、或注解值不是普通类时返回 {@code null}
	 */
	static @Nullable ClassSymbol refTarget(JCIdent i) {
		// 名字以 R_ 开头不代表它是类 —— 用户完全可能有个叫 R_x 的变量
		if (!(i.sym instanceof ClassSymbol rSym)) return null;

		// 先补全符号（增量时可能只有存根）
		if (rSym.members_field == null) {
			classFinder.loadClass(mSymtab.unnamedModule, rSym.flatname);
		}

		// 读 @RefOf 注解 —— 在 .class 里也能拿到
		Compound refOf = rSym.getAnnotationMirrors().stream()
		 .filter(a -> a.type.tsym.getQualifiedName().toString().equals(REF_OF_ANNO))
		 .findFirst().orElse(null);
		if (refOf == null) return null;

		// value() 是 Class，对应的 Attribute 是 Attribute.Class；
		// 数组/基本类型的 class 字面量不是普通类，取不到就放弃
		if (!(refOf.member(ns0("value")) instanceof Attribute.Class classAttr)) return null;
		return classAttr.classType.tsym instanceof ClassSymbol target ? target : null;
	}
}
