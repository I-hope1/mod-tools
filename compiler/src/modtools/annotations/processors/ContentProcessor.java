package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;
import modtools.utils.MySettings.Data;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.*;

/** 添加new XXX()，并给对应Content的Settings（如果有）初始化  */
@AutoService({Processor.class})
public class ContentProcessor extends BaseProcessor {
	public void dealElement(Element element) {
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
			processSetting((ClassSymbol) element, (JCClassDecl) trees.getTree(element), value, annotation.parent());
		}
	}
	Name      Name_Setting;
	ClassType CL_MySettings;
	ClassType CL_Data;
	public void init() throws Throwable {
		Name_Setting = names.fromString("Settings");
		CL_MySettings = findType("modtools.utils.MySettings");
		CL_Data = findType($_DATA);
	}
	public void contentLoad(Element element) {
		JCMethodDecl            m    = trees.getTree((ExecutableElement) findChild(element, "load", ElementKind.METHOD));
		ListBuffer<JCStatement> list = new ListBuffer<>();


		for (Element field : findAllChild(element, null, ElementKind.FIELD)) {
			JCVariableDecl tree = (JCVariableDecl) trees.getTree(field);
			list.add(parseStatement(field.getSimpleName()
															+ "=new " + tree.vartype + "();"));

			TypeSymbol TP_Content = tree.vartype.type.tsym;
			// 获取TP_Content的初始化的name
			JCMethodDecl init_method  = (JCMethodDecl) trees.getTree(TP_Content.members().findFirst(names.fromString("<init>")));
			Object       literal_name = ((JCLiteral) ((JCMethodInvocation) ((JCExpressionStatement) init_method.body.stats.get(0)).expr).args.get(0)).value;


			ClassSymbol settings = (ClassSymbol) TP_Content.members().findFirst(Name_Setting, t -> t.kind == Kind.TYP);
			if (settings == null) continue;
			JCClassDecl settingsTree = trees.getTree(settings);
			processSetting(settings, settingsTree, literal_name, "");
			// System.out.println(settingsTree);
		}
		m.body.stats = list.toList();
		// Log.info(m);
	}
	// -------------------Settings-------------------
	private void processSetting(ClassSymbol settings, JCClassDecl settingsTree, Object literal_name, String parent) {
		addImport(settings, CL_Data);
		// 给Settings添加方法data()和私有final字段data
		// 添加data()方法
		JCBlock body = mMaker.Block(0, List.of(mMaker.Return(mMaker.Ident(names.fromString("data")))));
		JCMethodDecl m_data = mMaker.MethodDef(mMaker.Modifiers(Flags.PUBLIC | Flags.FINAL), names.fromString("data"), mMaker.TypeApply(mMaker.Ident(CL_Data.tsym), List.nil()),
		 List.nil(), List.nil(), List.nil(),
		 body, null);
		settingsTree.defs = settingsTree.defs.append(m_data);

		// 添加私有final字段data
		JCMethodInvocation init_value;
		if (parent.isEmpty()) {
			addImport(settings, CL_MySettings);
			init_value = mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Select(mMaker.Ident(CL_MySettings.tsym),
				names.fromString("SETTINGS")), names.fromString("child")),
			 List.of(mMaker.Literal(literal_name)));
		} else {
			// addImport(settings, findType(parent));
			// Symbol symbol = settings.owner.members().findFirst(names.fromString(parent), t -> t.kind == Kind.TYP);
			init_value = mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Select(mMaker.Ident(names.fromString(parent)),
				names.fromString("data")), names.fromString("child")),
			 List.of(mMaker.Literal(literal_name)));
		}
		addConstantField(settingsTree, CL_Data, "data", init_value);

		// 在初始化中，给type字段赋值
		settingsTree.defs.stream()
		 .filter(t -> t instanceof JCMethodDecl m1 && m1.name.toString().equals("<init>") && m1.params.size() == 1)
		 .findFirst().ifPresent(t -> {
			 // 添加type()方法
			 JCBlock body2 = mMaker.Block(0, List.of(mMaker.Return(mMaker.Select(mMaker.This(settings.type), names.fromString("type")))));
			 JCMethodDecl m_type = mMaker.MethodDef(mMaker.Modifiers(Flags.PUBLIC | Flags.FINAL), names.fromString("type"), mMaker.Ident(mSymtab.classType.tsym),
				List.nil(), List.nil(), List.nil(),
				body2, null);
			 settingsTree.defs = settingsTree.defs.append(m_type);
			 // 添加type字段，初始化从初始化参数里获取
			 addField(settingsTree, Flags.PRIVATE, mSymtab.classType, "type", null).vartype = mMaker.Ident(mSymtab.classType.tsym);
			 // 给type字段赋值为第一个参数
			 JCMethodDecl init = (JCMethodDecl) t;
			 init.body.stats = init.body.stats.prepend(
				mMaker.Exec(mMaker.Assign(mMaker.Select(mMaker.This(settings.type), names.fromString("type")), mMaker.Ident(init.params.get(0).name)))
			 );
		 });
		// print(trees.getPath(settings).getCompilationUnit());
	}
	public void process() {
	}

	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(ContentInit.class.getCanonicalName(), SettingsInit.class.getCanonicalName());
	}
}
