package modtools.annotations.unsafe;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

import java.util.stream.Collectors;

import static modtools.annotations.PrintHelper.SPrinter.*;
import static modtools.annotations.unsafe.Replace.*;

public class DesugarRecord extends TreeTranslator {
	public static final int PUBLIC_FINAL = Flags.PUBLIC | Flags.FINAL;

	JCCompilationUnit toplevel;

	public void visitImport(JCImport tree) {
	}

	/** 辅助方法：通过纯语法树定义判断该类是否已经显式声明了特定方法 */
	private static boolean hasMethod(JCClassDecl tree, Name name, int paramCount) {
		return tree.defs.stream()
		 .filter(d -> d instanceof JCMethodDecl)
		 .map(d -> (JCMethodDecl) d)
		 .anyMatch(m -> m.name.contentEquals(name) && m.params.size() == paramCount);
	}

	public void visitClassDef(JCClassDecl tree) {
		super.visitClassDef(tree);
		if ((tree.mods.flags & Flags.RECORD) == 0) return;

		maker.at(tree);

		// 获取所有非静态的成员字段
		var fields = tree.defs.stream()
		 .filter(d -> d instanceof JCVariableDecl)
		 .map(d -> (JCVariableDecl) d)
		 .filter(f -> (f.mods.flags & Flags.STATIC) == 0/* 排除static */)
		 .toList();

		// 清理字段上的 RECORD 标记
		fields.forEach(field -> {
			field.mods.flags &= ~(Flags.PRIVATE | Flags.RECORD);
			field.mods.flags |= Flags.PUBLIC;
			if (field.sym != null) {
				field.sym.flags_field &= ~(Flags.PRIVATE | Flags.RECORD);
				field.sym.flags_field |= Flags.PUBLIC;
			}
		});

		// 判断并同步更新 static / RECORD 标记
		if (tree.sym != null) {
			if (tree.sym.owner instanceof ClassSymbol) {
				tree.mods.flags |= Flags.STATIC;
				tree.sym.flags_field |= Flags.STATIC;
			}
			tree.sym.flags_field &= ~Flags.RECORD; // 同步清理符号表的 RECORD
		} else {
			// 局部类或局部 Record，通过 toplevel 判断是否为嵌套/局部作用域
			if (toplevel != null && !toplevel.defs.contains(tree)) {
				tree.mods.flags |= Flags.STATIC;
			}
		}
		tree.mods.flags &= ~Flags.RECORD; // 清理语法树上的 RECORD

		// 处理构造方法
		boolean hasConstructor = tree.defs.stream()
		 .anyMatch(d -> d instanceof JCMethodDecl m && m.name.contentEquals(ns.init));

		if (!hasConstructor) {
			// 如果没有构造方法（例如局部隐式 Record），纯手动构建全参构造方法
			ListBuffer<JCVariableDecl> params = new ListBuffer<>();
			ListBuffer<JCStatement> stats = new ListBuffer<>();
			for (JCVariableDecl field : fields) {
				// 生成构造方法形参
				JCVariableDecl param = maker.VarDef(maker.Modifiers(Flags.PARAMETER), field.name, field.vartype, null);
				params.add(param);

				// this.field = field;
				stats.add(maker.Exec(
				 maker.Assign(
				  maker.Select(maker.Ident(ns.fromString("this")), field.name),
				  maker.Ident(field.name)
				 )
				));
			}

			// Javac 构造函数的返回值类型必须传入 null
			JCMethodDecl constructor = maker.MethodDef(
			 maker.Modifiers(Flags.PUBLIC),
			 ns.init,
			 null,
			 List.nil(),
			 params.toList(),
			 List.nil(),
			 maker.Block(0, stats.toList()),
			 null
			);
			tree.defs = tree.defs.append(constructor);
		} else {
			// 如果已经有构造方法（如显式声明的或顶层已经生成的）
			tree.defs.stream().filter(d -> d instanceof JCMethodDecl).map(d -> (JCMethodDecl) d)
			 .filter(m -> m.name.contentEquals(ns.init)).findFirst().ifPresent(m -> {
				 m.mods.flags &= ~(Flags.RECORD | Flags.GENERATEDCONSTR);
				 if (m.sym != null) {
					 m.sym.flags_field &= ~(Flags.RECORD | Flags.GENERATEDCONSTR);
				 }
				 m.body.stats = m.body.stats.appendList(List.from(
					fields.stream().map(field -> maker.Exec(
					 maker.Assign(maker.Select(maker.Ident(ns.fromString("this")), field.name), maker.Ident(field.name))
					)).collect(Collectors.toList())
				 ));
			 });
		}

		// 为每一个字段添加访问器方法
		for (JCVariableDecl field : fields) {
			if (hasMethod(tree, field.name, 0)) continue;

			JCMethodDecl getter = maker.MethodDef(maker.Modifiers(Flags.PUBLIC), field.name, field.vartype,
			 List.nil(), List.nil(), List.nil(), maker.Block(0, List.of(maker.Return(
				maker.Ident(field.name)))), null);
			tree.defs = tree.defs.append(getter);
		}

		ListBuffer<JCStatement> buffer = new ListBuffer<>();
		addEquals(tree, buffer, fields);
		addHashCode(tree, buffer, fields);
		addToString(tree, buffer, fields);
	}

	private static void addEquals(JCClassDecl tree, ListBuffer<JCStatement> buffer,
	                              java.util.List<JCVariableDecl> fields) {
		if (hasMethod(tree, ns.equals, 1)) {
			return;
		}

		buffer.clear();
		JCIf checkThis = maker.If(maker.Binary(Tag.EQ, maker.Ident(ns.fromString("this")), maker.Ident(ns.fromString("other"))),
		 maker.Return(maker.Literal(true)), null);
		buffer.add(checkThis);

		JCIf checkNull = maker.If(maker.Binary(Tag.EQ, maker.Ident(ns.fromString("other")), maker.Literal(TypeTag.BOT, null)),
		 maker.Return(maker.Literal(false)), null);
		buffer.add(checkNull);

		JCIf checkType = maker.If(maker.Binary(Tag.NE, maker.Apply(List.nil(), maker.Select(maker.Ident(ns.fromString("other")), ns.getClass), List.nil()),
			maker.Apply(List.nil(), maker.Select(maker.Ident(ns.fromString("this")), ns.getClass), List.nil())),
		 maker.Return(maker.Literal(false)), null);
		buffer.add(checkType);

		buffer.add(maker.VarDef(maker.Modifiers(Flags.FINAL), ns.fromString("$other"), maker.Ident(tree.name),
		 maker.TypeCast(maker.Ident(tree.name), maker.Ident(ns.fromString("other")))));

		JCExpression resCondition = null;
		for (JCVariableDecl field : fields) {
			JCExpression fieldExpr = maker.Select(maker.Ident(ns.fromString("$other")), field.name);
			JCExpression thisExpr  = maker.Ident(field.name);
			JCExpression condition;
			if (field.vartype != null && field.vartype.hasTag(Tag.TYPEIDENT)) {
				condition = maker.Binary(Tag.EQ, fieldExpr, thisExpr);
			} else {
				condition = maker.Apply(List.nil(), maker.Select(fieldExpr, ns.equals), List.of(thisExpr));
			}
			resCondition = resCondition == null ? condition : maker.Binary(Tag.AND, resCondition, condition);
		}
		if (resCondition == null) {
			resCondition = maker.Literal(true);
		}
		buffer.add(maker.Return(resCondition));

		tree.defs = tree.defs.append(
		 maker.MethodDef(maker.Modifiers(PUBLIC_FINAL), ns.equals, maker.Type(syms.booleanType),
			List.nil(),
			List.of(maker.VarDef(maker.Modifiers(Flags.PARAMETER), ns.fromString("other"), maker.Type(syms.objectType), null)),
			List.nil(),
			maker.Block(0, buffer.toList()), null));
	}

	private static void addHashCode(JCClassDecl tree, ListBuffer<JCStatement> buffer,
	                                java.util.List<JCVariableDecl> fields) {
		if (hasMethod(tree, ns.hashCode, 0)) { return; }

		buffer.clear();
		buffer.add(maker.Return(maker.Apply(List.nil(), maker.Select(maker.Type(syms.objectsType), ns.fromString("hash")),
		 List.from(fields.stream().map(f -> maker.Ident(f.name)).collect(Collectors.toList())))));
		tree.defs = tree.defs.append(
		 maker.MethodDef(maker.Modifiers(PUBLIC_FINAL), ns.hashCode, maker.Type(syms.intType),
			List.nil(),
			List.nil(),
			List.nil(),
			maker.Block(0, buffer.toList()), null));
	}

	private static void addToString(JCClassDecl tree, ListBuffer<JCStatement> buffer,
	                                java.util.List<JCVariableDecl> fields) {
		if (hasMethod(tree, ns.toString, 0)) { return; }

		buffer.clear();
		buffer.add(maker.Return(
		 maker.Binary(Tag.PLUS, maker.Literal(tree.getSimpleName().toString() + "["),
			maker.Binary(Tag.PLUS, fields.stream().map(f -> {
				 return maker.Binary(Tag.PLUS,
					maker.Binary(Tag.PLUS,
					 maker.Binary(Tag.PLUS, maker.Literal(f.name.toString()), maker.Literal("=")),
					 maker.Ident(f.name)),
					maker.Literal(", "));
			 }).reduce(null, (a, b) -> a == null ? b : maker.Binary(Tag.PLUS, a, b)),
			 maker.Literal("]")))
		));

		tree.defs = tree.defs.append(
		 maker.MethodDef(maker.Modifiers(PUBLIC_FINAL), ns.toString, maker.Type(syms.stringType),
			List.nil(),
			List.nil(),
			List.nil(),
			maker.Block(0, buffer.toList()), null));
	}

	public void translateTopLevelClass(JCCompilationUnit toplevel, JCTree tree) {
		try {
			this.toplevel = toplevel;
			translate(tree);
		} catch (Throwable e) {
			err(e);
		} finally {
			this.toplevel = null;
		}
	}
}