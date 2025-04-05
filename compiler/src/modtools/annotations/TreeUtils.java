package modtools.annotations;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.comp.Modules;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static modtools.annotations.BaseProcessor.*;

@SuppressWarnings("UnusedReturnValue")
public interface TreeUtils extends ParseUtils, NameString {
	/**
	 * 检测字段是否存在
	 *
	 * @return {@code true} if element is present.
	 */
	default boolean checkField(
	 JCClassDecl classDecl, String fieldName, String group
	) {
		JCVariableDecl fieldElem = findChild(classDecl, Tag.VARDEF, var -> var.name.toString().equals(fieldName));
		if (fieldElem == null) {
			new IllegalStateException("You don't mark class (" + classDecl.getSimpleName() + ") or mark it group (" + group + ")." + "\n(debug)Needed (" + fieldName + ") element.")
			 .printStackTrace();
			return false;
		}
		return true;
	}
	default JCVariableDecl addField(JCClassDecl classDecl, int flags, Type type, String name, String init) {
		JCVariableDecl x = makeVar(flags, type, name, init, classDecl.sym);
		x.pos = 0;
		classDecl.defs = classDecl.defs.prepend(x);
		return x;
	}
	default JCVariableDecl addField0(JCClassDecl classDecl, int flags, Type type, String name, JCExpression init) {
		JCVariableDecl x = makeVar0(flags, type, name, init, classDecl.sym);
		x.pos = 0;
		classDecl.defs = classDecl.defs.prepend(x);
		return x;
	}
	default JCVariableDecl makeVar(long flags, Type type, String name, String init, Symbol owner) {
		return makeVar0(flags, type, name, init == null ? null : parseExpression(init), owner);
	}
	Pattern pattern = Pattern.compile("^[0-9a-zA-Z\\-$_ ]+?$");
	default JCVariableDecl makeVar0(long flags, Type type, String name, JCExpression init, Symbol owner) {
		return makeVar1(flags, type, name, init, owner, true);
	}
	private JCVariableDecl makeVar1(long flags, Type type, String name, JCExpression init, Symbol owner,
	                                boolean enterScope) {
		if (!pattern.matcher(name).find())
			throw new IllegalArgumentException("Name(" + name + ") contains illegal char.");
		VarSymbol varSymbol = new VarSymbol(
		 flags, ns(name), type, owner
		);
		if (enterScope && owner instanceof ClassSymbol csm) {
			csm.members_field.enter(varSymbol);
		}
		return mMaker.VarDef(varSymbol, init);
	}
	default JCVariableDecl addConstantField(JCClassDecl classDecl, Type type, String name, Object value) {
		return addConstantField(0, classDecl, type, name, value);
	}

	default JCVariableDecl addConstantField(long flags, JCClassDecl classDecl, Type type, String name, Object value) {
		JCVariableDecl x = makeVar0(flags | Flags.STATIC | Flags.FINAL | Flags.HASINIT,
		 type, name, value instanceof JCExpression ex ? ex : mMaker.Literal(value), classDecl.sym);
		classDecl.defs = classDecl.defs.prepend(x);
		return x;
	}

	default Type findTypeBoot(String name) {
		return mSymtab.getClass(mSymtab.java_base, ns(name)).type;
	}
	default ClassType findType(String name) {
		return (ClassType) mSymtab.getClass(mSymtab.unnamedModule, ns(name)).type;
	}
	default ClassSymbol findClassSymbolByBoot(String name) {
		return mSymtab.getClass(mSymtab.java_base, ns(name));
	}

	default ClassSymbol findClassSymbol(String name) {
		return mSymtab.getClass(mSymtab.unnamedModule, ns(name));
	}
	default ClassSymbol findClassSymbolAny(String name) {
		for (ModuleSymbol module : Modules.instance(_context).allModules()) {
			ClassSymbol sym = mSymtab.getClass(module, ns(name));
			if (sym != null && sym.exists()) return sym;
		}
		return null;
	}

	default void addImport(Element element, ClassType classType) {
		addImport(element, classType.tsym);
	}

	default void addImport(Element element, TypeSymbol sym) {
		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(element).getCompilationUnit();
		addImport(unit, sym);
	}
	default void addImport(JCCompilationUnit unit, TypeSymbol sym) {
		if (sym == null) throw new NullPointerException("sym is null.");
		if (!unit.namedImportScope.includes(sym) && !unit.starImportScope.includes(sym)) {
			/* unit.namedImportScope.importType(
			 SettingUI().members(), SettingUI().members(), SettingUI()
			); */
			unit.namedImportScope.importType(sym.owner.members(), sym.owner.members(), sym);

			var list = new ArrayList<>(unit.defs);
			list.add(1, mMaker.Import((JCFieldAccess) mMaker.QualIdent(sym), false));
			unit.defs = List.from(list);
		}
	}
}
