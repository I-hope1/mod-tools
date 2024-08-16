package modtools.annotations.unsafe;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.comp.Operators;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import modtools.annotations.HopeReflect;

import java.lang.reflect.Method;
import java.util.*;

import static modtools.annotations.PrintHelper.SPrinter.err;

public class DesugarStringTemplate extends TreeTranslator {
	final Symtab     syms;
	final TreeMaker  make;
	final Names      names;
	final JavacTrees trees;
	final Operators  operators;

	public DesugarStringTemplate(Context context) {
		syms = Symtab.instance(context);
		make = TreeMaker.instance(context);
		names = Names.instance(context);
		trees = JavacTrees.instance(context);
		operators = Operators.instance(context);
	}
	@Override
	public void visitStringTemplate(JCStringTemplate template) {
		if (template.processor instanceof JCIdent i && !Objects.equals(i.toString(), "STR") &&
		    trees.getElement(trees.getPath(toplevel, i)) instanceof VarSymbol var &&
		    trees.getTree(var) instanceof JCVariableDecl variableDecl) {
			assert var.isStatic() && var.isFinal();
			assert variableDecl.init instanceof JCLambda;
			class InterpolateToConcat extends TreeCopier<Void> {
				public InterpolateToConcat(TreeMaker M) {
					super(M);
				}
				public JCTree visitMethodInvocation(MethodInvocationTree node, Void v) {
					if (node.getMethodSelect().toString().endsWith("interpolate")) {
						make.at(((JCMethodInvocation) node));
						return concatExpression(template.fragments, template.expressions);
					}
					return super.visitMethodInvocation(node, v);
				}
			}
			var interpolate = new InterpolateToConcat(make);
			result = interpolate.copy(((JCLambda) variableDecl.init).body);
			return;
		}
		super.visitStringTemplate(template);
	}
	JCExpression makeLit(Type type, Object value) {
		return make.Literal(type.getTag(), value).setType(type.constType(value));
	}

	JCExpression makeString(String string) {
		return makeLit(syms.stringType, string);
	}
	Method method = HopeReflect.nl(() -> Operators.class.getDeclaredMethod("resolveBinary", DiagnosticPosition.class, Tag.class, Type.class, Type.class));
	JCBinary makeBinary(JCExpression lhs, JCExpression rhs) {
		JCBinary tree = make.Binary(Tag.PLUS, lhs, rhs);
		tree.operator = HopeReflect.iv(method, operators, tree, Tag.PLUS, lhs.type, rhs.type);
		tree.type = tree.operator.type.getReturnType();
		return tree;
	}
	JCExpression concatExpression(List<String> fragments, List<JCExpression> expressions) {
		JCExpression           expr     = null;
		Iterator<JCExpression> iterator = expressions.iterator();
		for (String fragment : fragments) {
			expr = expr == null ? makeString(fragment)
			 : makeBinary(expr, makeString(fragment));
			if (iterator.hasNext()) {
				JCExpression expression     = iterator.next();
				Type         expressionType = expression.type;
				expr = makeBinary(expr, expression.setType(expressionType));
			}
		}
		return expr;
	}
	JCCompilationUnit toplevel;
	public void translateTopLevelClass(JCCompilationUnit toplevel, JCTree tree) {
		/* if (tree instanceof JCClassDecl classDecl && classDecl.name.contentEquals("HopeString")) {
			println(tree);
		} */
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
