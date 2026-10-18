package modtools.annotations.processors;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import modtools.annotations.NameUtils;
import modtools.annotations.settings.FlushField;

import static modtools.annotations.NameString.ns0;

/**
 * Settings 类上两个“附加动作”的 AST 生成：
 *
 * <ul>
 *   <li>{@link FlushField}：把设置值写回业务字段。既生成静态初始化块里的赋值，
 *       也在 {@code set(Object)} 里加一条 {@code if (this == xxx) 写回} 分支。</li>
 *   <li>{@code fireEvent}：在 {@code set(Object)} 末尾追一条 {@code MyEvents.fire(this)}。</li>
 * </ul>
 *
 * <p>两者都会往类里塞 {@code public void set(Object)}，所以统一由 {@link #ensureSet()} 负责
 * “有就复用、没有才新建”。之前它们各自 {@code defs.append} 一个新方法：一旦某个 Settings
 * 同时用了 {@code @FlushField} 和 {@code fireEvent = true}，就会生成两个同签名的方法，
 * 等到写 class 文件时才炸，而且报错位置跟真正的原因对不上。
 */
public final class SettingsMethods {
	private final JCClassDecl   classDecl;
	private final ClassSymbol   settings;
	private final ClassSymbol   iSettings;
	private final ClassSymbol   myEvents;
	private final TreeMaker     maker;
	private final Symtab        symtab;
	/** 静态初始化块里要执行的写回语句 */
	private final ListBuffer<JCStatement> flushAssignments = new ListBuffer<>();

	/**
	 * @param classDecl 目标类（通常就是 settings 的 tree）
	 * @param settings  目标类的符号
	 * @param iSettings 用于生成 {@code ISettings.super.set(val)}
	 * @param myEvents  用于生成 {@code MyEvents.fire(this)}，只有 {@code fireEvent} 用得上
	 */
	public SettingsMethods(JCClassDecl classDecl, ClassSymbol settings, ClassSymbol iSettings,
	                       ClassSymbol myEvents, TreeMaker maker, Symtab symtab) {
		this.classDecl = classDecl;
		this.settings = settings;
		this.iSettings = iSettings;
		this.myEvents = myEvents;
		this.maker = maker;
		this.symtab = symtab;
	}

	public boolean hasFlushAssignments() {
		return !flushAssignments.isEmpty();
	}

	/** 只能取一次：{@code toList()} 之后 buffer 就置为共享状态了。 */
	public List<JCStatement> flushAssignments() {
		return flushAssignments.toList();
	}

	/** 不是 {@link FlushField} 字段就什么都不做。 */
	public void flushField(JCFieldAccess classType, JCFieldAccess access, VarSymbol symbol) {
		if (symbol.getAnnotation(FlushField.class) == null) return;

		maker.at(classDecl.defs.last());
		flushAssignments.add(writeBack(classType, access, maker.Ident(symbol)));

		JCMethodDecl set = ensureSet();
		maker.at(classDecl.defs.last());
		set.body.stats = set.body.stats.append(
		 maker.If(maker.Binary(Tag.EQ, maker.Ident(symbol), maker.This(settings.type)),
		  writeBack(classType, access, maker.This(settings.type)), null));
	}

	/** 在 {@code set(Object)} 末尾追发事件；方法不存在就先建出来。 */
	public void fireEvent() {
		JCMethodDecl set = ensureSet();
		maker.at(classDecl.defs.last());
		set.body.stats = set.body.stats.append(
		 maker.Exec(maker.Apply(List.nil(),
		  maker.Select(maker.Ident(myEvents), ns0("fire")),
		  List.of(maker.This(settings.type)))));
	}

	/** {@code %access% = %selector%.getXxx()} */
	private JCExpressionStatement writeBack(JCFieldAccess classType, JCFieldAccess access,
	                                        JCExpression selector) {
		return maker.Exec(maker.Assign(access,
		 maker.Apply(List.nil(),
		  maker.Select(selector, ns0("get" + NameUtils.kebabToBigCamel(classType.selected.toString()))),
		  List.nil())));
	}

	/** 保证类里恰好有一个 {@code public void set(Object)}，并且它已经进了 members()。 */
	private JCMethodDecl ensureSet() {
		for (JCTree def : classDecl.defs) {
			if (def instanceof JCMethodDecl m && isSetObject(m)) return m;
		}

		MethodSymbol ms = new MethodSymbol(Flags.PUBLIC, ns0("set"),
		 new MethodType(List.of(symtab.objectType), symtab.voidType, List.nil(), settings), settings);
		JCVariableDecl val = maker.Param(ns0("val"), symtab.objectType, ms);
		ms.params = List.of(val.sym);

		JCBlock body = maker.Block(0, List.of(
		 maker.Exec(maker.Apply(List.nil(),
		  maker.Select(maker.Select(maker.Ident(iSettings), ns0("super")), ns0("set")),
		  List.of(maker.Ident(val))))));

		JCMethodDecl method = maker.MethodDef(ms, body);
		settings.members().enter(ms);
		classDecl.defs = classDecl.defs.append(method);
		return method;
	}

	private boolean isSetObject(JCMethodDecl m) {
		return m.name.contentEquals("set")
		       && m.params.size() == 1
		       && m.params.get(0).vartype.type != null
		       && m.params.get(0).vartype.type.equals(symtab.objectType);
	}
}
