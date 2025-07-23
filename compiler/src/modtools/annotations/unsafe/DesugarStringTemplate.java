package modtools.annotations.unsafe;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.comp.Operators;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import modtools.annotations.HopeReflect;

import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;
import java.lang.reflect.Method;
import java.util.*;

import static modtools.annotations.PrintHelper.SPrinter.err;

public class DesugarStringTemplate extends TreeTranslator {
	public static final boolean DISABLED = false;

	final Filer         filer;
	final Symtab        syms;
	final TreeMaker     make;
	final Names         names;
	final JavacTrees    trees;
	final Operators     operators;
	final Log           log;
	final JavacElements elements;
	final TopTranslator topTranslator;

	int lastID = 0;

	public DesugarStringTemplate(Context context) {
		filer = Replace.filer;
		syms = Symtab.instance(context);
		make = TreeMaker.instance(context);
		names = Names.instance(context);
		trees = JavacTrees.instance(context);
		operators = Operators.instance(context);
		log = Log.instance(context);
		elements = JavacElements.instance(context);
		topTranslator = TopTranslator.instance(context);
	}
	@Override
	public void visitStringTemplate(JCStringTemplate template) {
		if (template.processor instanceof JCIdent i && !Objects.equals(i.toString(), "STR") &&
		    trees.getElement(trees.getPath(toplevel, i)) instanceof VarSymbol varSym &&
		    trees.getTree(varSym) instanceof JCVariableDecl variableDecl) {
			// assert varSym.isStatic() && varSym.isFinal();
			if (!varSym.isStatic() && varSym.isFinal()) {
				log.useSource(toplevel.sourcefile);
				log.error(variableDecl.startPos, err("StringTemplate processor must be static final"));
				return;
			}
			// assert variableDecl.init instanceof JCLambda;
			if (!(variableDecl.init instanceof JCLambda lambda)) {
				log.useSource(toplevel.sourcefile);
				log.error(variableDecl.startPos, err("StringTemplate processor must be a lambda"));
				return;
			}
			JCVariableDecl paramFrag = null, paramExp = null;
			if (lambda.body instanceof JCBlock block) {
				block.pos = template.pos;

				ClassType listType = (ClassType) syms.listType.constType(1);
				listType.typarams_field = List.nil();
				paramFrag = make.Param(names.fromString("$fragments"), listType, null);
				paramExp = make.Param(names.fromString("$expressions"), listType, null);
			}
			Name           parmaName      = lambda.params.get(0).name;
			JCVariableDecl finalParmaFrag = paramFrag;
			JCVariableDecl finalParmaExp  = paramExp;
			class InterpolateToConcat extends TreeCopier<Void> {
				public InterpolateToConcat(TreeMaker M) {
					super(M);
				}
				public JCTree visitMethodInvocation(MethodInvocationTree node, Void v) {
					JCMethodInvocation invocation = (JCMethodInvocation) node;
					make.at(invocation);
					if (invocation.toString().equals(parmaName + ".interpolate()")) {
						return concatExpression(template.fragments, template.expressions);
					} else if (finalParmaFrag == null && invocation.toString().startsWith(parmaName + ".fragments().get(")) {
						if (!(invocation.args.get(0) instanceof JCLiteral literal && literal.value instanceof Integer integer)) {
							throw new IllegalArgumentException("" + invocation);
						}
						return makeString(template.fragments.get(integer));
					} else if (finalParmaExp == null && invocation.toString().startsWith(parmaName + ".values().get(")) {
						if (!(invocation.args.get(0) instanceof JCLiteral literal && literal.value instanceof Integer integer)) {
							throw new IllegalArgumentException("" + invocation);
						}
						return template.expressions.get(integer);
					} else if (finalParmaFrag != null && invocation.toString().equals(parmaName + ".fragments()")) {
						return makeIdent(finalParmaFrag);
					} else if (finalParmaExp != null && invocation.toString().equals(parmaName + ".values()")) {
						return makeIdent(finalParmaExp);
					}
					return super.visitMethodInvocation(node, v);
				}
				/* public JCTree visitVariable(VariableTree node, Void unused) {
					return super.visitVariable(node, unused).setType(((JCExpression) node.getInitializer()).type);
				} */
			}
			var interpolate = new InterpolateToConcat(make);
			result = interpolate.copy(lambda.body);

			JCClassDecl classDecl1 = trees.getTree((TypeElement) varSym.owner);

			if (result instanceof JCBlock block) {
				make.at(template);
				String classSimpleName = "_" + variableDecl.name;
				// String       className       = varSym.packge() + "." + classSimpleName;
				String       methodName = "interpolate" + lastID++;
				JCMethodDecl interpolateMethod;
				JCClassDecl  genClassDecl;

				// 在varSym.owner中创建内部类
				interpolateMethod = make.MethodDef(
				 make.Modifiers(Flags.PUBLIC | Flags.STATIC),
				 names.fromString(methodName),
				 make.Ident(template.type.tsym).setType(template.type),
				 List.nil(),
				 List.of(paramFrag, paramExp),
				 List.nil(),
				 block,
				 null
				);
				// genClassDecl = make.ClassDef(
				//  make.Modifiers(Flags.PUBLIC | Flags.STATIC),
				//  names.fromString(classSimpleName),
				//  List.nil(),
				//  null,
				//  List.nil(),
				//  List.of(interpolateMethod));
				classDecl1.defs = classDecl1.defs.append(interpolateMethod);
				/* String classDecl = STR."""
					\{trees.getPath(varSym).getCompilationUnit().getImports().stream().map(String::valueOf).collect(Collectors.joining())}
				  public class \{classSimpleName} {
				 			public static \{template.type} \{methodName}(\{paramFrag}, \{paramExp}) \{block}
				 	}
				 """;
				JavaFileObject sourceFile;
				try {
					sourceFile = filer.createSourceFile(className);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				try (var writer = sourceFile.openWriter()) {
					writer.write(classDecl);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				ClassSymbol sym = syms.enterClass(syms.unnamedModule, names.fromString(className));
				sym.sourcefile = sourceFile;
				sym.complete(); */

				MethodSymbol interpolateMethodSymbol = new MethodSymbol(Flags.PUBLIC | Flags.STATIC,
				 names.fromString(methodName),
				 new MethodType(List.of(finalParmaFrag.type, finalParmaExp.type), template.type, List.nil(), (TypeSymbol) varSym.owner),
				 varSym.owner);
				finalParmaExp.sym.owner = finalParmaFrag.sym.owner = interpolateMethodSymbol;
				// interpolateMethodSymbol.params = interpolateMethod.params.map(x -> x.sym);
				varSym.owner.members().enter(interpolateMethodSymbol);

				interpolateMethod.sym = interpolateMethodSymbol;
				result = make.App(make.Select(make.QualIdent(varSym.owner), interpolateMethod.sym),
				 List.of(makeStringList(template.fragments), makeList(template.expressions)));
			}

			thenRun(() -> {
				variableDecl.vartype = make.Ident(syms.objectType.tsym);
				variableDecl.mods.flags &= ~Flags.FINAL;
				variableDecl.sym.flags_field &= ~Flags.FINAL;
				variableDecl.init = null;
			});
			// result = ParserFactory.instance(Replace.context)
			//  .newParser(result.toString(), false, true, true)
			//  .parseExpression();
			// println(result);;
			return;
		}
		super.visitStringTemplate(template);
	}
	ArrayList<Runnable> thenRuns = new ArrayList<>();
	private void thenRun(Runnable runnable) {
		thenRuns.add(runnable);
	}
	JCTree makeIdent(JCVariableDecl variableDecl) {
		return make.Ident(variableDecl);
	}
	public JCMethodDecl makeMethod(String name, List<JCVariableDecl> params, Type returnType, JCBlock body,
	                               ClassSymbol owner) {
		MethodType   methodType   = new MethodType(params.map(p -> p.type), returnType, List.nil(), syms.methodClass);
		MethodSymbol methodSymbol = new MethodSymbol(Flags.PUBLIC | Flags.STATIC, names.fromString(name), returnType, owner);
		methodSymbol.type = methodType;
		List<VarSymbol> params1 = methodSymbol.params();
		for (int i = 0, paramsSize = params1.size(); i < paramsSize; i++) {
			params1.get(i).name = params.get(i).name;
		}
		return make.MethodDef(methodSymbol, body);
	}
	public void visitLambda(JCLambda tree) {
		tree.body = translate(tree.body);
		result = tree;
	}
	public void visitClassDef(JCClassDecl tree) {
		classDecl = tree;
		tree.defs = translate(tree.defs);
		classDecl = null;
		result = tree;
	}
	public List<JCTypeParameter> translateTypeParams(List<JCTypeParameter> trees) {
		return trees;
	}
	JCExpression makeLit(Type type, Object value) {
		return make.Literal(type.getTag(), value).setType(type.constType(value));
	}
	JCExpression makeStringList(List<String> strings) {
		Symbol list = syms.arraysType.tsym.members().findFirst(names.fromString("asList"));
		// code: Arrays.asList(trees)
		return make.App(make.Select(make.Ident(syms.arraysType.tsym), list), strings.map(this::makeString));
	}
	JCExpression makeList(List<JCExpression> trees) {
		Symbol list = syms.arraysType.tsym.members().findFirst(names.fromString("asList"));
		// code: Arrays.asList(trees)
		return make.App(make.Select(make.Ident(syms.arraysType.tsym), list), trees);
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
	JCClassDecl       classDecl;
	public void translateTopLevelClass(JCCompilationUnit toplevel, JCTree tree) {
		if (DISABLED) return;
		/* if (tree instanceof JCClassDecl classDecl && classDecl.name.contentEquals("HopeString")) {
			println(tree);
		} */
		try {
			this.toplevel = toplevel;
			translate(tree);
		} catch (Throwable e) {
			err(e);
		} finally {
			this.classDecl = null;
			this.toplevel = null;
		}
	}
}
