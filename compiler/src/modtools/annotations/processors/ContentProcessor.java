package modtools.annotations.processors;


import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import modtools.annotations.*;
import modtools.annotations.settings.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.tools.JavaFileObject;
import java.io.*;
import java.util.*;
import java.util.function.*;

import static modtools.annotations.NameString.ns0;

/** 添加new XXX()，并给对应Content的Settings（如果有）初始化 */
@AutoService(Processor.class)
public class ContentProcessor extends BaseProcessor<ClassSymbol>
 implements DataUtils {
	public static final  String SETTING_PREFIX = "E_";
	private static final String FIELD_PREFIX   = "f_";

	private Name        nameSetting;
	private ClassType   consType;
	private ClassSymbol dataClass, mySettingsClass,
	 iSettings, myEvents, settingsImpl, contentClass;
	private Name prefix_underline;

	/** 用于ModTools调用forName(设置类) */
	private final StringBuilder loadMethodBuilder = new StringBuilder();
	public void lazyInit() throws Throwable {
		nameSetting = ns("Settings");
		mySettingsClass = C_MySettings();
		dataClass = C_Data();
		iSettings = findClassSymbol("modtools.events.ISettings");
		myEvents = findClassSymbol("modtools.events.MyEvents");
		consType = findType("arc.func.Cons");
		settingsImpl = findClassSymbol("modtools.events.SettingsImpl");
		contentClass = findClassSymbol("modtools.content.Content");
		prefix_underline = ns("_");
	}

	public void contentLoad(ClassSymbol element) throws IOException {
		Element load = findChild(element, "load", ElementKind.METHOD);
		// 没有 load() 就没有生成目标；直接 getTree(null) 会 NPE。
		if (load == null) return;

		JCMethodDecl loadMethod = trees.getTree((ExecutableElement) load);
		// 增量编译时这个类可能不在当前 round 的源码里，拿不到 tree 也不能改。
		if (loadMethod == null || loadMethod.body == null) return;

		ListBuffer<JCStatement> statements = new ListBuffer<>();
		for (Element field : findAllChild(element, null, ElementKind.FIELD)) {
			JCVariableDecl variableTree = (JCVariableDecl) trees.getTree(field);
			if (variableTree == null) continue;

			statements.add(
			 mMaker.Exec(mMaker.Assign(
				mMaker.Ident((Name) field.getSimpleName()),
				mMaker.NewClass(null, List.nil(), variableTree.vartype, List.nil(), null)
			 )));
		}

		loadMethod.body.stats = statements.toList();
	}

	public void dealElement(ClassSymbol element) throws IOException {
		if (element.getAnnotation(ContentsLoad.class) != null) {
			contentLoad(element);
			// print(element);
		} else if (element.getAnnotation(SettingsInit.class) != null) {
			SettingsInit annotation = element.getAnnotation(SettingsInit.class);
			String       value      = annotation.value();
			if (element.owner instanceof ClassSymbol contentOwner && contentOwner.isSubClass(contentClass, types)) {
				// 先判空再 getTree：findFirst 找不到构造器时直接 getTree(null) 会 NPE。
				Element      initElement = contentOwner.members().findFirst(names.init);
				JCMethodDecl initMethod  = initElement == null ? null : (JCMethodDecl) trees.getTree(initElement);
				if (initMethod == null) return;

				Object literalName = firstLiteralArg(initMethod);
				if (literalName == null) {
					// useSource 改的是全局状态，必须还原，否则后续诊断会记到错误的文件上。
					JavaFileObject prev = log.useSource(element.sourcefile);
					try {
						log.error(initMethod, SPrinter.err("Cannot load content data for: " + element));
					} finally {
						log.useSource(prev);
					}
					return;
				}

				ClassSymbol settingsSymbol = (ClassSymbol) contentOwner.members().findFirst(nameSetting, t -> t.kind == Kind.TYP);
				// println("[ContentProcessor] SettingsSymbol: " + settingsSymbol);
				if (settingsSymbol != null) {
					JCClassDecl settingsTree = trees.getTree(settingsSymbol);
					if (settingsTree == null) {
						// 关键：在增量编译时，如果拿不到 Tree，说明这个类没被包含在当前 Round 的源码中
						// 此时不应继续尝试修改，否则会造成字节码不一致
						println("[ContentProcessor] Warning：Cannot find the tree for:  " + settingsSymbol);
						return;
					}
					// println("[ContentProcessor] Tree: " + settingsTree);
					processSetting(settingsSymbol, settingsTree, literalName, "", false);
				}
				return;
			}
			if (value.equals(".")) {
				value = element.getSimpleName().toString();
				if (value.startsWith(SETTING_PREFIX)) value = value.substring(SETTING_PREFIX.length());
			}
			processSetting(element, trees.getTree(element), value, annotation.parent(), annotation.fireEvent());
		}
	}

	/**
	 * 取出构造器首句 {@code super("name")} 里的字面量名字。
	 * <p>
	 * 用 {@code instanceof} 逐层判断，而不是强转 + {@code catch (Throwable)}：
	 * 后者会把 {@link OutOfMemoryError}、{@link StackOverflowError} 也一并吞掉，
	 * 变成一条“Cannot load content data”的误导性错误。
	 *
	 * @return 拿不到字面量时返回 {@code null}
	 */
	private static Object firstLiteralArg(JCMethodDecl initMethod) {
		if (initMethod.body == null || initMethod.body.stats.isEmpty()) return null;
		if (!(initMethod.body.stats.get(0) instanceof JCExpressionStatement statement)) return null;
		if (!(statement.expr instanceof JCMethodInvocation invocation)) return null;
		if (invocation.args.isEmpty()) return null;
		if (!(invocation.args.get(0) instanceof JCLiteral literal)) return null;
		return literal.value;
	}
	public void process() throws Throwable {
		ClassSymbol    typeElement = elements.getTypeElement("modtools.SettingsLoader");
		if (typeElement != null) return;
		JavaFileObject file = mFiler.createSourceFile("modtools.SettingsLoader", settingsImpl);
		try (Writer writer = file.openWriter()) {
			writer.write("package modtools;\n");
			writer.write("public class SettingsLoader {\n");
			writer.write("  public static void load() {\n");

			writer.write(SettingsLoaderSource.body(loadMethodBuilder));

			writer.write("  }\n");
			writer.write("}");
		}
		loadMethodBuilder.setLength(0);
	}

	private final SwitchMethods switchMethods = new SwitchMethods();

	private void processSetting(ClassSymbol settings, JCClassDecl classDecl,
	                            Object literalName, String parent, boolean fireEvents) throws IOException {
		var path = trees.getPath(settings);
		// 增量编译时这个类可能不在当前 round 的源码里，拿不到 path 就不能改。
		if (path == null) return;
		JCCompilationUnit unit = (JCCompilationUnit) path.getCompilationUnit();

		switchMethods.clear();
		// 每个 Settings 类独立的收集结果；做成局部变量，省掉「换类时记得 clear」这条隐式约定。
		Map<VarSymbol, String> allEnumFields = new LinkedHashMap<>();
		SettingsMethods settingsMethods = new SettingsMethods(classDecl, settings, iSettings, myEvents,
		 mMaker, mSymtab);
		ListBuffer<JCStatement> defList = new ListBuffer<>();

		collectMembers(classDecl, unit, allEnumFields, defList, settingsMethods);
		writeRefSource(settings, literalName, allEnumFields, unit);
		insertStaticBlocks(classDecl, defList, settingsMethods);

		// 在 ModTools 运行时强制加载该 Settings 类，触发其静态初始化以完成注册。
		SettingsLoaderSource.appendLoad(loadMethodBuilder, settings.flatname);

		switchMethods.validate(classDecl, settings, log, trees);
		switchMethods.build(classDecl, settings, iSettings, mMaker, mSymtab);
		addImport(settings, dataClass);

		mMaker.at(classDecl.defs.last());
		addMethod(classDecl, "data", dataClass.type, "data");
		if (parent.isEmpty()) addImport(settings, mySettingsClass);

		if (fireEvents) {
			addImport(settings, myEvents);
			settingsMethods.fireEvent();
		}

		addDataField(classDecl, parent, literalName);
		addTypeAndBuilderMembers(classDecl);
	}

	/**
	 * 扫描枚举常量：收集 Ref 字段、{@code def(...)} 调用和 {@code @FlushField} 写回。
	 */
	private void collectMembers(JCClassDecl classDecl, JCCompilationUnit unit,
	                            Map<VarSymbol, String> allEnumFields,
	                            ListBuffer<JCStatement> defList,
	                            SettingsMethods settingsMethods) {
		classDecl.accept(new TreeScanner() {
			public void visitVarDef(JCVariableDecl tree) {
				// TreeScanner 会深入方法体和嵌套类：方法里的 `Foo x = new Foo()`、
				// 嵌套类的字段都会被扫到，从而被当成 Settings 常量写进 Ref 类。
				// 只有 classDecl 的直接成员才是真正的枚举常量 / 字段。
				if (!classDecl.defs.contains(tree)) return;
				if (tree.name.startsWith(prefix_underline)) return;
				if (!(tree.init instanceof JCNewClass newClass)) return;
				VarSymbol symbol = getSymbol(unit, tree);

				allEnumFields.put(symbol, "boolean");
				switchMethods.collect(symbol);

				Name name = tree.name;
				// enumx(float.class, it -> it.$(...))
				if (newClass.args.size() >= 1 && newClass.args.get(0) instanceof JCFieldAccess classType) {
					allEnumFields.put(symbol, "" + classType.selected);
				}
				if (newClass.args.size() == 2 && newClass.args.get(1) instanceof JCLambda lambda) {
					lambda.accept(new TreeScanner() {
						public void visitApply(JCMethodInvocation method) {
							mMaker.at(classDecl.defs.last());
							if (method.args.isEmpty()) return;
							// %name%.def(%args%[0])
							defList.add(mMaker.Exec(mMaker.Apply(List.nil(),
							 mMaker.Select(mMaker.Ident(name), ns("def")),
							 List.of(method.args.get(0)))));
							if (!(method.args.size() >= 2 && newClass.args.get(0) instanceof JCFieldAccess classType
							      && method.args.get(0) instanceof JCFieldAccess access)) { return; }

							settingsMethods.flushField(classType, access, symbol);
						}
					});
				}
			}
		});
	}

	/** 生成 {@code R_<name>} 中间类：枚举常量名 → 默认值类型，供 {@code @RefOf} 引用。 */
	private void writeRefSource(ClassSymbol settings, Object literalName,
	                            Map<VarSymbol, String> allEnumFields,
	                            JCCompilationUnit unit) throws IOException {
		if (allEnumFields.isEmpty()) return;

		Name pkgName = settings.packge().fullname;
		try {
			JavaFileObject file = mFiler.createSourceFile(pkgName + "." + RefRewriter.REF_PREFIX + literalName, settings);
			try (Writer writer = file.openWriter()) {
				writer.write(RefSourceGenerator.render(unit, settings, literalName, allEnumFields));
			}
		} catch (FilerException e) {
			println(pkgName + " has been created.");
		}
	}

	/**
	 * 把 {@code def(...)} 和 {@code @FlushField} 的写回语句插进静态初始化块。
	 * <p>
	 * 位置取「第一个非静态成员之后」：枚举常量本身是静态的，跳过它们，
	 * 保证块在常量都构造完之后才执行。
	 */
	private void insertStaticBlocks(JCClassDecl classDecl, ListBuffer<JCStatement> defList,
	                                SettingsMethods settingsMethods) {
		ListBuffer<JCTree> defBuilder = new ListBuffer<>();
		boolean            append     = false;
		Consumer<JCTree> consumer = def -> {
			if (!defList.isEmpty()) {
				mMaker.at(def);
				defBuilder.append(mMaker.Block(Flags.STATIC, defList.toList()));
			}
			if (settingsMethods.hasFlushAssignments()) {
				mMaker.at(def);
				defBuilder.append(mMaker.Block(Flags.STATIC, settingsMethods.flushAssignments()));
			}
		};
		for (JCTree def : classDecl.defs) {
			defBuilder.append(def);
			if (append || (def instanceof JCVariableDecl vari && vari.sym.isStatic())) continue;
			append = true;
			consumer.accept(def);
		}
		if (!append) consumer.accept(classDecl.defs.last());
		classDecl.defs = defBuilder.toList();
	}

	/** 没有 {@code data} 字段就补一个，值取自父 Settings 或全局 {@code SETTINGS}。 */
	private void addDataField(JCClassDecl classDecl, String parent, Object literalName) {
		if (findChild(classDecl, Tag.VARDEF, (JCVariableDecl t) -> t.name.contentEquals("data")) != null) return;
		addConstantField(Flags.PUBLIC, classDecl, dataClass.type, "data", createInitValue(parent, literalName));
	}

	/**
	 * 生成 {@code type()} / {@code builder()} 两个 getter 及背后的字段，
	 * 并让两参构造器把实参存进去。
	 */
	private void addTypeAndBuilderMembers(JCClassDecl classDecl) {
		var allInit = classDecl.defs.stream()
		 .filter(t -> t instanceof JCMethodDecl)
		 .map(t -> (JCMethodDecl) t)
		 .filter(m1 -> m1.name.equals(names.init)).toList();

		String f_typeName = FIELD_PREFIX + "type";
		addMethod(classDecl, f_typeName, mSymtab.classType, "type");
		var f_type = addField(classDecl, mSymtab.classType, f_typeName, mMaker.ClassLiteral(mSymtab.booleanType));

		// 两参构造器：第 0 个参数是枚举常量的 type，第 1 个是 UI builder。
		// f_builder 只在存在这种构造器时才需要，且必须只加一次：
		// 按构造器逐个添加的话，多个 (Class, Cons) 构造器会生成重复的 builder()。
		JCVariableDecl f_builder = null;
		for (JCMethodDecl init : allInit) {
			if (init.params.size() != 2) continue;
			if (f_builder == null) {
				String f_builderName = FIELD_PREFIX + "builder";
				addMethod(classDecl, f_builderName, consType, "builder");
				f_builder = addField(classDecl, consType, f_builderName, null);
			}

			init.body.stats = init.body.stats.append(
			 mMaker.Exec(mMaker.Assign(mMaker.Ident(f_type), mMaker.Ident(init.params.get(0))))
			);
			// f_builder = args
			init.body.stats = init.body.stats.append(
			 mMaker.Exec(mMaker.Assign(mMaker.Ident(f_builder), mMaker.Ident(init.params.get(1))))
			);
		}
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
		// 去重看「生成的方法名」，不是它返回的字段名：
		// f_type / f_builder 这类字段名不会出现在方法表里，按字段名查永远查不到。
		if (findChild(settingsTree, Tag.METHODDEF, (JCMethodDecl t) -> t.name.contentEquals(methodName)) != null) { return; }
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
		return Set.of(ContentsLoad.class, SettingsInit.class);
	}
}
