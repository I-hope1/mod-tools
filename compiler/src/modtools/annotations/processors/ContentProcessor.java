package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.ListBuffer;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.Set;

@AutoService({Processor.class})
public class ContentProcessor extends BaseProcessor {
	public void dealElement(Element element) {
		JCMethodDecl            m    = (JCMethodDecl) trees.getTree(findChild(element, "load", ElementKind.METHOD));
		ListBuffer<JCStatement> list = new ListBuffer<>();
		for (Element field : findAllChild(element, null, ElementKind.FIELD)) {
			JCVariableDecl tree = (JCVariableDecl) trees.getTree(field);
			list.add(parseStatement(field.getSimpleName()
															+ "=new " + tree.vartype + "();"));
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
