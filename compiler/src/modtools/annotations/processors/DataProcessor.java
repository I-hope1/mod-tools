package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.*;

@AutoService({Processor.class})
// @SupportedOptions({"debug", "verify"})
public class DataProcessor extends BaseProcessor<Element> {
	private static final String EVENT       = "modtools.events.MyEvents";
	private static final String EVNET_FIELD = "$event-0";
	Type TY_Event;
	Symbol EVENT_INIT;
	public void init() throws Throwable {
		TY_Event = findType(EVENT);
		EVENT_INIT = TY_Event.tsym.members().findFirst(names.init);
	}
	public void process() {
		initMap.forEach((parent, selves) -> {
			/* if (!parent.getSimpleName().toString().startsWith("E_"))
				throw new IllegalArgumentException("class name must start with 'E_'"); */
			var classDecl = (JCClassDecl) trees.getTree(parent);

			JCVariableDecl event_f = addField0(
			 classDecl,
			 Flags.PRIVATE | Flags.FINAL,
			 TY_Event, EVNET_FIELD,
			 mMaker.Create(EVENT_INIT, List.nil()));

			// classDecl.sym.members_field.enter(findSymbol(EVNET_FIELD));
			JCMethodDecl method = findChild(classDecl, Tag.METHODDEF, m0 -> m0.name.contentEquals("dataInit"));
			if (method == null) return;

			ListBuffer<JCStatement> buffer = new ListBuffer<>();
			selves.forEach(self -> {
				String fieldName     = self.getSimpleName() + "";
				String underlineName = getUnderlineName(fieldName).toString();

				mMaker.at(method);
				JCVariableDecl t = makeVar0(Flags.PARAMETER, null, "t", null, method.sym);
				t.startPos = 0;
				// Generate: %event%.onIns(%ECL%.%prop%, t->%field%=t.enabled());
				JCStatement x = execStatement(
				 mMaker.Select(mMaker.Ident(event_f), ns("onIns")),
				 List.of(
					mMaker.Select(mMaker.Ident(ns("Settings")), ns(underlineName)),
					mMaker.Lambda(
					 List.of(t),
					 mMaker.Assign(mMaker.Ident(ns(fieldName)),
						mMaker.Apply(List.nil(),
						 mMaker.Select(mMaker.Ident(t),
							ns("enabled")),
						 List.nil()))
					 /* parseExpression(fieldName + "=t.enabled()") */
					)
				 ));
				/* x.accept(new TreeTranslator() {
					public void visitLambda(JCLambda tree) {
						// Log.info(tree.target = findType("arc.func.Cons"));
						super.visitLambda(tree);
					}
				}); */

				buffer.add(x);
			});
			method.body.stats = buffer.toList();
			// Log.info(method);
			// Log.info(classDecl);
		});
	}
	public Map<Element, java.util.List<Element>> initMap = new HashMap<>();

	public void dealElement(Element element) {
		if (element.getKind() == ElementKind.FIELD)
			initMap.computeIfAbsent(element.getEnclosingElement(), k -> new ArrayList<>()).add(element);
	}

	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(DataEventFieldInit.class);
	}
}
