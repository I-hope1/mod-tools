package modtools.annotations.unsafe;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.util.*;
import modtools.annotations.NoAccessCheck;

import java.util.function.BiPredicate;

@NoAccessCheck
public class MyResolve extends Resolve {
	BiPredicate<Env<AttrContext>, Symbol> validator;
	public MyResolve(Context context, BiPredicate<Env<AttrContext>, Symbol> validator) {
		super(context);
		this.validator = validator;
		// syms = Symtab.instance(context);
		// moduleFinder = ModuleFinder.instance(context);
		// names = Names.instance(context);
	}
	public boolean isAccessible(Env<AttrContext> env, Type site, Symbol sym, boolean checkInner) {
		if (!sym.owner.isAbstract() && !sym.isInner() && !sym.isAnonymous()
				&& (sym.flags_field & Flags.PARAMETER) == 0 &&
				validator.test(env, sym)) {
			sym.flags_field |= Flags.PUBLIC;
			sym.flags_field &= ~(Flags.PRIVATE | Flags.PROTECTED);
		}
		return super.isAccessible(env, site, sym, checkInner);
	}

	// Symtab       syms;
	// ModuleFinder moduleFinder;
	// Names        names;
}
