package modtools.annotations;

import arc.util.Log;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;

import javax.lang.model.element.Element;
import java.util.regex.Pattern;

import static modtools.annotations.BaseProcessor.*;

@SuppressWarnings("UnusedReturnValue")
public interface TreeUtils extends ParseUtils {
	/**
	 * 检测字段是否存在
	 *
	 * @return {@code true} if field is present.
	 */
	default boolean checkField(
	 JCClassDecl classDecl, String fieldName, String group
	) {
		JCVariableDecl fieldElem = findChild(classDecl, Tag.VARDEF, var -> var.name.toString().equals(fieldName));
		if (fieldElem == null) {
			Log.err(new IllegalStateException("You don't mark class (" + classDecl.getSimpleName() + ") or mark it group (" + group + ")." + "\n(debug)Needed (" + fieldName + ") field."));
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
	default JCVariableDecl makeVar(long flags, Type type, String name, String init, Symbol sym) {
		return makeVar0(flags, type, name, init == null ? null : parseExpression(init), sym);
	}
	Pattern pattern = Pattern.compile("^[0-9a-zA-Z\\-$_ ]+$");
	default JCVariableDecl makeVar0(long flags, Type type, String name, JCExpression init, Symbol sym) {
		return makeVar1(flags, type, name, init, sym, true);
	}
	private JCVariableDecl makeVar1(long flags, Type type, String name, JCExpression init, Symbol sym, boolean enterScope) {
		if (!pattern.matcher(name).find())
			throw new IllegalArgumentException("Name(" + name + ") contains illegal char.");
		VarSymbol varSymbol = new VarSymbol(
		 flags, names.fromString(name), type, sym
		);
		if (enterScope && sym instanceof ClassSymbol csm) {
			csm.members_field.enter(varSymbol);
		}
		return mMaker.VarDef(varSymbol, init);
	}
	default JCVariableDecl addConstantField(JCClassDecl classDecl, Type type, String name, Object value) {
		JCVariableDecl x = makeVar0(Flags.STATIC | Flags.FINAL | Flags.HASINIT,
		 type, name, mMaker.Literal(value), classDecl.sym);
		classDecl.defs = classDecl.defs.prepend(x);
		return x;
	}


	default ClassSymbol findClassByImport(Element dcls, String name) {
		JCClassDecl classDecl = (JCClassDecl) trees.getTree(dcls);
		if (name.equals(dcls.getSimpleName().toString())) {
			return classDecl.sym;
		}

		var imports = (java.util.List<JCImport>) trees.getPath(dcls).getCompilationUnit().getImports();

		ClassSymbol cl;
		for (JCImport i : imports) {
			JCFieldAccess qualid = (JCFieldAccess) i.qualid;
			if (qualid.name.contentEquals("*") && (cl = (ClassSymbol) findClassSymbol(qualid.name + "." + name)) != null) {
				return cl;
			} else if (!qualid.name.isEmpty() && (cl = (ClassSymbol) findClassSymbol(qualid.selected + "." + name)) != null) {
				return cl;
			}
		}
		for (JCTree t : classDecl.defs) {
			if (t instanceof JCClassDecl c) {
				if (c.sym.name.contentEquals(name)) {
					return c.sym;
				}
			}
		}

		return null;
	}

	default Type findTypeBoot(String name) {
		return mSymtab.getClass(mSymtab.java_base, names.fromString(name)).type;
	}
	default ClassType findType(String name) {
		return (ClassType) mSymtab.getClass(mSymtab.unnamedModule, names.fromString(name)).type;
	}
	default ClassSymbol findClassSymbol(String name) {
		return mSymtab.getClass(mSymtab.unnamedModule, names.fromString(name));
	}
}
