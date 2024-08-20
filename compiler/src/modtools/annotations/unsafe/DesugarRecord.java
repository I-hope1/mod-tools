package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Flags;
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
	public void visitClassDef(JCClassDecl tree) {
		super.visitClassDef(tree);
		if ((tree.mods.flags & Flags.RECORD) == 0) return;
		if (tree.type == null) {
			tree.type = tree.sym.type;
		}

		maker.at(tree);
		tree.mods.flags &= ~Flags.RECORD;
		tree.sym.flags_field &= ~Flags.RECORD;
		// tree.mods.flags |= Flags.FINAL;
		java.util.List<JCVariableDecl> fields = tree.defs.stream().filter(d -> d instanceof JCVariableDecl).map(d -> (JCVariableDecl) d).toList();
		// 将private设成public
		fields.forEach(field -> {
			field.mods.flags &= ~Flags.PRIVATE;
			field.mods.flags |= Flags.PUBLIC;
		});

		// 添加构造方法
		tree.defs.stream().filter(d -> d instanceof JCMethodDecl).map(d -> (JCMethodDecl) d)
		 .filter(m -> m.name.contentEquals(ns.init)).findFirst().ifPresent(m -> {
			 m.mods.flags &= ~(Flags.RECORD | Flags.GENERATEDCONSTR);
			 m.sym.flags_field &= ~(Flags.RECORD | Flags.GENERATEDCONSTR);
			 if (m.body.stats.head.toString().equals("super();")) {
				 m.body.stats = m.body.stats.tail; // 删除super()
			 }
			 maker.at(m.body.stats.head);
			 m.body.stats = m.body.stats.prependList(List.from(
				fields.stream().map(field -> maker.Exec(
				 maker.Assign(maker.Select(maker.This(tree.type), field.name), maker.Ident(field.name))
				)).collect(Collectors.toList())
			 ));
		 });
		maker.at(tree);
		// 为每一个字段添加访问器方法
		for (JCVariableDecl field : fields) {
			JCMethodDecl getter = maker.MethodDef(maker.Modifiers(Flags.PUBLIC), field.name, field.vartype,
			 List.nil(), List.nil(), List.nil(), maker.Block(0, List.of(maker.Return(
				maker.Ident(field)))), null);
			tree.defs = tree.defs.append(getter);
		}

		ListBuffer<JCStatement> buffer = new ListBuffer<>();
		// 添加boolean equals(Object other)方法
		// 判断this == other
		JCIf checkThis = maker.If(maker.Binary(Tag.EQ, maker.This(tree.type), maker.Ident(ns.fromString("other"))),
		 maker.Return(maker.Literal(true)), null);
		buffer.add(checkThis);
		// 判断this和other是不是同一个类型 if (!(other instacneof Type $other)) return false;
		JCIf checkType = maker.If(maker.Unary(Tag.NOT, maker.TypeTest(maker.Ident(ns.fromString("other")),
			maker.BindingPattern(
			 maker.VarDef(maker.Modifiers(0), ns.fromString("$other"), maker.Type(tree.type), null)))),
		 maker.Return(maker.Literal(false)), null);
		buffer.add(checkType);
		/* 遍历所有字段，判断是否相等
		 如果是基本数据类型：if (!($other.field == field)) return false;
		 如果是引用类型：if (!($other.field.equals(field))) return false;
		*/
		JCExpression resCondition = null;
		for (JCVariableDecl field : fields) {
			JCExpression fieldExpr = maker.Select(maker.Ident(ns.fromString("$other")), field.name);
			JCExpression thisExpr  = maker.Ident(field);
			JCExpression condition;
			if (field.vartype.type.isPrimitive()) {
				condition = maker.Binary(Tag.EQ, fieldExpr, thisExpr);
			} else {
				condition = maker.Apply(List.nil(), maker.Select(fieldExpr, ns.equals), List.of(thisExpr));
			}
			resCondition = resCondition == null ? condition : maker.Binary(Tag.AND, resCondition, condition);
		}
		buffer.add(maker.Return(resCondition));
		// 声明方法equals
		tree.defs = tree.defs.append(
		 maker.MethodDef(maker.Modifiers(PUBLIC_FINAL), ns.equals, maker.Type(syms.booleanType),
			List.nil(),
			List.of(maker.VarDef(maker.Modifiers(Flags.PARAMETER), ns.fromString("other"), maker.Type(syms.objectType), null)),
			List.nil(),
			maker.Block(0, buffer.toList()), null));

		// 添加int hashCode()
		// 使用Objects.hash(...)
		buffer.clear();
		buffer.add(maker.Return(maker.Apply(List.nil(), maker.Select(maker.QualIdent(syms.objectsType.tsym), ns.fromString("hash")),
		 List.from(fields.stream().map(f -> maker.Ident(f)).collect(Collectors.toList())))));
		tree.defs = tree.defs.append(
		 maker.MethodDef(maker.Modifiers(PUBLIC_FINAL), ns.hashCode, maker.Type(syms.intType),
			List.nil(),
			List.nil(),
			List.nil(),
			maker.Block(0, buffer.toList()), null));

		// 添加String toString()
		// "类名[" +
		// "字段名=" + 字段 + ", "
		// ...
		// "]"
		buffer.clear();
		buffer.add(maker.Return(
		 maker.Binary(Tag.PLUS, maker.Literal(tree.getSimpleName().toString() + "["),
			maker.Binary(Tag.PLUS, fields.stream().map(f -> maker.Binary(Tag.PLUS,
				 maker.Binary(Tag.PLUS, maker.Binary(Tag.PLUS, maker.Literal(f.name.toString()), maker.Literal("=")), maker.Ident(f)), maker.Literal(", ")))
				.reduce(null, (a, b) -> a == null ? b : maker.Binary(Tag.PLUS, a, b)),
			 maker.Literal("]")))));


		tree.defs = tree.defs.append(
		 maker.MethodDef(maker.Modifiers(PUBLIC_FINAL), ns.toString, maker.Type(syms.stringType),
			List.nil(),
			List.nil(),
			List.nil(),
			maker.Block(0, buffer.toList()), null));

		// println("-------DesugarRecord-------");
		// println(tree);
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
