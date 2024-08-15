package modtools.annotations.unsafe;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

import java.util.function.Function;

public class MyTransPatterns extends TransPatterns {
	final SwitchDesugar   switchDesugar;
	final LambdaToMethod  ltm;
	final DefaultToStatic dts;
	public MyTransPatterns(Context context) {
		super(context);
		switchDesugar = new SwitchDesugar(context);
		ltm = LambdaToMethod.instance(context);
		dts = new DefaultToStatic(context);
	}
	public JCTree translateTopLevelClass(Env<AttrContext> env, JCTree cdef, TreeMaker make) {
		switchDesugar.translate(cdef);
		l:
		if (env.enclClass.sym.isInterface()) {
			dts.translateTopLevelClass(env, cdef);
			// println(cdef);
		}
		return super.translateTopLevelClass(env, cdef, make);
	}
}
class SwitchDesugar extends TreeTranslator {
	final Symtab    syms;
	final TreeMaker make;
	final Names     names;

	SwitchDesugar(Context context) {
		syms = Symtab.instance(context);
		make = TreeMaker.instance(context);
		names = Names.instance(context);
	}

	Function<JCExpression, JCStatement> func;
	@Override
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
		if (transSwitch(tree.cases, tree.selector)) return;
		super.visitSwitchExpression(tree);
	}
	@Override
	public void visitSwitch(JCSwitch tree) {
		if (transSwitch(tree.cases, tree.selector)) return;
		super.visitSwitch(tree);
	}
	boolean transSwitch(List<JCCase> cases, JCExpression selector) {
		if (cases.stream().anyMatch(c -> c.labels.stream().anyMatch(l -> l instanceof JCPatternCaseLabel))) {
			JCIf        first  = null;
			JCStatement lastIf = null;
			for (JCCase jcCase : cases) {
				JCStatement smt = translateCase(selector, jcCase);
				if (first == null) first = (JCIf) smt;
				if (lastIf instanceof JCIf jcIf) jcIf.elsepart = smt;
				lastIf = smt;
			}

			result = first;
			// println(result);
			return true;
		}
		return false;
	}

	JCStatement translateCase(JCExpression selector, JCCase jcCase) {
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
				JCExpression condition = make.Binary(Tag.EQ, selector, constantLabel.expr);
				ifCondition = ifCondition != null ? make.Binary(Tag.OR, ifCondition, condition) : condition;
			} else if (label instanceof JCDefaultCaseLabel defaultLabel) {
				ifCondition = null;
			}
			// 可以根据需要处理其他类型的label
		}

		return ifCondition == null ? truepart : make.If(ifCondition, truepart, null);
	}

	void translateStats(JCCase jcCase, Function<JCExpression, JCStatement> func) {
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

	JCExpression makePatternMatchCondition(JCExpression selector, JCPattern pattern) {
		if (pattern instanceof JCBindingPattern bindingPattern) {
			return make.TypeTest(selector, bindingPattern);
		}
		// 处理其他类型的模式
		return make.Literal(true); // 默认情况，应该根据实际需求修改
	}
}


class DefaultToStatic extends TreeTranslator {
	final Symtab syms;
	final TreeMaker make;
	final Names     names;
	final Enter enter;
	public final TypeEnter typeEnter;

	DefaultToStatic(Context context) {
		syms = Symtab.instance(context);
		make = TreeMaker.instance(context);
		names = Names.instance(context);
		enter = Enter.instance(context);
		typeEnter = TypeEnter.instance(context);
	}


	ListBuffer<JCTree> toAppendMethods = new ListBuffer<>();
	boolean            hasLambda;
	JCMethodDecl       genMethod;
	JCVariableDecl     self;
	public void visitMethodDef(JCMethodDecl tree) {
		if (!tree.sym.isDefault()) {
			result = tree;
			return;
		}
		// println(Flags.toString(tree.mods.flags) + "." + tree.sym);
		class LambdaScanner extends TreeScanner {
			boolean hasLambda;
			public void visitLambda(JCLambda tree) {
				hasLambda = true;
			}
		}
		LambdaScanner scanner = new LambdaScanner();
		tree.body.accept(scanner);
		if (scanner.hasLambda) {
			MethodSymbol enclMethod = tree.sym;
			VarSymbol    varSymbol  = new VarSymbol(0, names.fromString("default$this"), enclMethod.owner.type, enclMethod);
			self = make.VarDef(varSymbol, null);
			genMethod = make.MethodDef(make.Modifiers(Flags.STATIC | Flags.PUBLIC),
			 names.fromString(tree.name + "$default"), tree.restype,
			 tree.typarams,
			 tree.recvparam,
			 tree.params.prepend(self),
			 tree.thrown,
			 null,
			 tree.defaultValue);
			genMethod.params = tree.params.prepend(self);
			genMethod.body = translate(tree.body);
			genMethod.accept(enter);
			// println(genMethod);
			JCMethodInvocation apply = make.Apply(
			 List.nil(), make.Ident(enclMethod),
			 tree.params.map(make::Ident).prepend(make.This(enclMethod.owner.type))
			);

			// apply.type = ms.type;
			tree.body = make.Block(0, List.of(enclMethod.getReturnType() == syms.voidType ? make.Exec(apply)
			 : make.Return(apply)));
			tree.body.accept(enter);

			toAppendMethods.add(genMethod);
		}

		genMethod = null;
		self = null;
		result = tree;
	}

	public void visitIdent(JCIdent tree) {
		if (tree.name == names._this && self != null) {
			make.at(tree);
			result = make.Ident(self);
			return;
		}
		super.visitIdent(tree);
	}
	public void visitApply(JCMethodInvocation tree) {
		super.visitApply(tree);
		if (result instanceof JCMethodInvocation invocation
		    && invocation.meth instanceof JCIdent i &&
		    !hasImport(i.sym) && self != null) {
			make.at(i);
			invocation.meth = make.Select(make.Ident(self), i.sym);
		}
	}
	public void visitSelect(JCFieldAccess tree) {
		if (tree.name == names._this) {
			make.at(tree);
			result = make.Ident(self);
			return;
		}
		super.visitSelect(tree);
	}
	private boolean hasImport(Symbol symbol) {
		return env.toplevel.namedImportScope.includes(symbol)
		       || env.toplevel.starImportScope.includes(symbol);
	}
	Env<AttrContext> env;
	public void translateTopLevelClass(Env<AttrContext> env, JCTree cdef) {
		try {
			this.env = env;
			translate(cdef);
			// println(cdef);
		} finally {
			((JCClassDecl) cdef).defs = ((JCClassDecl) cdef).defs.appendList(toAppendMethods);
			toAppendMethods.clear();
			hasLambda = false;
			this.env = null;
		}
	}
}