package modtools.annotations.unsafe;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

import java.util.*;
import java.util.function.*;

import static com.sun.tools.javac.code.Kinds.Kind.ERR;
import static com.sun.tools.javac.tree.JCTree.Tag.*;

public class MyResolve extends Resolve {
	BiPredicate<Env<AttrContext>, Symbol> validator;
	public MyResolve(Context context, BiPredicate<Env<AttrContext>, Symbol> validator) {
		super(context);
		this.validator = validator;
		syms = Symtab.instance(context);
		moduleFinder = ModuleFinder.instance(context);
		names = Names.instance(context);
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

	Symtab       syms;
	ModuleFinder moduleFinder;
	Names        names;

	/** @see Resolve#lookupPackage(Env, Name)  */
	Symbol lookupPackage(Env<AttrContext> env, Name name) {
		PackageSymbol pack = syms.lookupPackage(env.toplevel.modle, name);

		if (allowModules && isImportOnDemand(env, name)) {
			if (pack.members().isEmpty()) {
				return lookupInvisibleSymbol(env, name, syms::getPackagesForName, syms::enterPackage, sym -> {
					sym.complete();
					return !sym.members().isEmpty();
				}, pack);
			}
		}

		return pack;
	}

	public <S extends Symbol> Symbol lookupInvisibleSymbol(
	 Env<AttrContext> env,
	 Name name,
	 Function<Name, Iterable<S>> get,
	 BiFunction<ModuleSymbol, Name, S> load,
	 Predicate<S> validate,
	 Symbol defaultResult) {
		//even if a class/package cannot be found in the current module and among packages in modules
		//it depends on that are exported for any or this module, the class/package may exist internally
		//in some of these modules, or may exist in a module on which this module does not depend.
		//Provide better diagnostic in such cases by looking for the class in any module:
		Iterable<? extends S> candidates = get.apply(name);

		for (S sym : candidates) {
			if (validate.test(sym))
				return sym;
		}

		Set<ModuleSymbol> recoverableModules = new HashSet<>(syms.getAllModules());

		recoverableModules.add(syms.unnamedModule);
		recoverableModules.remove(env.toplevel.modle);

		for (ModuleSymbol ms : recoverableModules) {
			//avoid overly eager completing classes from source-based modules, as those
			//may not be completable with the current compiler settings:
			if (ms.sourceLocation == null) {
				if (ms.classLocation == null) {
					ms = moduleFinder.findModule(ms);
				}

				if (ms.kind != ERR) {
					S sym = load.apply(ms, name);

					if (sym != null && validate.test(sym)) {
						return sym;
					}
				}
			}
		}

		return defaultResult;
	}
	boolean isImportOnDemand(Env<AttrContext> env, Name name) {
		if (!env.tree.hasTag(IMPORT))
			return false;

		JCTree qualid = ((JCImport) env.tree).qualid;

		if (!qualid.hasTag(SELECT))
			return false;

		if (TreeInfo.name(qualid) != names.asterisk)
			return false;

		return TreeInfo.fullName(((JCFieldAccess) qualid).selected) == name;
	}
}
