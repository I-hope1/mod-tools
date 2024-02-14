package modtools.annotations.unsafe;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.comp.MemberEnter;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;

import static modtools.annotations.BaseProcessor.names;

public class MyMemberEnter extends MemberEnter {
	public MyMemberEnter(Context context) {
		super(context);
	}
	public void visitClassDef(JCClassDecl that) {
		if (env.enclClass.sym.isEnum()
				&& env.enclClass.extending != null) {
			env.enclClass.sym.getSuperclass().tsym.flags_field &= ~Flags.FINAL;
		}
		super.visitClassDef(that);
	}
	public void visitMethodDef(JCMethodDecl tree) {
		boolean lastEnum = false;
		long lastFlags = 0;
		if (env.enclClass.sym.isEnum()
				&& tree.name == names.init && env.enclClass.extending != null) {
			lastEnum = true;
			lastFlags = tree.mods.flags;
			tree.mods.flags = 0;
			/* 去除final  */
			env.enclClass.sym.getSuperclass().tsym.flags_field &= ~Flags.FINAL;
			// println("@: @", tree, env.enclClass);
		}
		super.visitMethodDef(tree);
		if (lastEnum) {
			tree.mods.flags = lastFlags;
		}
	}
}
