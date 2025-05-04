package modtools.annotations.unsafe;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

import static modtools.annotations.PrintHelper.SPrinter.println;

/** 这个还未完全适配所有的 */
public class DefaultToStatic extends TreeTranslator {
	public static final String NAME_PREFIX = "$default$";

	final        Symtab            syms;
	final        TreeMaker         make;
	final        Names             names;
	final        Enter             enter;
	public final TypeEnter         typeEnter;
	public final JavacTrees        trees;
	public       JCCompilationUnit toplevel;
	public       JCClassDecl       classDecl;

	public DefaultToStatic(Context context) {
		syms = Symtab.instance(context);
		make = TreeMaker.instance(context);
		names = Names.instance(context);
		enter = Enter.instance(context);
		typeEnter = TypeEnter.instance(context);
		trees = JavacTrees.instance(context);
	}


	ListBuffer<JCTree> toAppendMethods = new ListBuffer<>();
	JCMethodDecl       genMethod;
	JCVariableDecl     self;
	public void visitMethodDef(JCMethodDecl methodDecl) {
		if ((methodDecl.mods.flags & Flags.DEFAULT) == 0) {
			result = methodDecl;
			return;
		}
		// println(Flags.toString(tree.mods.flags) + "." + tree.sym);
		class LambdaScanner extends TreeScanner {
			boolean hasLambda, hasCaptured;
			boolean    inLambda;
			List<Name> blackList = classDecl.defs.map(p ->
			 p instanceof JCVariableDecl v ? v.name :
				p instanceof JCClassDecl c ? c.name :
				 p instanceof JCMethodDecl m ? m.name : null);
			public void visitLambda(JCLambda tree) {
				hasLambda = true;

				inLambda = true;
				List<Name> prev = blackList;
				blackList = blackList.appendList(tree.params.map(p -> p.name));
				if (!hasCaptured) super.visitLambda(tree);
				blackList = prev;
				inLambda = false;
			}

			public void visitIdent(JCIdent tree) {
				if (inLambda
				    && blackList.stream().noneMatch(p -> p != null && p.contentEquals(tree.name))
				    && !hasImport(tree.name)) {
					hasCaptured = true;
				}
			}
		}
		LambdaScanner scanner = new LambdaScanner();
		methodDecl.body.accept(scanner);
		if (scanner.hasLambda && scanner.hasCaptured) {
			MethodSymbol enclMethod = methodDecl.sym;
			self = make.Param(names.fromString("default$this"), enclMethod.owner.type, enclMethod);
			make.at(methodDecl);
			// methodDecl.params.forEach(p -> p.sym = null);
			genMethod = make.MethodDef(make.Modifiers(Flags.STATIC | Flags.PUBLIC),
			 names.fromString(NAME_PREFIX + methodDecl.name), methodDecl.restype,
			 methodDecl.typarams,
			 methodDecl.recvparam,
			 new TreeCopier<>(make).copy(methodDecl.params).prepend(self),
			 methodDecl.thrown,
			 null,
			 methodDecl.defaultValue);
			// genMethod.params = methodDecl.params.prepend(self);
			genMethod.body = translate(methodDecl.body);
			// genMethod.accept(enter);
			// println(genMethod);
			JCMethodInvocation apply = make.Apply(
			 List.nil(), make.Ident(genMethod.name),
			 methodDecl.params.<JCExpression>map(param -> make.Ident(param.name))
				.prepend(make.QualThis(enclMethod.owner.type))
			);

			// apply.type = ms.type;
			methodDecl.body = make.Block(0, List.of(enclMethod.getReturnType() == syms.voidType ? make.Exec(apply)
			 : make.Return(apply)));
			println(genMethod);
			// methodDecl.body.accept(enter);
			println(methodDecl);

			toAppendMethods.add(genMethod);
		}

		genMethod = null;
		self = null;
		result = methodDecl;
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
		    !hasImport(i.name) && self != null) {
			make.at(i);
			invocation.meth = make.Select(make.Ident(self), i.name);
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
	private boolean hasImport(Name name) {
		return toplevel.namedImportScope.findFirst(name) != null
		       || toplevel.starImportScope.findFirst(name) != null;
	}
	public void translateTopLevelClass(JCCompilationUnit unit, JCTree cdef) {
		if (!(cdef instanceof JCClassDecl dcl && (dcl.mods.flags & Flags.INTERFACE) != 0)) {
			return;
		}
		try {
			toplevel = unit;
			this.classDecl = dcl;
			translate(dcl);
			dcl.defs = dcl.defs.appendList(toAppendMethods);
		} finally {
			toAppendMethods.clear();
			this.classDecl = null;
			toplevel = null;
		}
	}
}