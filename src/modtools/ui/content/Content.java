
package modtools.ui.content;

import arc.Core;
import arc.func.Boolp;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.util.Reflect;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.ui.HopeStyles;
import modtools.ui.comp.buttons.CircleImageButton;
import modtools.utils.MySettings.Data;
import modtools.utils.Tools;

import java.util.ArrayList;

import static modtools.IntVars.modName;
import static modtools.utils.MySettings.SETTINGS;

public abstract class Content {
	public static final ArrayList<Content> all = new ArrayList<>();

	public final Drawable icon;
	public final String   name;
	public       Runnable update;

	/** 显示名称 */
	public String localizedName() {
		return Core.bundle.get(modName + "." + name, name);
	}
	public final String getSettingName() {
		return name;
	}

	protected boolean defLoadable = true;
	public final boolean loadable() {
		return SETTINGS.getBool("load-" + name, defLoadable);
	}
	public Content(String name) {
		this(name, null);
	}
	public Content(String name, Drawable icon) {
		this.name = name;
		if (icon == null) try {
			icon = Reflect.get(Icon.class, name + "Small");
		} catch (Throwable ignored) {
			icon = Styles.none;
		}
		this.icon = icon;
		all.add(this);
	}

	public final void loadSettings() {
		loadSettings(data());
	}
	public void loadSettings(Data SETTINGS) {
	}

	private Data data;
	/** 设置 */
	public final Data data() {
		return data == null ? data = SETTINGS.child(getSettingName()) : data;
	}
	/** 加载  */
	public void load() {
	}

	/** 点击按钮触发的事件 */
	public void build() {
	}

	public String toString() {
		return "Content#" + name;
	}

	public Button buildButton(boolean isSmallized) {
		return buildButton(isSmallized, null);
	}
	public final Button buildButton(boolean isSmallized, Boolp checked) {
		Button btn = makeButton(isSmallized,
		 checked == null ?
			/* un-toggle */isSmallized ? HopeStyles.hope_flati : HopeStyles.cleart
			:/* toggle */ isSmallized ? HopeStyles.hope_flatTogglei : HopeStyles.flatTogglet);
		if (checked != null) {
			btn.update(() -> btn.setChecked(checked.get()));
		}
		return btn;
	}
	private Button makeButton(boolean isSmallized, ButtonStyle style) {
		if (isSmallized) {
			Button btn = new CircleImageButton(icon, (ImageButtonStyle) style);
			btn.setTransform(true);
			return btn;
		}
		String     localizedName = localizedName();
		TextButton button        = new TextButton(localizedName, (TextButtonStyle) style);
		button.add(new Image(icon)).size(icon == Styles.none ? 0 : 20);
		button.getCells().reverse();
		button.clicked(() -> Tools.runShowedException(this::build));
		button.update(() -> button.setColor(button.isDisabled() ? Color.gray : Color.white));
		button.getLabel().setSize(0.9f);
		return button;
	}
}
