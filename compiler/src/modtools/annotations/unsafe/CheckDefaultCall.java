package modtools.annotations.unsafe;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.*;

public class CheckDefaultCall extends TreeScanner {
	final JavacTrees trees;
	final Log        log;
	final String     errorKey = "default.method.call";
	public CheckDefaultCall(Context context) {
		trees = JavacTrees.instance(context);
		log = Log.instance(context);
		Replace.bundles.put("compiler.err." + errorKey, "Default method call: {0}#{1}");
	}
	JCCompilationUnit toplevel;
	public void scanToplevel(JCCompilationUnit toplevel) {
		this.toplevel = toplevel;
		// scan(toplevel);
	}
	public void visitApply(JCMethodInvocation tree) {
		// if (tree.meth.type != null && tree.meth.type.tsym.owner.isInterface())
		MethodSymbol method = (MethodSymbol) trees.getElement(trees.getPath(toplevel, tree));
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
		super.visitApply(tree);
	}

	public static class CheckException extends RuntimeException {
		public CheckException(String message) {
			super(message);
		}
	}
}
