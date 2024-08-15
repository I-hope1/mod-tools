package modtools.annotations.unsafe;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

public class DefaultToStatic extends TreeTranslator {
	final Symtab syms;
	final TreeMaker make;
	final Names     names;
	final Enter enter;
	public final TypeEnter typeEnter;
	public final JavacTrees trees;
	public JCCompilationUnit toplevel;

	public DefaultToStatic(Context context) {
		syms = Symtab.instance(context);
		make = TreeMaker.instance(context);
		names = Names.instance(context);
		enter = Enter.instance(context);
		typeEnter = TypeEnter.instance(context);
		trees = JavacTrees.instance(context);
	}


	ListBuffer<JCTree> toAppendMethods = new ListBuffer<>();
	boolean            hasLambda;
	JCMethodDecl       genMethod;
	JCVariableDecl     self;
	public void visitMethodDef(JCMethodDecl tree) {
		if ((tree.mods.flags & Flags.DEFAULT) == 0) {
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
			VarSymbol    varSymbol  = new VarSymbol(Flags.PARAMETER, names.fromString("default$this"), enclMethod.owner.type, enclMethod);
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
			 List.nil(), make.Ident(genMethod.name),
			 tree.params.map(make::Ident).prepend(make.This(enclMethod.owner.type))
			);

			// apply.type = ms.type;
			tree.body = make.Block(0, List.of(enclMethod.getReturnType() == syms.voidType ? make.Exec(apply)
			 : make.Return(apply)));
			// println(tree);
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
			translate(cdef);
		} finally {
			((JCClassDecl) cdef).defs = ((JCClassDecl) cdef).defs.appendList(toAppendMethods);
			toAppendMethods.clear();
			hasLambda = false;
			toplevel = null;
		}
	}
}