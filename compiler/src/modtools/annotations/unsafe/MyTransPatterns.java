package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

public class MyTransPatterns extends TransPatterns {
	final SwitchDesugar  switchDesugar;
	final LambdaToMethod ltm;
	public MyTransPatterns(Context context) {
		super(context);
		switchDesugar = new SwitchDesugar(context);
		ltm = LambdaToMethod.instance(context);
	}
	public JCTree translateTopLevelClass(Env<AttrContext> env, JCTree cdef, TreeMaker make) {
		switchDesugar.translate(cdef);
		return super.translateTopLevelClass(env, cdef, make);
	}
}
class SwitchDesugar extends TreeTranslator {
	final        Symtab    syms;
	final        TreeMaker make;
	final        Names     names;
	public final Check     chk;

	SwitchDesugar(Context context) {
		syms = Symtab.instance(context);
		make = TreeMaker.instance(context);
		names = Names.instance(context);
		chk = Check.instance(context);
	}

	MethodSymbol currentMethod;
	public void visitMethodDef(JCMethodDecl tree) {
		MethodSymbol prev = currentMethod;
		currentMethod = tree.sym;
		super.visitMethodDef(tree);
		currentMethod = prev;
	}
	public void visitSwitchExpression(JCSwitchExpression tree) {
		if (transSwitch(tree.cases, tree.selector, tree.type)) return;
		super.visitSwitchExpression(tree);
	}
	@Override
	public void visitSwitch(JCSwitch tree) {
		if (transSwitch(tree.cases, tree.selector, null)) return;
		super.visitSwitch(tree);
	}
	class YieldTranslator extends TreeTranslator {
		int i = 0;
		String getName() {
			return "$switch" + i++;
		}
		JCVariableDecl variable;
		public void init(Type exprType) {
			variable = make.VarDef(new VarSymbol(0, names.fromString(getName()), exprType, currentMethod), null);
		}
		public <T extends JCTree> T translate(T tree) {
			if (tree instanceof JCYield yield) {
				return (T) make.Exec(make.Assign(make.Ident(variable), yield.value).setType(variable.type));
			}
			return super.translate(tree);
		}
	}
	final YieldTranslator yieldTranslator = new YieldTranslator();
	boolean transSwitch(List<JCCase> cases, JCExpression selector, Type exprType) {
		if (cases.stream().anyMatch(c -> c.labels.stream().anyMatch(l -> l instanceof JCPatternCaseLabel))) {
			if (exprType != null) {
				yieldTranslator.init(exprType);
				yieldTranslator.translate(cases);
			}

			JCIf        first  = null;
			JCStatement lastIf = null;
			for (JCCase jcCase : cases) {
				JCStatement smt = translateCase(selector, jcCase);
				if (first == null) first = (JCIf) smt;
				if (lastIf instanceof JCIf jcIf) jcIf.elsepart = smt;
				lastIf = smt;
			}

			result = first;
			if (exprType != null) {
				LetExpr expr = make.LetExpr(List.of(
				 yieldTranslator.variable,
				 first
				), make.Ident(yieldTranslator.variable));
				expr.type = exprType;
				expr.needsCond = true;
				result = expr;
				// println(expr);
			}
			return true;
		}
		return false;
	}

	JCStatement translateCase(JCExpression selector, JCCase jcCase) {
		JCExpression ifCondition = null;

		make.at(jcCase);
		JCStatement truepart = jcCase.body instanceof JCBlock b ? b : make.Block(0, jcCase.stats);
		for (JCCaseLabel label : jcCase.labels) {
			make.at(label);
			if (label instanceof JCPatternCaseLabel patternLabel) {
				JCPattern pattern = patternLabel.pat;
				ifCondition = makePatternMatchCondition(ifCondition, selector, pattern);
			} else if (label instanceof JCConstantCaseLabel constantLabel) {
				JCExpression condition = make.Binary(Tag.EQ, selector, constantLabel.expr);
				ifCondition = ifCondition != null ? make.Binary(Tag.OR, ifCondition, condition) : condition;
			} else if (label instanceof JCDefaultCaseLabel defaultLabel) {
				ifCondition = null;
			}
			// 可以根据需要处理其他类型的label
		}

		return ifCondition == null ? truepart : make.If(ifCondition, truepart, null);
	}

	JCExpression makePatternMatchCondition(JCExpression condition, JCExpression selector, JCPattern pattern) {
		if (pattern instanceof JCBindingPattern bindingPattern) {
			make.at(pattern);
			JCExpression test = make.TypeTest(selector,
			 bindingPattern.var.name.isEmpty() ? make.Ident(bindingPattern.type.tsym) : bindingPattern)
			 .setType(syms.booleanType);
			return condition != null ? Replace.desugarStringTemplate.makeBinary(Tag.OR, condition, test) : test;
		}
		// 处理其他类型的模式
		return condition;
		// + make.Literal(true);
	}
}


