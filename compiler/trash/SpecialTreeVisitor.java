package modtools.annotations.unsafe;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;

import static com.sun.tools.javac.code.Flags.ENUM;

public class SpecialTreeVisitor extends JCTree.Visitor {
	boolean specialized;
	public SpecialTreeVisitor() {
		this.specialized = false;
	}

	@Override
	public void visitTree(JCTree tree) { /* no-op */ }

	@Override
	public void visitVarDef(JCVariableDecl tree) {
		if ((tree.mods.flags & ENUM) != 0) {
			if (tree.init instanceof JCNewClass newClass && newClass.def != null) {
				specialized = true;
			}
		}
	}
}
