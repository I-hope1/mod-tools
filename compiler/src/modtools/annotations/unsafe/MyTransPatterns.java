package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

import java.util.function.*;

import static modtools.annotations.PrintHelper.SPrinter.println;

public class MyTransPatterns extends TransPatterns {
	final AA aa;
	public MyTransPatterns(Context context) {
		super(context);
		aa = new AA(context);
	}
	public JCTree translateTopLevelClass(Env<AttrContext> env, JCTree cdef, TreeMaker make) {
		aa.translate(cdef);
		return super.translateTopLevelClass(env, cdef, make);
	}
}
class AA extends TreeTranslator {
	final Symtab    syms;
	final TreeMaker make;
	final Names     names;

	AA(Context context) {
		this.syms = Symtab.instance(context);
		this.make = TreeMaker.instance(context);
		this.names = Names.instance(context);
	}
	Function<JCExpression, JCStatement> func;
	public void visitReturn(JCReturn tree) {
		if (tree.expr instanceof JCSwitchExpression expression &&
		    expression.cases.stream().anyMatch(c -> c.labels.stream().anyMatch(l -> l instanceof JCPatternCaseLabel))) {
			func = make::Return;
			visitSwitchExpression(expression);
			func = null;
			return;
		}
		super.visitReturn(tree);
	}
	public void visitVarDef(JCVariableDecl tree) {
		if (tree.init instanceof JCSwitchExpression expression &&
		    expression.cases.stream().anyMatch(c -> c.labels.stream().anyMatch(l -> l instanceof JCPatternCaseLabel))) {
			tree.init = null;
			func = v -> make.Exec(make.Assign(tree.vartype, v));
			visitSwitchExpression(expression);
			func = null;
			return;
		}
		super.visitVarDef(tree);
	}

	public void visitSwitchExpression(JCSwitchExpression tree) {
		if (tree.cases.stream().anyMatch(c -> c.labels.stream().anyMatch(l -> l instanceof JCPatternCaseLabel))) {
			JCIf        first  = null;
			JCStatement lastIf = null;
			for (JCCase jcCase : tree.cases) {
				JCStatement smt = translateCase(tree.selector, jcCase);
				if (first == null) first = (JCIf) smt;
				if (lastIf instanceof JCIf jcIf) jcIf.elsepart = smt;
				lastIf = smt;
			}

			result = super.translate(first);
			println(result);
			return;
		}
		super.visitSwitchExpression(tree);
	}
	@Override
	public void visitSwitch(JCSwitch tree) {
		if (tree.cases.stream().anyMatch(c -> c.labels.stream().anyMatch(l -> l instanceof JCPatternCaseLabel))) {
			JCIf        first  = null;
			JCStatement lastIf = null;
			for (JCCase jcCase : tree.cases) {
				JCStatement smt = translateCase(tree.selector, jcCase);
				if (first == null) first = (JCIf) smt;
				if (lastIf instanceof JCIf jcIf) jcIf.elsepart = smt;
				lastIf = smt;
			}

			result = first;
			// println(result);
			return;
		}
		super.visitSwitch(tree);
	}

	private JCStatement translateCase(JCExpression selector, JCCase jcCase) {
		JCExpression ifCondition = null;
		if (func != null) {
			translateStats(jcCase, func);
		}
		make.at(jcCase);
		JCStatement truepart = jcCase.body instanceof JCBlock b ? b : make.Block(0, jcCase.stats);
		for (JCCaseLabel label : jcCase.labels) {
			make.at(label);
			if (label instanceof JCPatternCaseLabel patternLabel) {
				JCPattern    pattern   = patternLabel.pat;
				JCExpression condition = makePatternMatchCondition(selector, pattern);
				ifCondition = ifCondition != null ? make.Binary(Tag.OR, ifCondition, condition) : condition;
			} else if (label instanceof JCConstantCaseLabel constantLabel) {
				JCExpression condition = make.Binary(JCTree.Tag.EQ, selector, constantLabel.expr);
				ifCondition = ifCondition != null ? make.Binary(Tag.OR, ifCondition, condition) : condition;
			} else if (label instanceof JCDefaultCaseLabel defaultLabel) {
				ifCondition = null;
			}
			// 可以根据需要处理其他类型的label
		}

		return ifCondition == null ? truepart : make.If(ifCondition, truepart, null);
	}

	private void translateStats(JCCase jcCase, Function<JCExpression, JCStatement> func) {
		List<JCStatement> stats = jcCase.stats;
		TreeTranslator translator = new TreeTranslator() {
			public <T extends JCTree> T translate(T tree) {
				if (tree instanceof JCYield yield) return (T) func.apply(yield.value);
				return super.translate(tree);
			}
		};
		translator.translate(stats);
		// return make.Apply(List.nil(), make.Select(make.TypeCast(make.Ident(names.fromString("Supplier")),
		//  make.Lambda(List.nil(), make.Block(0, stats))), names.fromString("get")), List.nil());
	}

	private JCExpression makePatternMatchCondition(JCExpression selector, JCPattern pattern) {
		if (pattern instanceof JCBindingPattern bindingPattern) {
			return make.TypeTest(selector, bindingPattern);
		}
		// 处理其他类型的模式
		return make.Literal(true); // 默认情况，应该根据实际需求修改
	}
}
