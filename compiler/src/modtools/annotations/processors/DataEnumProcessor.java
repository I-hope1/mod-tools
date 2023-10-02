package modtools.annotations.processors;

import arc.util.Strings;
import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.io.Writer;
import java.util.Set;

@AutoService({Processor.class})
public class DataEnumProcessor extends BaseProcessor<TypeElement> {
	Type dataType, mySettingType;
	public void init() throws Throwable {
		dataType = findType("modtools.utils.MySettings$Data");
		mySettingType = findType("modtools.utils.MySettings");
	}
	public JCModifiers lastMods;
	public void dealElement(TypeElement element) throws Throwable {
		JCClassDecl clazz = trees.getTree(element);
		lastMods = clazz.mods;
		clazz.mods = mMaker.Modifiers(Flags.PUBLIC | Flags.ENUM);
		String str_name = clazz.name.toString();
		if (!str_name.startsWith("E_"))
			throw new IllegalStateException("clazz (" + clazz.name + ") must start with 'E_'.");
		if (!str_name.endsWith("Comp"))
			throw new IllegalStateException("clazz (" + clazz.name + ") must end with 'Comp'.");

		if (!Flags.isEnum(clazz.sym)) throw new IllegalStateException("clazz(" + clazz.name + ") must be enum.");
		DataEnum dataEnum     = getAnnotationByElement(DataEnum.class, element, false);
		String   newClassName = str_name.substring(0, str_name.length() - 4/* comp */);
		clazz.name = names.fromString(newClassName);
		String newMainName = newClassName.substring(2/* E_ */);

		checkAndAddedData(element, clazz, newMainName);
		findAllChild(clazz, Tag.METHODDEF, (JCMethodDecl m) -> m.name.equals(names.init))
		 .each(t -> {
			 class Translator extends TreeTranslator {
				 public void visitApply(JCMethodInvocation tree) {
					 if (((JCIdent) tree.meth).name == names._super) result = null;
					 else super.visitApply(tree);
				 }
			 }
			 t.accept( new Translator());
		 });
		checkAndAddedEnabled(element, clazz);
		checkAndAddedDef(element, clazz);
		checkAndAddedSet(element, clazz);

		var    unit       = trees.getPath(element).getCompilationUnit();
		var    sourceFile = mFiler.createSourceFile(unit.getPackageName() + "." + newClassName);
		Writer writer     = sourceFile.openWriter();
		writer.write(unit.toString());
		writer.flush();
		writer.close();

		clazz.defs = List.nil();
		clazz.name = names.fromString("Hidden" + newClassName);
		clazz.mods = lastMods;
	}
	private void checkAndAddedSet(TypeElement element, JCClassDecl clazz) {
		if (findChild(element, "set", ElementKind.METHOD) != null) return;
		MethodSymbol symbol = new MethodSymbol(Flags.PUBLIC | Flags.FINAL, names.fromString("def"), mSymtab.voidType, clazz.sym);
		mMaker.at(clazz.defs.head);
		JCVariableDecl value = makeVar(Flags.PARAMETER, mSymtab.booleanType, "value", null, symbol);
		JCMethodDecl methodDef = mMaker.MethodDef(
		 mMaker.Modifiers(symbol.flags_field),
		 names.fromString("set"),
		 mMaker.Type(mSymtab.voidType), List.nil(), null, List.of(value),
		 List.nil(), parseBlock("{data.put(name(), value);}"), null);
		clazz.defs = clazz.defs.append(methodDef);
		methodDef.sym = symbol;
	}
	private void checkAndAddedDef(TypeElement element, JCClassDecl clazz) {
		if (findChild(element, "def", ElementKind.METHOD) != null) return;
		MethodSymbol symbol = new MethodSymbol(Flags.PUBLIC | Flags.FINAL, names.fromString("def"), mSymtab.voidType, clazz.sym);
		mMaker.at(clazz.defs.stream().mapToInt(t -> t.pos).sum());
		JCVariableDecl value = makeVar(Flags.PARAMETER, mSymtab.booleanType, "value", null, symbol);
		JCMethodDecl methodDef = mMaker.MethodDef(
		 mMaker.Modifiers(symbol.flags_field),
		 names.fromString("def"),
		 mMaker.Type(mSymtab.voidType), List.nil(), null, List.of(value),
		 List.nil(), parseBlock("{data.getBool(name(), value);}"), null);
		clazz.defs = clazz.defs.append(methodDef);
		methodDef.sym = symbol;
	}
	private void checkAndAddedEnabled(TypeElement element, JCClassDecl clazz) {
		if (findChild(element, "enabled", ElementKind.METHOD) != null) return;
		MethodSymbol symbol = new MethodSymbol(Flags.PUBLIC | Flags.FINAL, names.fromString("enabled"), mSymtab.voidType, clazz.sym);
		JCMethodDecl methodDef = mMaker.MethodDef(
		 mMaker.Modifiers(symbol.flags_field),
		 names.fromString("enabled"),
		 mMaker.Type(mSymtab.booleanType), List.nil(), null, List.nil(),
		 List.nil(), parseBlock("{return data.getBool(name());}"), null);
		methodDef.sym = symbol;
		clazz.defs = clazz.defs.append(methodDef);
	}
	private void checkAndAddedData(TypeElement element, JCClassDecl clazz, String newMainName) {
		if (findChild(element, "data", ElementKind.FIELD) != null) return;
		String replace = Strings.insertSpaces(newMainName)
		 .replace(' ', '_');
		addField(clazz, Flags.STATIC | Flags.PUBLIC | Flags.FINAL,
		 dataType, "data",
		 mySettingType + ".D_" + replace.replaceAll("([A-Z])_([A-Z])_", "$1$2")
			.toUpperCase()
		);
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(DataEnum.class.getCanonicalName());
	}
}
