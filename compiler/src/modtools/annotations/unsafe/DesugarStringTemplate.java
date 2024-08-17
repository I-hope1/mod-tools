package modtools.annotations.unsafe;

import com.sun.source.tree.*;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.comp.Operators;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import modtools.annotations.HopeReflect;

import java.lang.reflect.Method;
import java.util.*;

import static modtools.annotations.PrintHelper.SPrinter.*;

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
			JCLambda lambda    = (JCLambda) variableDecl.init;
			Name     parmaName = lambda.params.get(0).name;
			class InterpolateToConcat extends TreeCopier<Void> {
				public InterpolateToConcat(TreeMaker M) {
					super(M);
				}
				public JCTree visitMethodInvocation(MethodInvocationTree node, Void v) {
					JCMethodInvocation invocation = (JCMethodInvocation) node;
					make.at(invocation);
					if (invocation.toString().equals(parmaName + ".interpolate()")) {
						return concatExpression(template.fragments, template.expressions);
					} else if (invocation.toString().startsWith(parmaName + ".fragments().get(")) {
						if (!(invocation.args.get(0) instanceof JCLiteral literal && literal.value instanceof Integer integer))
							throw new IllegalArgumentException("" + invocation);
						return makeString(template.fragments.get(integer));
					} else if (invocation.toString().startsWith(parmaName + ".values().get(")) {
						if (!(invocation.args.get(0) instanceof JCLiteral literal && literal.value instanceof Integer integer))
							throw new IllegalArgumentException("" + invocation);
						return template.expressions.get(integer);
					}
					return super.visitMethodInvocation(node, v);
				}
			}
			var interpolate = new InterpolateToConcat(make);
			result = interpolate.copy(lambda.body);
			// result = ParserFactory.instance(Replace.context)
			//  .newParser(result.toString(), false, true, true)
			//  .parseExpression();
			println(result);
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
	static Method operatorMethod = HopeReflect.nl(() -> Operators.class.getDeclaredMethod("resolveBinary", DiagnosticPosition.class, Tag.class, Type.class, Type.class));
	JCBinary makeBinary(Tag tag, JCExpression lhs, JCExpression rhs) {
		JCBinary tree = make.Binary(tag, lhs, rhs);
		tree.operator = HopeReflect.iv(operatorMethod, operators, tree, tag, lhs.type, rhs.type);
		tree.type = tree.operator.type.getReturnType();
		return tree;
	}
	JCExpression concatExpression(List<String> fragments, List<JCExpression> expressions) {
		JCExpression           expr     = null;
		Iterator<JCExpression> iterator = expressions.iterator();
		for (String fragment : fragments) {
			expr = expr == null ? makeString(fragment)
			 : makeBinary(Tag.PLUS, expr, makeString(fragment));
			if (iterator.hasNext()) {
				JCExpression expression = iterator.next();
				expr = makeBinary(Tag.PLUS, expr, expression);
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
