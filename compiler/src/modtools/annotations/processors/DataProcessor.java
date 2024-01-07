package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.*;

@AutoService({Processor.class})
// @SupportedOptions({"debug", "verify"})
public class DataProcessor extends BaseProcessor {
	private static final String EVENT       = "modtools.events.MyEvents";
	private static final String EVNET_FIELD = "$event-0";
	public void process2() {
		initMap.forEach((parent, selves) -> {
			/* if (!parent.getSimpleName().toString().startsWith("E_"))
				throw new IllegalArgumentException("class name must start with 'E_'"); */
			var classDecl = (JCClassDecl) trees.getTree(parent);

			JCVariableDecl event_f = addField(
			 classDecl,
			 Flags.PRIVATE | Flags.FINAL,
			 findType(EVENT),
			 EVNET_FIELD, "new " + EVENT + "()");

			String E_NAME = "modtools.events.E_" + parent.getSimpleName();
			// classDecl.sym.members_field.enter(findSymbol(EVNET_FIELD));
			JCMethodDecl method = findChild(classDecl, Tag.METHODDEF, m0 -> m0.name.contentEquals("dataInit"));
			if (method == null) return;

			ListBuffer<JCStatement> buffer = new ListBuffer<>();
			selves.forEach(self -> {
				String fieldName     = self.getSimpleName() + "";
				String underlineName = getUnderlineName(fieldName).toString();
				/* classDecl.defs = classDecl.defs.append(maker.MethodDef(
				 maker.Modifiers(Flags.PUBLIC), //访问标志
				 names.fromString("$$"+fieldName), //名字
				 maker.TypeIdent(TypeTag.VOID), //返回类型
				 List.nil(), //泛型形参列表
				 List.of(maker.VarDef(maker.Modifiers(Flags.PARAMETER), // 访问标识
                names.fromString("e"), // 名称
                maker.Type(findType(E_NAME)), // 类型
                null)), //参数列表
				 List.nil(), //异常列表
				 parseBlock("{"+fieldName+"=e.enabled();}"), //方法体
				 null //默认方法（可能是interface中的那个default）
				)); */

				mMaker.at(method);
				JCVariableDecl t = makeVar0(Flags.PARAMETER, null, "t", null, method.sym);
				t.startPos = 0;
				// Generate: %event%.onIns(%ECL%.%prop%, t->%field%=t.enabled());
				JCStatement x = execStatement(
				 mMaker.Select(mMaker.Ident(event_f), names.fromString("onIns")),
				 List.of(
					mMaker.Select(mMaker.Ident(findClassSymbol(E_NAME)), names.fromString(underlineName)),
					mMaker.Lambda(
					 List.of(t),
					 mMaker.Assign(mMaker.Ident(names.fromString(fieldName)),
						mMaker.Apply(List.nil(),
						 mMaker.Select(mMaker.Ident(t),
							names.fromString("enabled")),
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
		if (element.getKind() == ElementKind.FIELD) initMap.computeIfAbsent(element.getEnclosingElement(), k -> new ArrayList<>()).add(element);
		else if (element.getKind() == ElementKind.ENUM) {
			JCVariableDecl data = (JCVariableDecl) trees.getTree(findChild(element, "data", ElementKind.FIELD));
			data.init = parseExpression("modtools.utils.MySettings.SETTINGS.child(\"%name%\")"
			 .replace("%name%", element.getSimpleName().toString().substring(2).toLowerCase())
			);
		}
	}

	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(DataEventFieldInit.class.getCanonicalName(), DataObjectInit.class.getCanonicalName());
	}
}
