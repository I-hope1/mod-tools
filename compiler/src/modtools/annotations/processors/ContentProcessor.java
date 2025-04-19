package modtools.annotations.processors;


import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;
import modtools.annotations.settings.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import javax.tools.JavaFileObject;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import static modtools.annotations.processors.ContentProcessor.$.*;

/** 添加new XXX()，并给对应Content的Settings（如果有）初始化 */
@AutoService(Processor.class)
public class ContentProcessor extends BaseProcessor<ClassSymbol>
 implements DataUtils {
	public static final  String SETTING_PREFIX = "E_";
	private static final String FIELD_PREFIX   = "f_";
public static final  String REF_PREFIX     = "R_";

	private Name        nameSetting;
	private ClassType   consType;
	private ClassSymbol dataClass, mySettingsClass,
	 iSettings, myEvents, settingsImpl;
	private JCClassDecl mainClass;


	public void lazyInit() throws Throwable {
		nameSetting = ns("Settings");
		mySettingsClass = C_MySettings();
		dataClass = C_Data();
		iSettings = findClassSymbol("modtools.events.ISettings");
		myEvents = findClassSymbol("modtools.events.MyEvents");
		consType = findType("arc.func.Cons");
		settingsImpl = findClassSymbol("modtools.events.SettingsImpl");
		mainClass = trees.getTree(findClassSymbol("modtools.ModTools"));
	}

	public void contentLoad(ClassSymbol element) throws IOException {
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

	public void dealElement(ClassSymbol element) throws IOException {
		if (element.getAnnotation(ContentInit.class) != null) {
			contentLoad(element);
			// print(element);
		} else if (element.getAnnotation(SettingsInit.class) != null) {
			SettingsInit annotation = element.getAnnotation(SettingsInit.class);
			String       value      = annotation.value();
			if (value.equals(".")) {
				value = element.getSimpleName().toString();
				if (value.startsWith(SETTING_PREFIX)) value = value.substring(2);
			}
			processSetting(element, trees.getTree(element), value, annotation.parent(), annotation.fireEvent());
		}
	}

	public static class $ {
		static ClassSymbol settings;
		static JCClassDecl classDecl;
		static Object      literalName; /* content初始使的字面量name（主要是用于设置的key） */
		static String      parent;
		static boolean     fireEvents;
	}
	private void processSetting(ClassSymbol settings, JCClassDecl classDecl,
	                            Object literalName, String parent, boolean fireEvents) throws IOException {
		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(settings).getCompilationUnit();
		$.settings = settings;
		$.classDecl = classDecl;

		// trees.getTree(settingsImpl).mods.flags &= ~Flags.FINAL;
		// settingsImpl.flags_field &= ~Flags.FINAL;
		// settingsImpl.type.tsym.flags_field &= ~Flags.FINAL;
		// mMaker.at(classDecl.extending);
		// classDecl.extending = mMaker.QualIdent(settingsImpl);

		// addImport(unit, settingsImpl);
		// mMaker.at(classDecl.defs.get(0));
		// classDecl.extending = mMaker.Ident(settingsImpl);

		allSwitches.clear();
		allEnumFields.clear();
		flushAssignment.clear();

		ListBuffer<JCStatement> defList = new ListBuffer<>();
		// ↑ 初始化结束 ↑


		classDecl.accept(new TreeScanner() {
			public void visitVarDef(JCVariableDecl tree) {
				if (!(tree.init instanceof JCNewClass newClass)) return;
				VarSymbol symbol = getSymbol(unit, tree);

				allEnumFields.put(symbol, "boolean");
				collectSwitch(symbol);

				// %name%.def(%args%[0])
				Name name = tree.name;
				// enumx(float.class, it -> it.$(...))
				if (newClass.args.size() >= 1 && newClass.args.get(0) instanceof JCFieldAccess classType) {
					allEnumFields.put(symbol, "" + classType.selected);
				}
				if (newClass.args.size() == 2 && newClass.args.get(1) instanceof JCLambda lambda) {
					lambda.accept(new TreeScanner() {
						public void visitApply(JCMethodInvocation method) {
							mMaker.at(classDecl.defs.last());
							defList.add(mMaker.Exec(mMaker.Apply(List.nil(),
							 mMaker.Select(mMaker.Ident(name),
								ns("def")),
							 List.of(method.args.get(0)))));
							if (!(method.args.size() >= 2 && newClass.args.get(0) instanceof JCFieldAccess classType
							      && method.args.get(0) instanceof JCFieldAccess access)) { return; }

							buildFlushField(classType, access, symbol);
						}
					});
				}
			}
		});
		if (!allEnumFields.isEmpty()) {
			// 生成对应的字段 enum_name(Type, ...) -> public static Type enumx = 默认值（ 0 / false / null）
			Name             pkgName       = settings.packge().fullname;
			JavaFileObject   file          = mFiler.createSourceFile(pkgName + "." +REF_PREFIX + literalName, settings);
			Writer           writer        = file.openWriter();
			StringBuilder    body          = new StringBuilder();

			writer.write(String.valueOf(unit.getPackage()));
			writer.write(unit.getImports().stream().map(String::valueOf).collect(Collectors.joining("", "\n", "\n")));
			writer.write("/** @see " + settings.getQualifiedName() + "*/\n");
			writer.write("public class " + REF_PREFIX + literalName + " {\n");
			// writer.write(allEnumFields.entrySet().stream().reduce("\n",
			//  (a, b) -> a + "\npublic static " + b.getValue() + " " + b.getKey().getSimpleName() + ";", (a, b) -> a + b));
			allEnumFields.forEach((symbol, type) -> {
				body.append("\tpublic static ").append(type).append(' ').append(symbol.getSimpleName()).append(";\n");
			});
			writer.write(body.toString());
			writer.write("\n}");
			writer.close();
		}
		if (!defList.isEmpty()) {
			mMaker.at(classDecl.defs.last());
			classDecl.defs = classDecl.defs.append(mMaker.Block(Flags.STATIC, defList.toList()));
		}
		// 添加flushAssignment
		if (!flushAssignment.isEmpty()) {
			mMaker.at(classDecl.defs.last());
			classDecl.defs = classDecl.defs.append(mMaker.Block(Flags.STATIC, flushAssignment.toList()));
		}

		// 在ModTools里加载Class.forName(
		mMaker.at(mainClass.defs.last());
		mainClass.defs = mainClass.defs.append(PBlock(
		 mMaker.Try(PBlock(
			 mMaker.Exec(mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(mSymtab.classType.tsym), ns("forName")), List.of(mMaker.Literal(settings.flatname.toString())))))
			, List.of(mMaker.Catch(mMaker.VarDef(mMaker.Modifiers(Flags.FINAL), ns("e"), mMaker.Ident(mSymtab.classNotFoundExceptionType.tsym), null), PBlock()))
			, null)
		));

		// println(classDecl);

		buildSwitch();
		addImport(settings, dataClass);

		mMaker.at(classDecl.defs.last());
		addMethod(classDecl, "data", dataClass.type, "data");
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


		if (settings.members().findFirst(ns("data"), t -> t.kind == Kind.VAR) == null) {
			addConstantField(Flags.PUBLIC, classDecl, dataClass.type,
			 "data", createInitValue(parent, literalName));
		}

		var allInit = classDecl.defs.stream()
		 .filter(t -> t instanceof JCMethodDecl)
		 .map(t -> (JCMethodDecl) t)
		 .filter(m1 -> m1.name.equals(names.init)).toList();

		String f_typeName = FIELD_PREFIX + "type";
		addMethod(classDecl, f_typeName, mSymtab.classType, "type");
		var f_type = addField(classDecl, mSymtab.classType, f_typeName, mMaker.ClassLiteral(mSymtab.booleanType));
		allInit.stream().filter(m1 -> m1.params.size() == 2).forEach(init -> {
			String f_builderName = FIELD_PREFIX + "builder";
			addMethod(classDecl, f_builderName, consType, "builder");
			var f_builder = addField(classDecl, consType, f_builderName, null);
			init.body.stats = init.body.stats.append(
			 mMaker.Exec(mMaker.Assign(mMaker.Ident(f_type), mMaker.Ident(init.params.get(0))))
			);

			JCVariableDecl param = init.params.get(1);

			JCExpression rhs = mMaker.Ident(param);
			// args = xxx
			init.body.stats = init.body.stats.append(
			 mMaker.Exec(mMaker.Assign(mMaker.Ident(f_builder), rhs))
			);
		});

		// if (!settings.getSimpleName().contentEquals("E_JSFuncDisplay")) return;
		// println("------------------------------");
		// println(classDecl);
	}
	ListBuffer<JCStatement> flushAssignment = new ListBuffer<>();
	private void buildFlushField(JCFieldAccess classType, JCFieldAccess access, VarSymbol symbol) {
		if (symbol.getAnnotation(FlushField.class) == null) return;

		mMaker.at(classDecl.defs.last());
		flushAssignment.add(buildAssignment(classType, access, mMaker.Ident(symbol)));
		Iterable<Symbol> methodSym = settings.members().getSymbols(
		 t -> t instanceof MethodSymbol m && m.name.toString().equals("set") && !m.params.isEmpty() && m.params.get(0).type.equals(mSymtab.objectType)
		      && m.getReturnType().equals(mSymtab.voidType));
		MethodSymbol ms;

		Iterator<Symbol> iterator = methodSym.iterator();
		if (!iterator.hasNext()) {
			mMaker.at(classDecl.defs.last());
			int flags = Flags.PUBLIC;
			ms = new MethodSymbol(flags, ns("set"),
			 new MethodType(List.of(mSymtab.objectType), mSymtab.voidType, List.nil(), settings),
			 settings);

			JCVariableDecl val = mMaker.Param(ns("val"), mSymtab.objectType, ms);
			JCBlock body = PBlock(mMaker.Exec(mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Select(mMaker.Ident(iSettings), ns("super")), ns("set")),
			 List.of(mMaker.Ident(val)))));
			ms.params = List.of(val.sym);

			JCMethodDecl method = mMaker.MethodDef(ms, body);
			settings.members().enter(ms);
			classDecl.defs = classDecl.defs.append(method);
		} else { ms = (MethodSymbol) iterator.next(); }

		JCMethodDecl methodDecl = trees.getTree(ms);
		methodDecl.body.stats = methodDecl.body.stats.append(
		 mMaker.If(mMaker.Binary(Tag.EQ, mMaker.Ident(symbol),
			 mMaker.This(settings.type)),
			buildAssignment(classType, access, mMaker.This(settings.type)), null)
		);
	}
	// ai 生成就是快
	private void buildSwitch() {
		if (allSwitches.isEmpty()) return;
		/* 仿照上面的，参加一个方法 boolean hasSwitch() {
			if (this == %name1%) return true;
			if (this == %name2%) return true;
			...
			return false;
		} */
		mMaker.at(classDecl.defs.last());
		MethodSymbol            ms         = new MethodSymbol(Flags.PUBLIC, ns("hasSwitch"), mSymtab.booleanType, settings);
		ListBuffer<JCStatement> listBuffer = new ListBuffer<>();
		allSwitches.forEach((symbol, aSwitch) -> {
			JCStatement ifState = mMaker.If(mMaker.Binary(Tag.EQ, mMaker.Ident(symbol),
			 mMaker.This(settings.type)), mMaker.Return(mMaker.Literal(true)), null);
			listBuffer.append(ifState);
		});
		listBuffer.append(mMaker.Return(mMaker.Literal(false)));
		JCMethodDecl method = mMaker.MethodDef(ms, PBlock(listBuffer.toList()));
		method.restype = mMaker.Type(mSymtab.booleanType);
		settings.members().enter(ms);
		classDecl.defs = classDecl.defs.append(method);

		/* 仿照上面的，参加一个方法 String switchKey() {
			if (this == %name1%) return %dependency1%;
			if (this == %name2%) return %dependency2%;
			...
			return ISettings.super.switchKey();
		} */

		mMaker.at(classDecl.defs.last());
		ms = new MethodSymbol(Flags.PUBLIC, ns("switchKey"), mSymtab.stringType, settings);
		listBuffer.clear();
		allSwitches.forEach((symbol, aSwitch) -> {
			if (aSwitch.dependency().isEmpty()) return;
			listBuffer.append(mMaker.If(mMaker.Binary(Tag.EQ, mMaker.Ident(symbol),
			 mMaker.This(settings.type)), mMaker.Return(mMaker.Literal(aSwitch.dependency())), null));
		});
		listBuffer.append(mMaker.Return(mMaker.Apply(List.nil(), mMaker.Select(mMaker.Select(mMaker.Type(iSettings.type), ns("super")), ns("switchKey")), List.nil())));
		method = mMaker.MethodDef(ms, PBlock(listBuffer.toList()));
		method.restype = mMaker.Type(mSymtab.stringType);
		settings.members().enter(ms);
		classDecl.defs = classDecl.defs.append(method);
	}
	Map<VarSymbol, Switch> allSwitches   = new HashMap<>();
	Map<VarSymbol, String>   allEnumFields = new HashMap<>();
	private void collectSwitch(VarSymbol symbol) {
		Switch aSwitch = symbol.getAnnotation(Switch.class);
		if (aSwitch == null) return;
		allSwitches.put(symbol, aSwitch);
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

	private void addMethod(JCClassDecl settingsTree, String fieldName, Type fieldType, String methodName) {
		if (findChild(settingsTree, Tag.METHODDEF, (JCMethodDecl t) -> t.name.contentEquals(fieldName)) != null) { return; }
		mMaker.at(settingsTree);
		JCBlock body  = PBlock(List.of(mMaker.Return(mMaker.Ident(ns(fieldName)))));
		int     flags = Flags.PUBLIC;
		JCMethodDecl method = mMaker.MethodDef(mMaker.Modifiers(flags),
		 ns(methodName), mMaker.Ident(fieldType.tsym),
		 List.nil(), List.nil(), List.nil(), body, null);
		settingsTree.defs = settingsTree.defs.append(method);
	}

	private JCVariableDecl addField(JCClassDecl settingsTree, Type type, String name, JCExpression init) {
		JCVariableDecl v;
		if ((v = findChild(settingsTree, Tag.VARDEF, (JCVariableDecl t) -> t.name.contentEquals(name))) != null) {
			return v;
		}
		v = addField0(settingsTree, Flags.PRIVATE, type, name, init);
		v.vartype = mMaker.Ident(type.tsym);
		return v;
	}

	@Override
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(ContentInit.class, SettingsInit.class);
	}
}
