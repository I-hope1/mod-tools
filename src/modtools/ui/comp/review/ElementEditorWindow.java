package modtools.ui.comp.review;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import mindustry.graphics.Pal;
import modtools.content.ui.ReviewElement;
import modtools.ui.HopeStyles;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.IDisposable;
import modtools.ui.comp.input.area.AutoTextField;
import modtools.utils.*;
import modtools.utils.ui.FormatHelper;

import static modtools.ui.IntUI.*;

/**
 * 元素属性编辑器：把 {@link Element} 上那些「改一下就能看出效果」的字段暴露出来，
 * 排查布局时不用改代码重编译。
 * <p>位置/尺寸只是临时覆盖——父级是 {@link Table} 时，下一次 layout 会把它们算回去；
 * 这是布局的正常行为，不是编辑失败。
 */
public class ElementEditorWindow extends Window implements IDisposable {
	private final Element element;

	// 打开时的快照，供 Reset 使用
	private final float   x, y, width, height, rotation, scaleX, scaleY;
	private final boolean visible;
	private final Touchable touchable;

	public ElementEditorWindow(Element element) {
		super("elementEditor", 280, 0);
		this.element = element;

		x = element.x;
		y = element.y;
		width = element.getWidth();
		height = element.getHeight();
		rotation = element.rotation;
		scaleX = element.scaleX;
		scaleY = element.scaleY;
		visible = element.visible;
		touchable = element.touchable;

		title.setText("Edit: " + ReviewElement.getElementName(element));
		build();
	}

	private void build() {
		cont.left().defaults().left().pad(2f);

		cont.table(t -> {
			t.defaults().left().pad(2f);
			numberRow(t, "X", () -> FormatHelper.fixed(element.x),
			 v -> element.setPosition(v, element.y));
			numberRow(t, "Y", () -> FormatHelper.fixed(element.y),
			 v -> element.setPosition(element.x, v));
			t.row();
			numberRow(t, "Width", () -> FormatHelper.fixed(element.getWidth()),
			 v -> element.setSize(v, element.getHeight()));
			numberRow(t, "Height", () -> FormatHelper.fixed(element.getHeight()),
			 v -> element.setSize(element.getWidth(), v));
			t.row();
			numberRow(t, "Rotation", () -> FormatHelper.fixed(element.rotation),
			 v -> element.rotation = v);
			t.row();
			numberRow(t, "ScaleX", () -> FormatHelper.fixed(element.scaleX),
			 v -> element.scaleX = v);
			numberRow(t, "ScaleY", () -> FormatHelper.fixed(element.scaleY),
			 v -> element.scaleY = v);
		}).growX().row();

		cont.check("Visible", element.visible, b -> {
			element.visible = b;
		}).left().row();

		cont.table(t -> {
			t.left().defaults().left();
			t.add("Touchable").fontScale(0.8f).color(Pal.accent).padRight(6f);
			TextButton[] btn = {null};
			btn[0] = t.button(element.touchable.name(), HopeStyles.flatBordert, () -> {
				element.touchable = switch (element.touchable) {
					case enabled -> Touchable.disabled;
					case disabled -> Touchable.childrenOnly;
					case childrenOnly -> Touchable.enabled;
				};
				btn[0].setText(element.touchable.name());
			}).growX().height(28f).get();
		}).growX().row();

		cont.table(t -> {
			t.left().defaults().left();
			t.add("Color").fontScale(0.8f).color(Pal.accent).padRight(6f);
			// ColorContainer 持有引用并逐帧刷新，直接指向元素自己的颜色即可跟随外部改动
			ColorContainer container = new ColorContainer(element.color);
			container.clicked(() -> colorPicker().show(element.color, c -> element.color.set(c)));
			t.add(container).size(18f).padRight(6f);
			t.add(new Label(() -> FormatHelper.color(element.color))).fontScale(0.8f).color(Color.lightGray).growX();
		}).growX().row();

		cont.table(t -> {
			t.button("Reset", HopeStyles.flatBordert, this::resetValues).growX().height(30f);
			t.button("Copy Path", HopeStyles.flatBordert,
				() -> JSFunc.copyText(ElementUtils.getDisplayPath(element), element)).growX().height(30f);
		}).growX().padTop(6f).row();
	}

	/** 恢复打开时的快照。位置/尺寸若受父级布局管理，会在下次 layout 时再次被覆盖 */
	private void resetValues() {
		element.setBounds(x, y, width, height);
		element.rotation = rotation;
		element.scaleX = scaleX;
		element.scaleY = scaleY;
		element.visible = visible;
		element.touchable = touchable;
		element.invalidate();
	}

	/**
	 * 一行「键 + 可点击编辑的数值」。
	 * <p>刻意不复用 {@link ReviewElement#floatSetter}：那个每帧都会把输入框里的值写回目标，
	 * 用在 x/y/width 上会与父级布局互相覆盖，永远拉不回来。
	 */
	private void numberRow(Table t, String key, Prov<CharSequence> value, Floatc apply) {
		t.add(key).fontScale(0.8f).color(Pal.accent).padRight(6f);

		Label label = new Label(value);
		label.setFontScale(0.85f);
		Cell<?> cell = t.add(label).growX().right();

		TextField field = new AutoTextField();
		field.setValidator(NumberHelper::isFloat);

		Runnable commit = () -> {
			if (cell.get() != field) return;
			cell.setElement(label);
			if (!field.isValid()) return;
			float v = NumberHelper.asFloat(field.getText());
			Tools.runShowedException(() -> {
				apply.get(v);
				// 只让元素自己重排：invalidateHierarchy 会连带父级 layout，把刚改的值算回去
				element.invalidate();
			});
		};

		label.clicked(() -> {
			field.setText(label.getText().toString());
			cell.setElement(field);
			Core.scene.setKeyboardFocus(field);
			field.setCursorPosition(field.getText().length());
		});
		field.keyDown(code -> {
			if (code == KeyCode.enter) {
				Core.scene.setKeyboardFocus(null);
				commit.run();
			}
		});
		// 失焦即提交：点别处/关窗口都不该丢输入
		field.update(() -> {
			if (cell.get() == field && Core.scene.getKeyboardFocus() != field) commit.run();
		});
	}
}
