package modtools.ui.comp.utils;

import arc.struct.ObjectMap;
import arc.struct.ObjectMap.Entry;
import arc.util.OS;
import mindustry.gen.Icon;
import modtools.annotations.asm.Sample;
import modtools.content.debug.Tester;
import modtools.events.E_JSFunc;
import modtools.jsfunc.IScript;
import modtools.ui.HopeStyles;
import modtools.ui.comp.*;
import modtools.ui.comp.input.JSRequest;
import modtools.ui.comp.input.area.TextAreaTab;
import modtools.ui.comp.input.highlight.JSSyntax;
import modtools.utils.ByteCodeTools.MyClass;
import modtools.utils.Tools;
import modtools.utils.reflect.*;
import modtools.utils.search.*;
import rhino.*;

import java.lang.reflect.*;
import java.util.regex.Pattern;

public class ChangeClassDialog extends Window {
	public ValueLabel valueLabel;

	private final ObjectMap<Method, JSSyntax> syntaxes = new ObjectMap<>();
	private       Pattern                     pattern;
	public ChangeClassDialog(ValueLabel label) {
		super("Change Class");
		this.valueLabel = label;

		E_JSFunc.change_class_reference_when_edit.build(cont);
		cont.button("ok", Icon.ok, HopeStyles.flatBordert, () -> {
			var myClass = new MyClass<>(label.type, Sample.GEN_CLASS_NAME_SUFFIX);
			for (Entry<Method, JSSyntax> entry : syntaxes) {
				Method   m      = entry.key;
				JSSyntax syntax = entry.value;
				if (syntax.drawable.getText().isBlank()) continue;
				myClass.setFunc(m.getName(), null, Modifier.PUBLIC, false, m.getReturnType(), m.getParameterTypes());
				myClass.buildSuperFunc(
				 Sample.SUPER_METHOD_PREFIX + m.getName(),m.getName(), m.getReturnType(), m.getParameterTypes());
			}
			/* 先使类public化 */
			myClass = new MyClass<>(myClass.define(), Sample.GEN_CLASS_NAME_SUFFIX);
			for (Entry<Method, JSSyntax> entry : syntaxes) {
				Method   m      = entry.key;
				JSSyntax syntax = entry.value;
				if (syntax.drawable.getText().isBlank()) continue;
				Script script = syntax.compile();
				myClass.setFunc(m.getName(), (self, args) -> {
					return script.exec(IScript.cx, JSRequest.wrapper(Tester.wrap(self) instanceof Scriptable sc ? sc : null,
					 ClassUtils.wrapArgs(args.toArray())));
				}, Modifier.PUBLIC, m.getReturnType(), m.getParameterTypes());
			}
			if (OS.isAndroid && E_JSFunc.change_class_reference_when_edit.enabled()) {
				HopeReflect.changeClass(label.val, myClass.define());
			} else {
				label.setNewVal(Tools.newInstance(label.val, myClass.define()));
			}

			hide();
		}).size(120, 45).row();

		FilterTable<Method> wrapper = new FilterTable<>();
		wrapper.addPatternUpdateListener(() -> pattern);
		new Search<>((_, pattern) -> this.pattern = pattern).build(cont, wrapper);
		wrapper.left().defaults().left().growX();
		cont.pane(wrapper).grow();
		ClassUtils.walkNotStaticMethod(label.type, m -> {
			if (Modifier.isFinal(m.getModifiers())) return;

			wrapper.bind(m);
			wrapper.table(t -> {
				t.left().defaults().left();
				t.add("[accent]" + m.getName() + "[]" + ClassUtils.paramsToString(m.getParameterTypes()) + " -> " + m.getReturnType().getSimpleName()).row();
				TextAreaTab areaTab = new TextAreaTab("");
				t.add(areaTab).growX();
				JSSyntax syntax = new JSSyntax(areaTab,
				 JSRequest.wrapper(Tester.wrap(label.val) instanceof Scriptable sc ? sc : null,
					ClassUtils.paramsToArgs(m.getParameterTypes())));
				areaTab.syntax = syntax;
				syntaxes.put(m, syntax);
			}).growX().left().row();
			Underline.of(wrapper, 1);
			wrapper.unbind();
		});
	}
	public Window show() {
		return super.show();
	}
}
