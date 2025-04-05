package modtools.annotations.unsafe;

import com.sun.tools.javac.comp.TransTypes;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;

public class MyTransTypes extends TransTypes {
	public MyTransTypes(Context context) { super(context); }

	JCCompilationUnit toplevel;
	public void visitTopLevel(JCCompilationUnit tree) {
		toplevel = tree;
		super.visitTopLevel(tree);
		toplevel = null;
	}
	public void visitApply(JCMethodInvocation tree) {
		super.visitApply(tree);
		// println(Replace.trees.getElement(Replace.trees.getPath(toplevel, tree)));
	}
}
