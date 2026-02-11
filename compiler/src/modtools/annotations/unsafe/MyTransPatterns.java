package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

/** 包含switch语法的脱糖 */
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
/** TODO: owner可能有问题  */
class SwitchDesugar extends TreeTranslator {
	public static final String LABEL_SWITCH = "label$switch";

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
	ClassSymbol currentClass;
	public void visitClassDef(JCClassDecl tree) {
		ClassSymbol prev = currentClass;
		currentClass = tree.sym;
		super.visitClassDef(tree);
		currentClass = prev;
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
			variable = exprType != null ? make.VarDef(new VarSymbol(0, names.fromString(getName()), exprType, currentMethod), null) : null;
		}
		public <T extends JCTree> T translate(T tree) {
			if (tree instanceof JCYield yield && variable != null) {
				return (T) make.Exec(make.Assign(make.Ident(variable), yield.value).setType(variable.type));
			}
			return super.translate(tree);
		}
	}
	class BreakTranslator extends TreeTranslator {
		public void visitForLoop(JCForLoop tree) {
			result = tree;
		}
		public void visitForeachLoop(JCEnhancedForLoop tree) {
			result = tree;
		}
		public void visitMethodDef(JCMethodDecl tree) {
			result = tree;
		}
		public void visitClassDef(JCClassDecl tree) {
			result = tree;
		}
		public void visitBreak(JCBreak tree) {
			if (tree.target == null) {
				tree.target = make.Ident(names.fromString(LABEL_SWITCH));
			}
			super.visitBreak(tree);
		}
	}
	final YieldTranslator yieldTranslator = new YieldTranslator();
	final BreakTranslator breakTranslator = new BreakTranslator();
	boolean transSwitch(List<JCCase> cases, JCExpression selector, Type exprType) {
		if (cases.stream().anyMatch(c -> c.labels.stream().anyMatch(l -> l instanceof JCPatternCaseLabel))) {
			if (exprType != null) {
				yieldTranslator.init(exprType);
				yieldTranslator.translate(cases);
			}
			if (exprType == null) {
				breakTranslator.translate(cases);
			}
			make.at(selector);
			JCBlock        block        = make.Block(0, List.nil());
			VarSymbol      selector_var = new VarSymbol(0, names.fromString("$switch$input"), selector.type, currentMethod != null ? currentMethod : currentClass);
			block.stats = block.stats.append(make.VarDef(selector_var, selector));

			JCIf        first  = null;
			JCStatement lastIf = null;
			JCCase[] array = cases.stream()
			 .sorted((a, b) -> a == b ? 0/* 可能不会发生 */ : a.labels.stream().anyMatch(l -> l instanceof JCDefaultCaseLabel) ? 1 : -1)
			 .toArray(JCCase[]::new);
			for (JCCase jcCase : array) {
				JCStatement smt = translateCase(make.Ident(selector_var), jcCase);
				if (first == null) first = (JCIf) smt;
				if (lastIf instanceof JCIf jcIf) jcIf.elsepart = smt;
				lastIf = smt;
			}
			JCLabeledStatement labeledStatement = make.Labelled(names.fromString(LABEL_SWITCH), block);
			labeledStatement.body = first;
			block.stats = block.stats.append(labeledStatement);

			result = block;
			if (exprType != null) {
				LetExpr expr = make.LetExpr(List.of(
				 yieldTranslator.variable,
				 block
				), make.Ident(yieldTranslator.variable));
				expr.type = exprType;
				expr.needsCond = true;
				result = expr;
			}
			// println(result);
			return true;
		}
		return false;
	}

	// ------------
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
				JCExpression condition = makeBinary(Tag.EQ, selector, constantLabel.expr);
				ifCondition = ifCondition != null ? makeBinary(Tag.OR, ifCondition, condition) : condition;
			} else if (label instanceof JCDefaultCaseLabel defaultLabel) {
				ifCondition = null;
			}
			// 可以根据需要处理其他类型的label
		}

		if (jcCase.guard != null) {
			ifCondition = ifCondition == null ? jcCase.guard : makeBinary(Tag.AND, ifCondition, jcCase.guard);
		}

		return ifCondition == null ? truepart : make.If(ifCondition, truepart, null);
	}
	JCBinary makeBinary(Tag tag, JCExpression lhs, JCExpression rhs) {
		return Replace.desugarStringTemplate.makeBinary(tag, lhs, rhs);
	}

	JCExpression makePatternMatchCondition(JCExpression condition, JCExpression selector, JCPattern pattern) {
		if (pattern instanceof JCBindingPattern bindingPattern) {
			make.at(pattern);
			if (bindingPattern.type == syms.objectType) return condition;
			JCTree tree = bindingPattern.var.name.isEmpty() ? make.Ident(bindingPattern.type.tsym) : make.BindingPattern(bindingPattern.var).setType(bindingPattern.type);
			JCExpression test = make.TypeTest(selector, tree)
			 .setType(syms.booleanType);
			return condition != null ? makeBinary(Tag.OR, condition, test) : test;
		}
		// 处理其他类型的模式
		return condition;
		// + make.Literal(true);
	}
}
