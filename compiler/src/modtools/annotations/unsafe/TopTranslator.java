package modtools.annotations.unsafe;

import com.sun.source.doctree.*;
import com.sun.source.util.DocTreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.DCTree.DCReference;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

import static modtools.annotations.processors.ContentProcessor.REF_PREFIX;

public class TopTranslator extends TreeTranslator {
	final JavacTrees trees;
	final Log        log;
	final Names      names;
	final Symtab     symtab;
	final TreeMaker  maker;

	final String errorKey = "default.method.call";
	public TopTranslator(Context context) {
		trees = JavacTrees.instance(context);
		log = Log.instance(context);
		names = Names.instance(context);
		symtab = Symtab.instance(context);
		maker = TreeMaker.instance(context);

		Replace.bundles.put("compiler.err." + errorKey, "Default method call: {0}#{1}");
	}
	JCCompilationUnit toplevel;
	public void scanToplevel(JCCompilationUnit toplevel) {
		this.toplevel = toplevel;
		translate(toplevel);
	}
	public void visitApply(JCMethodInvocation tree) {
		// if (tree.meth.type != null && tree.meth.type.tsym.owner.isInterface())
		// checkDefault(tree);
		super.visitApply(tree);
	}
	boolean inAssign = false;
	public void visitAssign(JCAssign tree) {
		inAssign = true;
		super.visitAssign(tree);
		inAssign = false;
		x_translateAssign(tree);
	}
	public void visitSelect(JCFieldAccess tree) {
		super.visitSelect(tree);
		if (!inAssign) x_translateFieldAccess(tree);
	}
	public void x_translateFieldAccess(JCFieldAccess access) {
		if (!(access.selected instanceof JCIdent i) || !i.name.toString().startsWith(REF_PREFIX)) {
			return;
		}
		String         enumName  = access.name.toString();
		ClassSymbol    symbol    = getEventClassSymbol(i);
		if (symbol == null) return;
		// R_XXX -> E_XXX.xxx.get%Type%()
		JCFieldAccess enumField = makeSelect(maker.QualIdent(symbol), names.fromString(enumName), symbol);
		String        s         = "" + access.type.tsym.getSimpleName();
		String getter = switch (s) {
			case "boolean" -> "enabled";
			default -> access.type.tsym.isEnum() ? "getEnum" : "get" + s.substring(0, 1).toUpperCase() + s.substring(1);
		};
		JCFieldAccess fn = makeSelect(enumField,
		 names.fromString(getter), iSettings());
		MethodSymbol ms = ((MethodSymbol) fn.sym);
		result = maker.Apply(List.nil(), fn, ms.params.isEmpty() ? List.nil() : List.of(
		 maker.ClassLiteral(access.type))).setType(access.type);
		// println(result);
	}
	private void x_translateAssign(JCAssign tree) {
		if (!(tree.lhs instanceof JCFieldAccess access) || !(access.selected instanceof JCIdent i) || !i.name.toString().startsWith(REF_PREFIX)) {
			return;
		}
		// R_XXX.xxx(lhs) = val(rhs) -> E_XXX.xxx.set%Type%(val)
		ClassSymbol symbol = getEventClassSymbol(i);
		// println(symbol.fullname);
		JCFieldAccess enumField = makeSelect(maker.QualIdent(symbol), access.name, symbol);
		JCFieldAccess fn        = makeSelect(enumField, names.fromString("set"), iSettings());
		result = maker.Apply(List.nil(), fn, List.of(tree.rhs)).setType(tree.type);
		// println(result);
	}

	private TypeSymbol $settings;
	private TypeSymbol iSettings() {
		return $settings != null ? $settings : ($settings = symtab.enterClass(symtab.unnamedModule, names.fromString("modtools.events.ISettings")));
	}
	public JCFieldAccess makeSelect(JCExpression selected, Name name, TypeSymbol owner) {
		JCFieldAccess select = maker.Select(selected, name);
		Symbol        sym    = owner.members().findFirst(name);
		if (sym == null) {
			log.useSource(toplevel.sourcefile);
			log.error("cant.resolve.location", names.fromString("modtools.events.ISettings"), name, List.nil(), owner);
			throw new CheckException("");
		}
		select.sym = sym;
		select.type = select.sym.type;
		return select;
	}

	private ClassSymbol getEventClassSymbol(JCIdent i) {
		DocCommentTree doc      = trees.getDocCommentTree(i.sym);
		SeeTree        seeTag    = (SeeTree) doc.getBlockTags().stream().filter(t -> t instanceof SeeTree).findFirst().orElseThrow();
		if (!(seeTag.getReference().get(0) instanceof DCReference reference)) { return null; }

		return (ClassSymbol) trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(i.sym), doc), reference));
	}

	private void checkDefault(JCMethodInvocation tree) {
		// 检查没有实现默认方法却调用了默认方法
		MethodSymbol method = (MethodSymbol) getSymbol(tree);
		l:
		if (method.isDefault() && method.owner.isInterface() /* && ((ClassSymbol) method.owner).sourcefile == null */) {
			ClassSymbol owner = (ClassSymbol) method.owner;
			if (owner.classfile == owner.sourcefile) break l;
			// DocCommentTree doc = trees.getDocCommentTree(method);
			// if (doc == null) break l;
			// SinceTree since = (SinceTree) doc.getBlockTags().stream().filter(t -> t.getKind() == Kind.SINCE).findFirst().orElse(null);
			// if (since == null || Source.lookup(since.getBody().toString()).compareTo(Source.JDK8) < 0) break l;
			// DocTreePath path = treesDocTreePath.getPath(trees.getPath(method), doc, since);/
			log.useSource(toplevel.sourcefile);
			log.error(tree.pos, errorKey, method.owner.name, method);
			throw new CheckException(errorKey);
		}
	}
	private Symbol getSymbol(JCTree tree) {
		return trees.getElement(trees.getPath(toplevel, tree));
	}

	public static class CheckException extends RuntimeException {
		public CheckException(String message) {
			super(message);
		}
	}
}
