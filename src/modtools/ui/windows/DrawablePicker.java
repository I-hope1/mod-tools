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
import mindustry.gen.*;
import mindustry.ui.Styles;
import modtools.ui.*;
import modtools.ui.gen.HopeIcons;
import modtools.ui.IntUI.*;
import modtools.ui.components.Window;
import modtools.ui.components.utils.MyItemSelection;
import modtools.ui.style.TintDrawable;
import modtools.utils.SR.CatchSR;
import modtools.utils.Tools;
import modtools.utils.reflect.FieldUtils;

import java.util.Arrays;

import static ihope_lib.MyReflect.unsafe;
import static modtools.ui.HopeStyles.hope_defaultSlider;
import static modtools.ui.windows.ColorPicker.*;

public class DrawablePicker extends Window implements IHitter, PopupWindow {
	private Drawable drawable;

	private Cons<Drawable> cons = c -> {};
	Color iconCurrent  = new Color(Color.white),
	 backgroundCurrent = new Color(bgColor);
	boolean         isIconColor = true;
	DelegetingColor current     = new DelegetingColor();
	float           h, s, v, a;
	TextField hexField;
	Slider    hSlider, aSlider;

	public DrawablePicker() {
		super("@pickcolor", 0, 0, false, false);
		background(null);

		cont.background(new TintDrawable(IntUI.whiteui, backgroundCurrent));
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
			t.table(i -> i.add(new Element() {
				public void draw() {
					if (drawable == null) return;
					Draw.color(iconCurrent, iconCurrent.a * parentAlpha);
					drawable.draw(x, y, width, height);
				}
			}).grow());
			t.table(Styles.black6, wrap -> {
				Seq<Drawable> drawables = Icon.icons.values().toSeq().as().addAll(Arrays.stream(Styles.class.getFields())
				 .filter(f -> FieldUtils.isStatic(f) && f.getType() == Drawable.class)
				 .map(f -> (Drawable) FieldUtils.get(null, f)).toList()).as();
				drawables.addUnique(drawable);
				MyItemSelection.buildTable0(wrap, drawables,
				 () -> drawable, drawable -> this.drawable = drawable, 8,
				 d -> d);
			}).grow().row();

			t.table(Styles.black6, buttons -> {
				buttons.left().defaults().growX().height(32);
				buttons.check("Icon", b -> {}).row();
				buttons.check("Background", b -> {});
				buttons.getChildren().<CheckBox>as().each(b -> {
					b.left();
					b.setStyle(HopeStyles.hope_defaultCheck);
				});
				ButtonGroup<CheckBox> group = new ButtonGroup<>();
				group.add(buttons.getChildren().toArray(CheckBox.class));
				buttons.update(() -> {
					if (isIconColor == (group.getChecked().getZIndex() == 0)) return;
					isIconColor = group.getChecked().getZIndex() == 0;
					resetColor(current);
				});
			}).padLeft(4f).padRight(6f);
			t.add(new Element() {
				 public void draw() {
					 float first  = Tmp.c1.set(current.delegetor()).value(1).saturation(0f).a(parentAlpha).toFloatBits();
					 float second = Tmp.c2.set(current.delegetor()).value(1).saturation(1f).a(parentAlpha).toFloatBits();

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
			 }).growX().height(100).padBottom(6f)
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

			hexField = t.field(current.toString().toUpperCase(), Tools.catchCons(value -> {
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
						float first  = Tmp.c1.set(current.delegetor()).a(0f).toFloatBits();
						float second = Tmp.c1.set(current.delegetor()).a(parentAlpha).toFloatBits();

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
		buttons.margin(6, 8, 6, 8).defaults().growX().height(32);
		buttons.button("@cancel", Icon.cancel, HopeStyles.flatt, this::hide);
		buttons.button("@ok", Icon.ok, HopeStyles.flatt, () -> {
			cons.get(iconCurrent.equals(sourceColor) ? drawable : new WrapTextureRegionDrawable(drawable, new Color(iconCurrent)));
			hide();
		});
	}
	/** 复制drawable并设置{@code tint}为{@link Color#white}  */
	private Drawable cloneDrawable(Drawable drawable) {
		try {
			Drawable newDrawable = (Drawable) unsafe.allocateInstance(drawable.getClass());
			Tools.clone(drawable, newDrawable, drawable.getClass(), f -> {
				if (!"tint".equals(f.getName()) || f.getType() != Color.class) return true;

				FieldUtils.setValue(f, newDrawable, new Color());
				return false;
			});
			return newDrawable;
		} catch (Throwable e) {
			return new TextureRegionDrawable(IntUI.whiteui.getRegion());
		}
	}
	private Color getTint(Drawable drawable) {
		return CatchSR.apply(() -> CatchSR.of(
			() -> Reflect.get(TextureRegionDrawable.class, drawable, "tint"))
		 .get(() -> Reflect.get(NinePatchDrawable.class, drawable, "tint"))
		 .get(() -> Color.white)
		 .get()
		);
	}
	private void resetColor(Color color) {
		if (color instanceof DelegetingColor d) color = d.delegetor();
		float[] values = color.toHsv(new float[3]);
		h = values[0];
		s = values[1];
		v = values[2];
		a = color.a;
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
			if (current.a() >= 0.9999f) {
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
	private static class WrapTextureRegionDrawable implements Drawable {
		Drawable drawable;
		Color    color;
		public WrapTextureRegionDrawable(Drawable drawable, Color color) {
			this.drawable = drawable;
			this.color = color;
		}
		public void draw(float x, float y, float originX, float originY, float width, float height, float scaleX,
										 float scaleY, float rotation) {
			if (drawable == null) return;
			Draw.color(color);
			drawable.draw(x, y, originX, originY, width, height, scaleX, scaleY, rotation);
		}
		public float getLeftWidth() {
			return drawable.getLeftWidth();
		}
		public void setLeftWidth(float leftWidth) {
			drawable.setLeftWidth(leftWidth);
		}
		public float getRightWidth() {
			return drawable.getRightWidth();
		}
		public void setRightWidth(float rightWidth) {
			drawable.setRightWidth(rightWidth);
		}
		public float getTopHeight() {
			return drawable.getTopHeight();
		}
		public void setTopHeight(float topHeight) {
			drawable.setTopHeight(topHeight);
		}
		public float getBottomHeight() {
			return drawable.getBottomHeight();
		}
		public void setBottomHeight(float bottomHeight) {
			drawable.setBottomHeight(bottomHeight);
		}
		public float getMinWidth() {
			return drawable.getMinWidth();
		}
		public void setMinWidth(float minWidth) {
			drawable.setMinWidth(minWidth);
		}
		public float getMinHeight() {
			return drawable.getMinHeight();
		}
		public void setMinHeight(float minHeight) {
			drawable.setMinHeight(minHeight);
		}
		public void draw(float x, float y, float width, float height) {
			if (drawable == null) return;
			Draw.color(color);
			drawable.draw(x, y, width, height);
		}

		public String toString() {
			return drawable.toString() + "#" + color.toString();
		}
	}
	private class DelegetingColor extends Color {
		public Color delegetor() {
			return isIconColor ? iconCurrent : backgroundCurrent;
		}
		public Color set(Color color) {
			return delegetor().set(color);
		}
		public Color fromHsv(float h, float s, float v) {
			return delegetor().fromHsv(h, s, v);
		}
		public String toString() {
			return delegetor().toString();
		}
		public Color a(float a) {
			return delegetor().a(a);
		}
		public float a() {
			return delegetor().a;
		}
	}
}
