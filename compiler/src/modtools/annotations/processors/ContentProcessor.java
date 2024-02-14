package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.*;

/** 添加new XXX()，并给对应Content的Settings（如果有）初始化 */
@AutoService({Processor.class})
// Inside the class:
public class ContentProcessor extends BaseProcessor<ClassSymbol>
 implements DataUtils {
	private Name        nameSetting;
	private ClassSymbol dataClass, mySettingsClass,
	 iSettings, myEvents, settingsImpl;

	public void init() throws Throwable {
		nameSetting = ns("Settings");
		mySettingsClass = C_MySettings();
		dataClass = C_Data();
		iSettings = findClassSymbol("modtools.events.ISettings");
		myEvents = findClassSymbol("modtools.events.MyEvents");
		// settingsImpl = findClassSymbol("modtools.events.SettingsImpl");
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
				processSetting(settingsSymbol, settingsTree, literalName, "", false);
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
			processSetting(element, trees.getTree(element), value, annotation.parent(), annotation.fireEvent());
		}
	}

	private void processSetting(ClassSymbol settings, JCClassDecl classDecl,
															Object literalName, String parent, boolean fireEvents) {
		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(settings).getCompilationUnit();
		// addImport(unit, settingsImpl);
		// mMaker.at(classDecl.defs.get(0));
		// classDecl.extending = mMaker.Ident(settingsImpl);

		classDecl.accept(new TreeScanner() {
			public void visitVarDef(JCVariableDecl tree) {
				if (!(tree.init instanceof JCNewClass newClass)) return;
				if (!(newClass.args.size() >= 2 && newClass.args.get(0) instanceof JCFieldAccess classType
							&& newClass.args.get(1) instanceof JCFieldAccess access)) return;
				if (newClass.def != null) {
					println(classDecl.mods.flags);
				}
				VarSymbol symbol = getSymbol(unit, tree);
				if (symbol.getAnnotation(FlushField.class) == null) return;

				mMaker.at(classDecl.defs.last());
				classDecl.defs = classDecl.defs.append(mMaker.Block(Flags.STATIC,
				 List.of(buildAssignment(classType, access, mMaker.Ident(symbol)))));
				Iterable<Symbol> set = settings.members().getSymbols(
				 t -> t instanceof MethodSymbol m && m.name.toString().equals("set") && !m.params.isEmpty() && m.params.get(0).type.equals(mSymtab.objectType)
							&& m.getReturnType().equals(mSymtab.voidType));
				MethodSymbol ms;

				Iterator<Symbol> iterator = set.iterator();
				if (!iterator.hasNext()) {
					mMaker.at(classDecl.defs.last());
					int flags = Flags.PUBLIC;
					ms = new MethodSymbol(flags, ns("set"),
					 new MethodType(List.of(mSymtab.objectType), mSymtab.voidType, List.nil(), settings),
					 settings);

					JCVariableDecl val = makeVar(Flags.PARAMETER, mSymtab.objectType, "val", null, ms);
					JCBlock body = PBlock(mMaker.Exec(mMaker.Apply(List.nil(),
					 mMaker.Select(mMaker.Select(mMaker.Ident(iSettings), ns("super")), ns("set")),
					 List.of(mMaker.Ident(val.name)))));
					ms.params = List.of(val.sym);

					JCMethodDecl method = mMaker.MethodDef(ms, body);
					settings.members().enter(ms);
					classDecl.defs = classDecl.defs.append(method);
				} else ms = (MethodSymbol) iterator.next();

				JCMethodDecl methodDecl = trees.getTree(ms);
				methodDecl.body.stats = methodDecl.body.stats.append(
				 mMaker.If(mMaker.Binary(Tag.EQ, mMaker.Ident(symbol),
					 mMaker.This(settings.type)),
					buildAssignment(classType, access, mMaker.This(settings.type)), null)
				);
			}
			private JCExpressionStatement buildAssignment(JCFieldAccess classType, JCFieldAccess access,
																										JCExpression selector) {
				return mMaker.Exec(mMaker.Assign(access,
					mMaker.Apply(List.nil(),
					 mMaker.Select(selector, ns("get" + kebabToBigCamel(classType.selected.toString()))),
					 List.nil()
					)
				 )
				);
			}
		});
		addImport(settings, dataClass);

		mMaker.at(classDecl.defs.last());
		addMethod(classDecl, "data", dataClass.type);
		if (parent.isEmpty()) addImport(settings, mySettingsClass);

		if (fireEvents) {
			addImport(settings, myEvents);
			JCMethodDecl put = mMaker.MethodDef(mMaker.Modifiers(Flags.PUBLIC),
			 ns("set"), mMaker.TypeIdent(TypeTag.VOID), List.nil(),
			 List.of(mMaker.Param(ns("val"), mSymtab.objectType, null)),
			 List.nil(),
			 PBlock(
				mMaker.Exec(mMaker.Apply(
				 List.nil(), mMaker.Select(
					mMaker.Select(mMaker.Ident(iSettings), ns("super")),
					ns("set")
				 ), List.of(mMaker.Ident(ns("val")))
				)),
				mMaker.Exec(mMaker.Apply(
				 List.nil(), mMaker.Select(
					mMaker.Ident(myEvents),
					ns("fire")
				 ), List.of(mMaker.This(settings.type))
				))
			 ), null);
			// print(put);
			classDecl.defs = classDecl.defs.append(put);
		}

		JCMethodInvocation initValue = createInitValue(parent, literalName);

		if (settings.members().findFirst(ns("data"), t -> t.kind == Kind.VAR) == null)
			addConstantField(Flags.PUBLIC, classDecl, dataClass.type, "data", initValue);

		java.util.List<JCMethodDecl> allInit = classDecl.defs.stream()
		 .filter(t -> t instanceof JCMethodDecl)
		 .map(t -> (JCMethodDecl) t)
		 .filter(m1 -> m1.name.equals(names.init)).toList();

		allInit.stream().filter(m1 -> !m1.params.isEmpty()).forEach(init -> {
			addMethod(classDecl, "type", mSymtab.classType);
			addField(classDecl, mSymtab.classType, "type", mMaker.ClassLiteral(mSymtab.booleanType));

			init.body.stats = init.body.stats.prepend(
			 mMaker.Exec(mMaker.Assign(mMaker.Select(mMaker.This(settings.type), ns("type")), mMaker.Ident(init.params.get(0).sym)))
			);
		});
		allInit.stream().filter(m1 -> m1.params.size() == 2).forEach(init -> {
			addMethod(classDecl, "args", mSymtab.objectType);
			addField(classDecl, mSymtab.objectType, "args", null);

			init.body.stats = init.body.stats.prepend(
			 mMaker.Exec(mMaker.Assign(mMaker.Select(mMaker.This(settings.type), ns("args")), mMaker.Ident(init.params.get(1).sym)))
			);
		});

		// println(classDecl);
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
		if (findChild(settingsTree, Tag.METHODDEF, (JCMethodDecl t) -> t.name.contentEquals(fieldName)) != null)
			return;
		mMaker.at(settingsTree);
		JCBlock body  = PBlock(List.of(mMaker.Return(mMaker.Ident(ns(fieldName)))));
		int     flags = Flags.PUBLIC;
		JCMethodDecl method = mMaker.MethodDef(mMaker.Modifiers(flags),
		 ns(fieldName), mMaker.Ident(fieldType.tsym),
		 List.nil(), List.nil(), List.nil(), body, null);
		settingsTree.defs = settingsTree.defs.append(method);
	}

	private void addField(JCClassDecl settingsTree, Type type, String name, JCExpression init) {
		if (findChild(settingsTree, Tag.VARDEF, (JCVariableDecl t) -> t.name.contentEquals(name)) != null)
			return;
		addField0(settingsTree, Flags.PRIVATE, type, name, init).vartype = mMaker.Ident(type.tsym);
	}

	@Override
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(ContentInit.class, SettingsInit.class);
	}
}
