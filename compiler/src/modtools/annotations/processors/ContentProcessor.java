package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.Set;

/** 添加new XXX()，并给对应Content的Settings（如果有）初始化  */
@AutoService({Processor.class})
public class ContentProcessor extends BaseProcessor {
	public void dealElement(Element element) {
		JCMethodDecl            m    = trees.getTree((ExecutableElement) findChild(element, "load", ElementKind.METHOD));
		ListBuffer<JCStatement> list = new ListBuffer<>();

		Name      Name_Setting  = names.fromString("Settings");
		ClassType CL_MySettings = findType("modtools.utils.MySettings");
		ClassType CL_Data       = findType("modtools.utils.MySettings$Data");


		for (Element field : findAllChild(element, null, ElementKind.FIELD)) {
			JCVariableDecl tree = (JCVariableDecl) trees.getTree(field);
			list.add(parseStatement(field.getSimpleName()
															+ "=new " + tree.vartype + "();"));

			TypeSymbol TP_Content = tree.vartype.type.tsym;
			// 获取TP_Content的初始化的name
			JCMethodDecl init_method = (JCMethodDecl) trees.getTree(TP_Content.members().findFirst(names.fromString("<init>")));
			Object       C_name      = ((JCLiteral) ((JCMethodInvocation) ((JCExpressionStatement) init_method.body.stats.get(0)).expr).args.get(0)).value;

			ClassSymbol settings = (ClassSymbol) TP_Content.members().findFirst(Name_Setting, t -> t.kind == Kind.TYP);
			if (settings == null) continue;
			// addImport(settings, CL_MySettings);
			JCClassDecl settingsTree = trees.getTree(settings);
			// 给Settings添加方法data()和私有final字段data
			// 添加data()方法
			JCBlock body = mMaker.Block(0, List.of(mMaker.Return(mMaker.Ident(names.fromString("data")))));
			JCMethodDecl m_data = mMaker.MethodDef(mMaker.Modifiers(Flags.PUBLIC | Flags.FINAL), names.fromString("data"), mMaker.TypeApply(mMaker.Ident(CL_Data.tsym), List.nil()),
			 List.nil(), List.nil(), List.nil(),
			 body, null);
			settingsTree.defs = settingsTree.defs.append(m_data);
			// 添加私有final字段data
			addConstantField(settingsTree, CL_Data, "data",
			 mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Select(mMaker.Ident(CL_MySettings.tsym),
				 names.fromString("SETTINGS")), names.fromString("child")),
			 List.of(mMaker.Literal(C_name))));

			// System.out.println(settingsTree);
		}
		m.body.stats = list.toList();
		// Log.info(m);
	}
	public void process() {
	}

	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(ContentInit.class.getCanonicalName());
	}
}
