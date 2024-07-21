package modtools.ui.windows;

import arc.func.Cons;
import arc.graphics.*;
import arc.graphics.Texture.TextureFilter;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.ui.*;
import modtools.ui.IntUI.*;
import modtools.ui.comp.Window;
import modtools.ui.comp.utils.MyItemSelection;
import modtools.ui.content.SettingsUI.SettingsBuilder;
import modtools.ui.content.ui.ShowUIList;
import modtools.ui.gen.HopeIcons;
import modtools.ui.style.*;
import modtools.utils.SR.CatchSR;
import modtools.utils.*;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.ui.FormatHelper;

import static ihope_lib.MyReflect.unsafe;
import static modtools.ui.HopeStyles.hope_defaultSlider;
import static modtools.ui.windows.ColorPicker.*;

public class DrawablePicker extends Window implements IHitter, PopupWindow {
	private Drawable drawable;

	private Cons<Drawable> cons = _ -> { };

	Color iconCurrent  = new Color(Color.white),
	 backgroundCurrent = new Color(bgColor);
	boolean        isIconColor = true;
	DelegatorColor current     = new DelegatorColor();
	float          h, s, v, a;
	TextField hexField;
	Slider    hSlider, aSlider;

	public DrawablePicker() {
		super("@pickcolor", 0, 0, false, false);
		background(null);

		cont.background(new TintDrawable(IntUI.whiteui, backgroundCurrent));
		sclListener.remove();
	}

	public void show(Drawable drawable, Cons<Drawable> consumer) {
		show(drawable, true, consumer);
	}

	public void show(Drawable drawable0, boolean alpha, Cons<Drawable> consumer) {
		this.current.set(color);
		this.cons = consumer;
		show();

		if (hueTex == null) {
			hueTex = Pixmaps.hueTexture(128, 1);
			hueTex.setFilter(TextureFilter.linear);
		}

		isIconColor = true;
		drawable = cloneDrawable(drawable0);
		Color sourceColor = new Color(iconCurrent.set(getTint(drawable0)));
		resetColor(sourceColor);

		cont.clear();
		cont.pane(newTable(t -> {
			t.add(new Element() {
				public void draw() {
					if (drawable == null) return;
					Draw.color(iconCurrent, iconCurrent.a * parentAlpha);
					final float minWidth  = drawable.getMinWidth();
					final float minHeight = drawable.getMinHeight();
					switch (drawStyle) {
						case normal -> Tmp.v1.set(minWidth, minHeight);
						case large -> Tmp.v1.set(128, 128);
						case full -> Tmp.v1.set(width, height);
					}
					drawable.draw(x, y, Tmp.v1.x, Tmp.v1.y);
					// HopeStyles.setSize(drawable, minWidth, minHeight);
				}
			}).grow().pad(6, 8, 6, 8);
			t.table(Styles.black6, wrap -> {
				Seq<Drawable> drawables = Icon.icons.values().toSeq()
				 .as().addAll(ShowUIList.styleIconKeyMap.keySet())
				 .addAll(ShowUIList.texKeyMap.keySet()).as();
				drawables.addUnique(drawable);
				MyItemSelection.buildTable0(wrap, drawables,
				 () -> drawable, drawable -> this.drawable = drawable, 8,
				 d -> d);
			}).grow().padBottom(4f).row();

			t.table(Styles.black6, buttons -> {
				buttons.label(() -> CatchSR.apply(() ->
				 CatchSR.of(() -> FormatHelper.getUIKey(drawable))
					.get(() -> "" + drawable)
				)).fontScale(0.6f).row();
				buttons.left().defaults().growX().height(32).padRight(4f);
				buttons.check("Icon", _ -> { }).row();
				buttons.check("Background", _ -> { }).row();

				Seq<CheckBox> allButtons = buttons.getChildren().select(el -> el instanceof CheckBox).as();
				allButtons.each(b -> {
					b.left();
					b.setStyle(HopeStyles.hope_defaultCheck);
				});

				ButtonGroup<CheckBox> group = new ButtonGroup<>();
				group.add(allButtons.toArray(CheckBox.class));
				buttons.update(() -> {
					if (isIconColor == (group.getChecked().getZIndex() == 1)) return;
					isIconColor = group.getChecked().getZIndex() == 1;
					resetColor(current);
				});

				SettingsBuilder.main = buttons;
				Seq<DrawStyle> styles = new Seq<>(DrawStyle.values());
				TextButton     button = buttons.button(drawStyle.name(), HopeStyles.flatt, () -> { }).get();
				button.clicked(() -> {
					drawStyle = styles.get((styles.indexOf(drawStyle) + 1) % styles.size);
					button.setText(drawStyle.name());
				});
			}).padLeft(4f).padRight(6f);
			t.add(new Element() {
				 public void draw() {
					 float first  = Tmp.c1.fromHsv(h, 0, 1).a(parentAlpha).toFloatBits();
					 float second = Tmp.c2.fromHsv(h, 1, 1).a(parentAlpha).toFloatBits();

					 Fill.quad(
						x, y, Tmp.c1.value(0).toFloatBits(),/* 左下角 */
						x + width, y, Tmp.c2.value(0).toFloatBits(),/* 有下角 */
						x + width, y + height, second,/* 有上角 */
						x, y + height, first/* 左上角 */
					 );

					 Draw.color(Tmp.c1.fromHsv(h, s, v).inv());
					 Icon.cancelSmall.draw(x + s * width, y + v * height,
						5 * Scl.scl(), 5 * Scl.scl());
				 }
			 }).growX().growY().padBottom(6f)
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
			 }))
			 .row();

			t.defaults().width(260f).height(24f);

			t.add(new Element() {
				public void draw() {
					Draw.color();
					HopeIcons.alphaBgCircle.draw(x, y, width, height);
					float x      = getX(Align.center);
					float y      = getY(Align.center);
					float radius = width / 2;
					float alpha  = a * parentAlpha;

					Draw.color(Tmp.c1.set(iconCurrent).inv(), alpha);
					Fill.circle(x, y, radius);
					Draw.color(iconCurrent, alpha);
					Fill.circle(x, y, radius - 1);
				}
			}).size(42);

			t.stack(new Image(new TextureRegion(hueTex)), hSlider = new Slider(0f, 360f, 0.3f, false, hope_defaultSlider) {{
				setValue(h);
				moved(value -> {
					h = value;
					updateColor();
				});
			}}).row();

			hexField = t.field(current.toString().toUpperCase(), Tools.consT(value -> {
				current.set(Color.valueOf(value).a(a));
				resetColor(current);

				hSlider.setValue(h);
				if (aSlider != null) {
					aSlider.setValue(a);
				}

				updateColor(false);
			})).size(130f, 40f).valid(text -> {
				//garbage performance but who cares this runs only every key type anyway
				try {
					Color.valueOf(text);
					return true;
				} catch (Exception e) {
					return false;
				}
			}).get();

			if (alpha) {
				t.stack(new Image(HopeTex.alphaBgLine), new Element() {
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
	/** 复制drawable并设置{@code tint}为{@link Color#white} */
	private Drawable cloneDrawable(Drawable drawable) {
		if (drawable instanceof DelegatingDrawable d) return d.drawable;
		try {
			Drawable newDrawable = (Drawable) unsafe.allocateInstance(drawable.getClass());
			Tools.clone(drawable, newDrawable, drawable.getClass(), f -> {
				if (!"tint".equals(f.getName()) || f.getType() != Color.class) return true;

				FieldUtils.setValue(f, newDrawable, Color.white);
				return false;
			});
			return newDrawable;
		} catch (Throwable e) {
			return new TextureRegionDrawable(IntUI.whiteui.getRegion());
		}
	}
	private Color getTint(Drawable drawable) {
		if (drawable instanceof DelegatingDrawable d) return d.color;
		return CatchSR.apply(() -> CatchSR.of(
			() -> Reflect.get(TextureRegionDrawable.class, drawable, "tint"))
		 .get(() -> Reflect.get(NinePatchDrawable.class, drawable, "tint"))
		 .get(() -> Color.white)
		);
	}
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

	void updateColor() {
		updateColor(true);
	}

	void updateColor(boolean updateField) {
		h = Mathf.clamp(h, 0, 360);
		s = Mathf.clamp(s);
		v = Mathf.clamp(v);
		current.fromHsv(h, s, v);
		current.a(a);

		updateHexText(updateField);
	}
	private void updateHexText(boolean updateField) {
		if (hexField != null && updateField) {
			String val = current.toString().toUpperCase();
			if ((current.rgba() & 0xFF) >= 0.9999f) {
				val = val.substring(0, 6);
			}
			hexField.setText(val);
		}
	}
	/** a new table that stack() with clip */
	Table newTable(Cons<Table> cons) {
		return new Table(cons) {
			public Cell<Stack> stack(Element... elements) {
				Stack stack = new Stack() {
					protected void drawChildren() {
						clipBegin();
						super.drawChildren();
						clipEnd();
					}
				};
				if (elements != null) {
					for (Element element : elements) stack.addChild(element);
				}
				return add(stack);
			}
		};
	}
	/** 仅用于picker */
	private class DelegatorColor extends Color {
		public Color delegator() {
			return isIconColor ? iconCurrent : backgroundCurrent;
		}
		public Color set(Color color) {
			return delegator().set(color);
		}
		public Color fromHsv(float h, float s, float v) {
			return delegator().fromHsv(h, s, v);
		}
		public float[] toHsv(float[] hsv) {
			return delegator().toHsv(hsv);
		}
		public String toString() {
			return delegator().toString();
		}
		public Color a(float a) {
			return delegator().a(a);
		}
		public int rgba() {
			return delegator().rgba();
		}
	}

	enum DrawStyle {
		normal,
		large,
		full
	}
	DrawStyle drawStyle = DrawStyle.large;
}
