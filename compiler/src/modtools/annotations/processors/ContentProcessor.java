package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.*;

/** 添加new XXX()，并给对应Content的Settings（如果有）初始化  */
@AutoService({Processor.class})
// Inside the class:
public class ContentProcessor extends BaseProcessor<ClassSymbol>
 implements DataUtils {
	private Name        nameSetting;
	private ClassSymbol dataClass, mySettingsClass;

	public void init() throws Throwable {
		nameSetting = ns("Settings");
		mySettingsClass = C_MySettings();
		dataClass = C_Data();
	}

	public void contentLoad(ClassSymbol element) {
		JCMethodDecl            loadMethod = trees.getTree((ExecutableElement) findChild(element, "load", ElementKind.METHOD));
		ListBuffer<JCStatement> statements = new ListBuffer<>();

		for (Element field : findAllChild(element, null, ElementKind.FIELD)) {
			JCVariableDecl variableTree = (JCVariableDecl) trees.getTree(field);
			statements.add(
			 mMaker.Exec(mMaker.Assign(
				mMaker.Ident((Name) field.getSimpleName()),
				mMaker.NewClass(null, List.nil(), variableTree.vartype, List.nil(), null)
			 )));

			TypeSymbol   contentTypeSymbol = variableTree.vartype.type.tsym;
			JCMethodDecl initMethod        = (JCMethodDecl) trees.getTree(contentTypeSymbol.members().findFirst(names.init));
			Object       literalName       = ((JCLiteral) ((JCMethodInvocation) ((JCExpressionStatement) initMethod.body.stats.get(0)).expr).args.get(0)).value;

			ClassSymbol settingsSymbol = (ClassSymbol) contentTypeSymbol.members().findFirst(nameSetting, t -> t.kind == Kind.TYP);
			if (settingsSymbol != null) {
				JCClassDecl settingsTree = trees.getTree(settingsSymbol);
				processSetting(settingsSymbol, settingsTree, literalName, "");
			}
		}

		loadMethod.body.stats = statements.toList();
		// print(loadMethod);
	}

	public void dealElement(ClassSymbol element) {
		if (element.getAnnotation(ContentInit.class) != null) {
			contentLoad(element);
			// print(element);
		} else if (element.getAnnotation(SettingsInit.class) != null) {
			SettingsInit annotation = element.getAnnotation(SettingsInit.class);
			String       value      = annotation.value();
			if (value.equals(".")) {
				value = element.getSimpleName().toString();
				if (value.startsWith("E_")) value = value.substring(2);
			}
			processSetting(element, trees.getTree(element), value, annotation.parent());
		}
	}

	private void processSetting(ClassSymbol settings, JCClassDecl settingsTree, Object literalName, String parent) {
		addImport(settings, dataClass);
		addImport(settings, mSymtab.objectType.tsym);

		addMethod(settingsTree, "data", dataClass.type);
		if (parent.isEmpty()) addImport(settings, mySettingsClass);

		JCMethodInvocation initValue = createInitValue(parent, literalName);

		if (settings.members().findFirst(ns("data"), t -> t.kind == Kind.VAR) == null)
			addConstantField(Flags.PUBLIC, settingsTree, dataClass.type, "data", initValue);

		java.util.List<JCMethodDecl> allInit = settingsTree.defs.stream()
		 .filter(t -> t instanceof JCMethodDecl)
		 .map(t -> (JCMethodDecl) t)
		 .filter(m1 -> m1.name.equals(names.init)).toList();

		allInit.stream().filter(m1 -> !m1.params.isEmpty()).forEach(init -> {
			addMethod(settingsTree, "type", mSymtab.classType);
			addField(settingsTree, mSymtab.classType, "type", mMaker.ClassLiteral(mSymtab.booleanType));

			init.body.stats = init.body.stats.prepend(
			 mMaker.Exec(mMaker.Assign(mMaker.Select(mMaker.This(settings.type), ns("type")), mMaker.Ident(init.params.get(0).sym)))
			);
		});
		allInit.stream().filter(m1 -> m1.params.size() == 2).forEach(init -> {
			addMethod(settingsTree, "args", mSymtab.objectType);
			addField(settingsTree, mSymtab.objectType, "args", null);

			init.body.stats = init.body.stats.prepend(
			 mMaker.Exec(mMaker.Assign(mMaker.Select(mMaker.This(settings.type), ns("args")), mMaker.Ident(init.params.get(1).sym)))
			);
		});
	}

	private JCMethodInvocation createInitValue(String parent, Object literalName) {
		JCMethodInvocation initValue;
		if (parent.isEmpty()) {
			initValue = mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Select(mMaker.Ident(mySettingsClass),
				ns("SETTINGS")), ns("child")),
			 List.of(mMaker.Literal(literalName)));
		} else {
			initValue = mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Select(mMaker.Ident(ns(parent)),
				ns("data")), ns("child")),
			 List.of(mMaker.Literal(literalName)));
		}
		return initValue;
	}

	private void addMethod(JCClassDecl settingsTree, String fieldName, Type fieldType) {
		if (findChild(settingsTree,Tag.METHODDEF, (JCMethodDecl t) -> t.name.contentEquals(fieldName)) != null) return;
		mMaker.at(settingsTree);
		JCBlock body  = PBlock(List.of(mMaker.Return(mMaker.Ident(ns(fieldName)))));
		int     flags = Flags.PUBLIC;
		JCMethodDecl method = mMaker.MethodDef(mMaker.Modifiers(flags),
		 ns(fieldName), mMaker.Ident(fieldType.tsym),
		 List.nil(), List.nil(), List.nil(), body, null);
		settingsTree.defs = settingsTree.defs.append(method);
	}

	private void addField(JCClassDecl settingsTree, Type type, String name, JCExpression init) {
		if (findChild(settingsTree,Tag.VARDEF, (JCVariableDecl t) -> t.name.contentEquals(name)) != null) return;
		addField0(settingsTree, Flags.PRIVATE, type, name, init).vartype = mMaker.Ident(type.tsym);
	}

	public void process() {
		// Placeholder for any additional processing logic
	}

	@Override
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(ContentInit.class.getCanonicalName(), SettingsInit.class.getCanonicalName());
	}
}
