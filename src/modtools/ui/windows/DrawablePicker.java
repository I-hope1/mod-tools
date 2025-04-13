package modtools.ui.windows;

import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.content.ui.ShowUIList;
import modtools.ui.*;
import modtools.ui.IntUI.*;
import modtools.ui.comp.Window;
import modtools.ui.comp.utils.MyItemSelection;
import modtools.ui.gen.HopeIcons;
import modtools.ui.style.*;
import modtools.utils.*;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.ui.FormatHelper;

import static ihope_lib.MyReflect.unsafe;
import static modtools.ui.HopeStyles.hope_defaultSlider;
import static modtools.ui.windows.ColorPicker.*;

public class DrawablePicker extends Window implements IHitter, PopupWindow {
	public static final TextureRegionDrawable PIN_ICON = Icon.cancelSmall;
	private             Drawable              drawable;

	private Cons<Drawable> cons = _ -> { };

	Color iconCurrent  = new Color(Color.white),
	 backgroundCurrent = new Color(Color.black).a(0.6f); /* black6 */
	boolean        isIconColor = true;
	DelegatorColor current     = new DelegatorColor();
	float          h, s, v, a;
	TextField hexField;
	Slider    hSlider, aSlider;

	public DrawablePicker() {
		super("@pickdrawable", 0, 0, false, false);
		background(null);

		cont.background(new TintDrawable(IntUI.whiteui, backgroundCurrent));
		sclListener.remove();
	}

	/**
	 * 显示DrawablePicker窗口，并设置初始的Drawable和回调函数。
	 * @param drawable 初始的Drawable
	 * @param consumer 选择Drawable后的回调函数
	 */
	public void show(Drawable drawable, Cons<Drawable> consumer) {
		show(drawable, true, consumer);
	}

	/**
	 * 显示DrawablePicker窗口，并设置初始的Drawable、是否包含透明度以及回调函数。
	 * @param drawable0 初始的Drawable
	 * @param hasAlpha 是否包含透明度
	 * @param consumer 选择Drawable后的回调函数
	 */
	public void show(Drawable drawable0, boolean hasAlpha, Cons<Drawable> consumer) {
		this.current.set(color);
		this.cons = consumer;
		show();

		isIconColor = true;
		Color sourceColor = new Color(iconCurrent.set(getTint(drawable0)));
		boolean isDelegate = drawable0 instanceof DelegatingDrawable;
		drawable = sourceColor.equals(Color.white) && !isDelegate ? drawable0 : cloneDrawable(drawable0);

		resetColor(sourceColor);

		cont.clear();
		cont.add(newTable(t -> {
			t.table(Styles.black6, wrap -> {
				Seq<Drawable> drawables = Icon.icons.values().toSeq()
				 .as().addAll(ShowUIList.styleIconKeyMap.keySet())
				 .addAll(ShowUIList.texKeyMap.keySet()).as();
				drawables.addUnique(drawable);
				MyItemSelection.buildTable0(wrap, drawables,
				 () -> drawable, drawable -> this.drawable = drawable, 10,
				 d -> d);
			}).grow().padLeft(4).height(256).colspan(2).row();

			t.table(fn -> {
				fn.defaults().uniformY();
				fn.table(Styles.black6, buttons -> {
					buttons.left().defaults().padLeft(4f).padRight(4f);
					buttons.label(() -> CatchSR.apply(() ->
					 CatchSR.of(() -> FormatHelper.getUIKey(drawable))
						.get(() -> "" + drawable)
					)).fontScale(0.6f).growX().labelAlign(Align.left).row();
					buttons.left().defaults().growX().height(32);
					buttons.button("Icon", Styles.fullTogglet, () -> { }).row();
					buttons.button("Background", Styles.fullTogglet, () -> { }).row();

					Seq<TextButton>         allButtons = buttons.getChildren().select(el -> el instanceof TextButton).as();
					ButtonGroup<TextButton> group      = new ButtonGroup<>();
					allButtons.each(b -> {
						b.getLabelCell().labelAlign(Align.left).padLeft(4f).padRight(4f);
						group.add(b);
					});

					buttons.update(() -> {
						if (isIconColor == (group.getChecked().getZIndex() == 1)) return;
						isIconColor = group.getChecked().getZIndex() == 1;
						resetColor(current);
					});
				}).padRight(6f).width(140f);
				fn.add(new Element() {
					 public void draw() {
						 float first  = Tmp.c1.fromHsv(h, 0, 1).a(parentAlpha).toFloatBits();
						 float second = Tmp.c2.fromHsv(h, 1, 1).a(parentAlpha).toFloatBits();

						 Fill.quad(
							x, y, Tmp.c1.value(0).toFloatBits(),/* 左下角 */
							x + width, y, Tmp.c2.value(0).toFloatBits(),/* 有下角 */
							x + width, y + height, second,/* 有上角 */
							x, y + height, first/* 左上角 */
						 );

						 Draw.color(Color.white);
						 int radius = 3;
						 PIN_ICON.draw(x + s * width, y + v * height,
							radius * Scl.scl(), radius * Scl.scl());
						 Draw.color(Color.lightGray);
						 PIN_ICON.draw(x + s * width, y + v * height,
							(radius - 1) * Scl.scl(), (radius - 1) * Scl.scl());
					 }
				 }).grow().marginBottom(6f)
				 .with(l -> l.addListener(new InputListener() {
					 public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
						 apply(x, y);
						 return true;
					 }
					 public void touchDragged(InputEvent event, float x, float y, int pointer) {
						 apply(x, y);
					 }
					 private void apply(float x, float y) {
						 s = x / l.getWidth();
						 v = y / l.getHeight();
						 updateColor();
					 }
				 }));
			}).colspan(2).growX().row();

			t.table(icon -> {
				icon.add(new Element() {
					public void draw() {
						float alpha = iconCurrent.a * parentAlpha;
						Draw.color(iconCurrent, alpha);
						// 绘制所选的图标
						if (drawable != null) {
							drawable.draw(x, y, width, height);
						}
					}
				}).size(42).expandX().left();
				icon.add(new Element() {
					public void draw() {
						Draw.color();
						HopeIcons.alphaBgCircle.draw(x, y, width, height);
						float x      = getX(Align.center);
						float y      = getY(Align.center);
						float radius = width / 2;
						float alpha  = iconCurrent.a * parentAlpha;

						Draw.color(Tmp.c1.set(iconCurrent).inv(), alpha);
						Fill.circle(x, y, radius);
						Draw.color(iconCurrent, alpha);
						Fill.circle(x, y, radius - 2);
					}
				}).size(42);

				icon.row();

				hexField = icon.field(current.toString().toUpperCase(), Tools.consT(value -> {
					 current.set(Color.valueOf(value).a(a));
					 resetColor(current);

					 hSlider.setValue(h);
					 if (aSlider != null) {
						 aSlider.setValue(a);
					 }

					 updateColor(false);
				 }))
				 .colspan(2)
				 .size(130f, 40f)
				 .valid(ColorPicker::isValidColor).get();
			}).padTop(4f);

			t.table(slider -> {
				slider.defaults().grow();
				slider.stack(new Image(new TextureRegion(hueTex.get())), hSlider = new Slider(0f, 360f, 0.3f, false, hope_defaultSlider) {{
					setValue(h);
					moved(value -> {
						h = value;
						updateColor();
					});
				}}).growY().row();

				if (hasAlpha) {
					slider.stack(new Image(HopeTex.alphaBgLine), new Element() {
						@Override
						public void draw() {
							float first  = Tmp.c1.set(current.delegator()).a(0f).toFloatBits();
							float second = Tmp.c1.set(current.delegator()).a(parentAlpha).toFloatBits();

							Fill.quad(
							 x, y, first,
							 x + width, y, second,
							 x + width, y + height, second,
							 x, y + height, first
							);
						}
					}, aSlider = new Slider(0f, 1f, 0.001f, false, hope_defaultSlider) {{
						setValue(a);
						moved(value -> {
							a = value;
							updateColor();
						});
					}}).row();
				}
			}).pad(4f).growX();
		})).grow();

		buttons.clear();
		buttons.margin(4f).defaults().growX().height(32);
		buttons.button("@cancel", Icon.cancel, HopeStyles.flatt, this::hide)
		 .marginLeft(4f).marginRight(4f);
		buttons.button("@ok", Icon.ok, HopeStyles.flatt, () -> {
			 cons.get(iconCurrent.equals(sourceColor) && !(drawable0 instanceof DelegatingDrawable) ?
				drawable : new DelegatingDrawable(drawable, new Color(iconCurrent)));
			 hide();
		 })
		 .marginLeft(4f).marginRight(4f);
	}

	/**
	 * 复制drawable并设置{@code tint}为{@link Color#white}。
	 * @param drawable 要复制的Drawable
	 * @return 复制后的Drawable
	 */
	private Drawable cloneDrawable(Drawable drawable) {
		if (drawable == null) return null;
		if (drawable instanceof DelegatingDrawable d) return d.drawable;
		try {
			Drawable newDrawable = (Drawable) unsafe.allocateInstance(drawable.getClass());
			Tools.clone1(drawable, newDrawable, drawable.getClass(), f -> {
				if (!"tint".equals(f.getName()) || f.getType() != Color.class) return true;

				FieldUtils.setValue(f, newDrawable, Color.white);
				return false;
			});
			return newDrawable;
		} catch (Throwable e) {
			return new TextureRegionDrawable(IntUI.whiteui.getRegion());
		}
	}

	/**
	 * 获取Drawable的tint颜色。
	 * @param drawable 要获取tint颜色的Drawable
	 * @return Drawable的tint颜色
	 */
	private Color getTint(Drawable drawable) {
		if (drawable == null) return Color.white;
		if (drawable instanceof DelegatingDrawable d) return d.color;
		return CatchSR.apply(() -> CatchSR.of(
			() -> Reflect.get(TextureRegionDrawable.class, drawable, "tint"))
		 .get(() -> Reflect.get(NinePatchDrawable.class, drawable, "tint"))
		 .get(() -> Color.white)
		);
	}

	/**
	 * 重置颜色为指定颜色，并更新相关UI元素。
	 * @param color 要重置的颜色
	 */
	private void resetColor(Color color) {
		// if (color instanceof AutoColor d) color = d.delegetor();
		float[] values = color.toHsv(new float[3]);
		h = values[0];
		s = values[1];
		v = values[2];
		a = (current.rgba() & 0xFF) / 255f;

		// 更新元素
		if (hSlider != null && aSlider != null) {
			hSlider.setValue(h);
			aSlider.setValue(a);
		}
		updateHexText(true);
	}

	/**
	 * 更新颜色并更新相关UI元素。
	 */
	void updateColor() {
		updateColor(true);
	}

	/**
	 * 更新颜色并更新相关UI元素。
	 * @param updateField 是否更新颜色字段
	 */
	void updateColor(boolean updateField) {
		h = Mathf.clamp(h, 0, 360);
		s = Mathf.clamp(s);
		v = Mathf.clamp(v);
		current.fromHsv(h, s, v);
		current.a(a);

		updateHexText(updateField);
	}

	/**
	 * 更新颜色字段的文本。
	 * @param updateField 是否更新颜色字段
	 */
	private void updateHexText(boolean updateField) {
		if (hexField != null && updateField) {
			String val = current.toString().toUpperCase();
			if ((current.rgba() & 0xFF) >= 0.9999f) {
				val = val.substring(0, 6);
			}
			hexField.setText(val);
		}
	}

	/**
	 * 内部类，用于在颜色选择器中代理颜色操作。
	 */
	private class DelegatorColor extends Color {
		/**
		 * 获取代理的颜色对象。
		 * @return 代理的颜色对象
		 */
		public Color delegator() {
			return isIconColor ? iconCurrent : backgroundCurrent;
		}

		/**
		 * 设置代理的颜色。
		 * @param color 要设置的颜色
		 * @return 代理的颜色对象
		 */
		public Color set(Color color) {
			return delegator().set(color);
		}

		/**
		 * 从HSV值设置代理的颜色。
		 * @param h 色调
		 * @param s 饱和度
		 * @param v 亮度
		 * @return 代理的颜色对象
		 */
		public Color fromHsv(float h, float s, float v) {
			return delegator().fromHsv(h, s, v);
		}

		/**
		 * 将代理的颜色转换为HSV值。
		 * @param hsv 用于存储HSV值的数组
		 * @return 包含HSV值的数组
		 */
		public float[] toHsv(float[] hsv) {
			return delegator().toHsv(hsv);
		}

		/**
		 * 返回代理的颜色的字符串表示。
		 * @return 颜色的字符串表示
		 */
		public String toString() {
			return delegator().toString();
		}

		/**
		 * 设置代理的颜色的透明度。
		 * @param a 透明度
		 * @return 代理的颜色对象
		 */
		public Color a(float a) {
			return delegator().a(a);
		}

		/**
		 * 返回代理的颜色的RGBA值。
		 * @return 颜色的RGBA值
		 */
		public int rgba() {
			return delegator().rgba();
		}
	}
}
