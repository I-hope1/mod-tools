package modtools.annotations.unsafe;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.util.Context;
import modtools.annotations.NoAccessCheck;

public class MyResolve extends Resolve {
	protected MyResolve(Context context) {
		super(context);
	}
	public boolean isAccessible(Env<AttrContext> env, Type site, Symbol sym, boolean checkInner) {
		if (!sym.owner.isAbstract() && !sym.isInner() && !sym.isAnonymous()
				&& (sym.flags_field & Flags.PARAMETER) == 0 &&
				env.enclClass.sym.getAnnotation(NoAccessCheck.class) != null) {
			sym.flags_field |= Flags.PUBLIC;
			sym.flags_field &= ~(Flags.PRIVATE | Flags.PROTECTED);
		}
		return super.isAccessible(env, site, sym, checkInner);
	}
}
