package modtools.annotations.processors.fieldinit;

import com.google.auto.service.AutoService;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;
import modtools.annotations.builder.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.*;

@AutoService({Processor.class})
public class BoolProcessor extends BaseProcessor<Element> implements DataUtils {
	public Name selector;
	/* method: settingColor */
	Map<String, Symbol>     map     = new HashMap<>();
	java.util.List<Element> methods = new ArrayList<>();

	public void dealElement(Element element) {
		if (element.getKind() == ElementKind.METHOD) {
			methods.add(element);
			return;
		}

		if (element.getKind() == ElementKind.FIELD) {
			JCClassDecl       decl  = trees.getTree((TypeElement) element.getEnclosingElement());
			DataBoolFieldInit anno  = element.getAnnotation(DataBoolFieldInit.class);
			JCVariableDecl    field = (JCVariableDecl) trees.getTree(element);
			field.sym.complete();
			map.put(element.getSimpleName() + "", field.sym);
			String key = field.name.toString();
			if (selector == null) selector = ns("getBool");
			field.init = mMaker.Apply(
			 List.nil(),
			 mMaker.Select(getData(anno.data(), decl.sym.type), selector),
			 List.of(
				mMaker.Literal(key),
				field.init != null ? field.init : mMaker.Literal(false)
			 )
			);
		}
	}
	public void process() {
		methods.forEach(this::process);
	}
	public void process(Element element) {
		DataBoolSetting         anno         = element.getAnnotation(DataBoolSetting.class);
		JCMethodDecl            method       = (JCMethodDecl) trees.getTree(element);
		JCVariableDecl          variableDecl = (JCVariableDecl) method.body.stats.get(0);
		JCNewArray              rhs          = (JCNewArray) variableDecl.init;
		ListBuffer<JCStatement> buffer       = new ListBuffer<>();
		for (JCExpression node : rhs.elems) {
			if (!(node instanceof JCFieldAccess || node instanceof JCIdent))
				throw new IllegalArgumentException("Illegal expression: " + node + "(" + node.getClass() + ")");
			String name = (node instanceof JCFieldAccess access ? access.name : ((JCIdent) node).name) + "";

			CompilationUnitTree unit   = trees.getPath(element).getCompilationUnit();
			TreePath            path   = trees.getPath(unit, node);
			Symbol              symbol = trees.getElement(path);

			DataBoolFieldInit fieldInit = symbol.getAnnotation(DataBoolFieldInit.class);

			mMaker.at(method);
			JCVariableDecl param = makeVar0(Flags.PARAMETER, null, "_p", null, method.sym);
			param.startPos = 0;
			JCExpression data = getData(fieldInit.data(), ((ClassSymbol) element.getEnclosingElement()).type);
			JCLiteral    key  = mMaker.Literal(name);
			// 下面这个代码是：SettingUI.bool("settingName", data, "key", node, _p -> node = _p)
			buffer.add(mMaker.Exec(mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Ident(SettingUI()),
				ns("bool")),
			 List.of(
				mMaker.Ident(method.params.get(0))/* 参数t */,
				mMaker.Literal(anno.prefix() + name.toLowerCase()),
				data,
				key,
				node,
				mMaker.Lambda(
				 List.of(param),
				 mMaker.Apply(List.nil(),
					mMaker.Select(data, ns("put")),
					List.of(
					 key,
					 mMaker.Assign(node, mMaker.Ident(param))
					)
				 )
				 /* parseExpression(fieldName + "=t.enabled()") */
				)
			 )
			)));
		}
		method.body.stats = buffer.toList();
		// Log.info(method);
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(DataBoolFieldInit.class.getCanonicalName(),
		 DataBoolSetting.class.getCanonicalName());
	}
}
