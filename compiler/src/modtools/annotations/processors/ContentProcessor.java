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
import org.checkerframework.checker.nullness.qual.Nullable;

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
	public ArrayList<ClassSymbol> settingsClasses   = new ArrayList<>();
	public StringBuilder          loadMethodBuilder = new StringBuilder();
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
		JCMethodDecl            loadMethod = trees.getTree((ExecutableElement) findChild(element, "load", ElementKind.METHOD));
		ListBuffer<JCStatement> statements = new ListBuffer<>();

		for (Element field : findAllChild(element, null, ElementKind.FIELD)) {
			JCVariableDecl variableTree = (JCVariableDecl) trees.getTree(field);
			statements.add(
			 mMaker.Exec(mMaker.Assign(
				mMaker.Ident((Name) field.getSimpleName()),
				mMaker.NewClass(null, List.nil(), variableTree.vartype, List.nil(), null)
			 )));
		}

		loadMethod.body.stats = statements.toList();
		// print(loadMethod);
	}

	public void dealElement(ClassSymbol element) throws IOException {
		if (element.getAnnotation(ContentsLoad.class) != null) {
			contentLoad(element);
			// print(element);
		} else if (element.getAnnotation(SettingsInit.class) != null) {
			SettingsInit annotation = element.getAnnotation(SettingsInit.class);
			String       value      = annotation.value();
			if (element.owner instanceof ClassSymbol contentOwner && contentOwner.isSubClass(contentClass, types)) {
				JCMethodDecl initMethod = (JCMethodDecl) trees.getTree(contentOwner.members().findFirst(names.init));
				if (initMethod == null) return;
				Object literalName;
				try {
					literalName = ((JCLiteral) ((JCMethodInvocation) ((JCExpressionStatement) initMethod.body.stats.get(0)).expr).args.get(0)).value;
				} catch (Throwable e) {
					log.useSource(element.sourcefile);
					log.error(initMethod, SPrinter.err("Cannot load content data for: " + element));
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
				if (value.startsWith(SETTING_PREFIX)) value = value.substring(2);
			}
			processSetting(element, trees.getTree(element), value, annotation.parent(), annotation.fireEvent());
		}
	}
	public void process() throws Throwable {
		ClassSymbol    typeElement = elements.getTypeElement("modtools.SettingsLoader");
		if (typeElement != null) return;
		JavaFileObject file = mFiler.createSourceFile("modtools.SettingsLoader", settingsImpl);
		try (Writer writer = file.openWriter()) {
			writer.write("package modtools;\n");
			writer.write("public class SettingsLoader {\n");
			writer.write("  public static void load() {\n");

			writer.write("    ");
			writer.write(loadMethodBuilder.toString().replace("\n", "\n    "));
			writer.write("\n");

			writer.write("  }\n");
			writer.write("}");
		}
		loadMethodBuilder.setLength(0);
	}

	ClassSymbol currentSettings;
	JCClassDecl currentClassDecl;
	private final SwitchMethods switchMethods = new SwitchMethods();

	private void processSetting(ClassSymbol settings, JCClassDecl classDecl,
	                            Object literalName, String parent, boolean fireEvents) throws IOException {
		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(settings).getCompilationUnit();
		this.currentSettings = settings;
		this.currentClassDecl = classDecl;
		settingsClasses.add(settings);

		// trees.getTree(settingsImpl).mods.flags &= ~Flags.FINAL;
		// settingsImpl.flags_field &= ~Flags.FINAL;
		// settingsImpl.type.tsym.flags_field &= ~Flags.FINAL;
		// mMaker.at(classDecl.extending);
		// classDecl.extending = mMaker.QualIdent(settingsImpl);

		// addImport(unit, settingsImpl);
		// mMaker.at(classDecl.defs.get(0));
		// classDecl.extending = mMaker.Ident(settingsImpl);

		switchMethods.clear();
		allEnumFields.clear();

		SettingsMethods settingsMethods = new SettingsMethods(classDecl, settings, iSettings, myEvents,
		 mMaker, mSymtab);

		ListBuffer<JCStatement> defList = new ListBuffer<>();
		// ↑ 初始化结束 ↑


		classDecl.accept(new TreeScanner() {
			public void visitVarDef(JCVariableDecl tree) {
				if (tree.name.startsWith(prefix_underline)) return;
				if (!(tree.init instanceof JCNewClass newClass)) return;
				VarSymbol symbol = getSymbol(unit, tree);

				allEnumFields.put(symbol, "boolean");
				switchMethods.collect(symbol);

				// %name%.def(%args%[0])
				Name name = tree.name;
				// enumx(float.class, it -> it.$(...))
				if (newClass.args.size() >= 1 && newClass.args.get(0) instanceof JCFieldAccess classType) {
					allEnumFields.put(symbol, "" + classType.selected);
					// flushInitList.add(mMaker.Exec(mMaker.Assign(classType.selected, mMaker.Apply(mMaker.Select(name, ns("get")))));
				}
				if (newClass.args.size() == 2 && newClass.args.get(1) instanceof JCLambda lambda) {
					lambda.accept(new TreeScanner() {
						public void visitApply(JCMethodInvocation method) {
							mMaker.at(classDecl.defs.last());
							if (method.args.isEmpty()) return;
							defList.add(mMaker.Exec(mMaker.Apply(List.nil(),
							 mMaker.Select(mMaker.Ident(name),
								ns("def")),
							 List.of(method.args.get(0)))));
							if (!(method.args.size() >= 2 && newClass.args.get(0) instanceof JCFieldAccess classType
							      && method.args.get(0) instanceof JCFieldAccess access)) { return; }

							settingsMethods.flushField(classType, access, symbol);
						}
					});
				}
			}
		});
		if (!allEnumFields.isEmpty()) {
			// 生成对应的字段 enum_name(Type, ...) -> public static Type enumx = 默认值（ 0 / false / null）
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

		ListBuffer<JCTree> defBuilder = new ListBuffer<>();
		boolean            append     = false;
		Consumer<JCTree> consumer = def -> {
			if (!defList.isEmpty()) {
				mMaker.at(def);
				defBuilder.append(mMaker.Block(Flags.STATIC, defList.toList()));
			}
			// 添加flushAssignment
			if (settingsMethods.hasFlushAssignments()) {
				mMaker.at(def);
				defBuilder.append(mMaker.Block(Flags.STATIC, settingsMethods.flushAssignments()));
			}
		};
		for (JCTree def : classDecl.defs) {
			// 如果是合成的字段，则忽略
			defBuilder.append(def);
			if (append || (def instanceof JCVariableDecl vari && vari.sym.isStatic())) {
				continue;
			}
			append = true;
			consumer.accept(def);
		}
		if (!append) consumer.accept(classDecl.defs.last());
		classDecl.defs = defBuilder.toList();
		// println(classDecl);

		// 在ModTools里加载Class.forName(
		loadMethodBuilder.append(
		 mMaker.Try(PBlock(
			 mMaker.Exec(mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(mSymtab.classType.tsym), ns("forName")),
				List.of(mMaker.Literal(settings.flatname.toString()))))),
			List.of(mMaker.Catch(mMaker.VarDef(mMaker.Modifiers(Flags.FINAL), ns("e"), mMaker.Ident(mSymtab.classNotFoundExceptionType.tsym), null), PBlock())),
			null)
		).append('\n');

		// println(classDecl);

		switchMethods.build(classDecl, settings, iSettings, mMaker, mSymtab);
		addImport(settings, dataClass);

		mMaker.at(classDecl.defs.last());
		addMethod(classDecl, "data", dataClass.type, "data");
		if (parent.isEmpty()) addImport(settings, mySettingsClass);

		if (fireEvents) {
			addImport(settings, myEvents);
			settingsMethods.fireEvent();
		}


		if (findChild(classDecl, Tag.VARDEF,
		 (JCVariableDecl t) -> t.name.contentEquals("data")) == null) {
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
	Map<VarSymbol, String> allEnumFields = new LinkedHashMap<>();

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
		return Set.of(ContentsLoad.class, SettingsInit.class);
	}
}
