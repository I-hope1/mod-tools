package modtools.ui.windows;

import arc.func.Cons;
import arc.graphics.*;
import arc.graphics.Texture.TextureFilter;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import modtools.ui.*;
import modtools.ui.IntUI.*;
import modtools.ui.comp.Window;
import modtools.ui.gen.HopeIcons;
import modtools.utils.Tools;

import static modtools.ui.HopeStyles.hope_defaultSlider;

public class ColorPicker extends Window implements IHitter, PopupWindow {
	static       Texture hueTex;
	static final Color   bgColor = Pal.gray;

	private Cons<Color> cons    = c -> {};
	final   Color       current = new Color();
	float h, s, v, a;
	TextField hexField;
	Slider    hSlider, aSlider;

	public ColorPicker() {
		super("@pickcolor", 0, 0, false, false);

		cont.background(IntUI.whiteui.tint(bgColor));
		sclListener.remove();
	}

	public void show(Color color, Cons<Color> consumer) {
		show(color, true, consumer);
	}

	public void show(Color color, boolean alpha, Cons<Color> consumer) {
		this.current.set(color);
		this.cons = consumer;
		show();

		if (hueTex == null) {
			hueTex = Pixmaps.hueTexture(128, 1);
			hueTex.setFilter(TextureFilter.linear);
		}

		float[] values = color.toHsv(new float[3]);
		h = values[0];
		s = values[1];
		v = values[2];
		a = color.a;

		cont.clear();
		cont.add(newTable(t -> {
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

					 Draw.color(Tmp.c1.fromHsv(h, s, v).inv(), parentAlpha);
					 Icon.cancelSmall.draw(x + s * width, y + v * height,
						5 * Scl.scl(), 5 * Scl.scl());
				 }
			 }).growX().height(100).padBottom(6f).colspan(2)
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

			t.defaults().width(140f).height(24f);


			t.add(new Element() {
				public void draw() {
					Draw.color();
					Draw.alpha(parentAlpha);
					HopeIcons.alphaBgCircle.draw(x, y, width, height);
					float x      = getX(Align.center);
					float y      = getY(Align.center);
					float radius = width / 2;
					float alpha  = a * parentAlpha;

					Draw.color(Tmp.c1.fromHsv(h, s, v).inv(), alpha);
					Fill.circle(x, y, radius);
					Draw.color(Tmp.c1.fromHsv(h, s, v), alpha);
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
				current.toHsv(values);
				h = values[0];
				s = values[1];
				v = values[2];
				a = current.a;

				hSlider.setValue(h);
				if (aSlider != null) {
					aSlider.setValue(a);
				}

				updateColor(false);
			}))
			 .size(150f, 40f)
			 .valid(text -> {
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
						float first  = Tmp.c1.set(current).a(0f).toFloatBits();
						float second = Tmp.c1.set(current).a(parentAlpha).toFloatBits();

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
		buttons.margin(0).defaults().growX().height(32);
		buttons.button("@cancel", Icon.cancel, HopeStyles.flatt, this::hide)
		 .marginLeft(4f).marginRight(4f);
		buttons.button("@ok", Icon.ok, HopeStyles.flatt, () -> {
			cons.get(current);
			hide();
		}).marginLeft(4f).marginRight(4f);
	}

	void updateColor() {
		updateColor(true);
	}

	void updateColor(boolean updateField) {
		h = Mathf.clamp(h, 0, 360);
		s = Mathf.clamp(s);
		v = Mathf.clamp(v);

		current.a = 1;
		current.fromHsv(h, s, v);
		current.a = a;

		if (hexField != null && updateField) {
			String val = current.toString().toUpperCase();
			if (current.a >= 0.9999f) {
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
}
