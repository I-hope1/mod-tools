package modtools.ui.content.ui.design;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.Image;
import arc.scene.ui.layout.*;
import arc.struct.ObjectMap;
import arc.util.Structs;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.*;
import mindustry.gen.Tex;
import modtools.jsfunc.type.CAST;
import modtools.ui.comp.ModifiedLabel;
import modtools.ui.content.ui.ShowUIList;
import modtools.utils.*;
import modtools.utils.reflect.*;

import java.lang.reflect.Field;

import static modtools.ui.comp.linstener.ReferringMoveListener.snap;

public class DesignTable<T extends Group> extends WidgetGroup {
	public T template;
	float[] horizontalLines = new float[]{0, 0.5f, 1f}, verticalLines = new float[]{0, 0.5f, 1f};
	VirtualGroup virtualGroup = new VirtualGroup();
	public DesignTable(T template) {
		this.template = template;
		init();
	}

	public float getPrefWidth() {
		return template.getPrefWidth();
	}
	public float getPrefHeight() {
		return template.getPrefHeight();
	}
	public void act(float delta) {
		super.act(delta);
		if (template.parent == this) {
			template.setSize(width, height);
			template.x = 0;
			template.y = 0;
			return;
		}
		Vec2 pos = ElementUtils.getAbsolutePos(this);
		virtualGroup.setPosition(pos.x - x, pos.y - y);
		template.setSize(width, height);
		template.x = x;
		template.y = y;
		template.act(delta);
	}
	private void init() {
		changeStatus(Status.move);
		addCaptureListener(new InputListener() {
			private Element selected;
			final   Vec2    delta = new Vec2(), last = new Vec2();
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (status == Status.edit) return false;
				selected = template.hit(x, y, false);
				if (selected == null) return false;
				while (selected.parent != template && selected.parent != DesignTable.this) {
					selected = selected.parent;
					if (selected == null) return false;
				}
				if (button != KeyCode.mouseLeft) return false;
				if (status == Status.delete) {
					selected.remove();
					return false;
				}
				selected.toFront();
				last.set(selected.x, selected.y);
				delta.set(x, y);
				return true;
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				if (status != Status.move) return;
				if (event.keyCode != KeyCode.mouseLeft) return;
				Vec2 vec2 = snap(selected, horizontalLines, verticalLines, last.x + x - delta.x, last.y + y - delta.y);
				selected.setPosition(vec2.x, vec2.y);
			}
		});
	}

	public void addChild(Element actor) {
		actor.x = width / 2f;
		actor.y = height / 2f;
		template.addChild(actor);
	}
	public void draw() {
		if (template.parent == virtualGroup) template.draw();
		else super.draw();
	}
	// status
	Status status;
	public void changeStatus(Status status) {
		this.status = status;
		status.listener.get(this);
	}
	void super$addChild(Element actor) {
		super.addChild(actor);
	}
	public void export() {
	}

	// static final ObjectMap<Class<?>, ?> classParsers = FieldUtils.getOrNull(FieldUtils.getFieldAccess(ContentParser.class, "classParsers"));
	// static final Json parser = FieldUtils.getOrNull(FieldUtils.getFieldAccess(ContentParser.class, "parser"));
	public void save() {
		Jval array = Jval.newArray();
		for (Element child : template.getChildren()) {
			Jval obj = Jval.newObject();
			obj.put("$type", ((DesignElement)child).$type().name());
			ClassUtils.walkPublicNotStaticKeys(child.getClass(), field -> {
				Object val = FieldUtils.getOrNull(field, child);
				if (val == null) return;
				Class<?> valClass = field.getType();
				if (valClass.isPrimitive() || valClass == Color.class) {
					obj.put(field.getName(), Jval.read(val.toString()));
				} else {
					FieldStringify stringify = classStringify.get(valClass);
					if (stringify == null) return;
					obj.put(field.getName(), stringify.toJval(val));
				}
			});
			array.add(obj);
		}
		JSFunc.copyText(array.toString(Jformat.hjson));
	}
	public void load() {
		String    text  = Core.app.getClipboardText();
		JsonArray array = Jval.read(text).asArray();
		template.clear();
		for (Jval jval : array) {
			Jval type = jval.remove("$type");
			Element actor = switch (ElementType.valueOf(type.asString())) {
				case label -> new DesignLabel(jval.remove("$text").asString());
				case img -> new DesignImage();
			};
			Field[] fields = actor.getClass().getFields();
			for (Field field : fields) {
				if (!jval.has(field.getName())) continue;
				Object parse = parse(jval.get(field.getName()), field.getType());
				if (parse == null) continue;
				FieldUtils.setValue(field, actor, parse);
			}
			template.addChild(actor);
		}
	}
	public static <T> Object parse(Jval jval, Class<T> expect) {
		if (Number.class.isAssignableFrom(CAST.box(expect))) return jval.asNumber();
		if (expect == boolean.class) return jval.asBool();
		if (expect == Color.class) return new Color(Integer.parseUnsignedInt(jval.toString(), 16));
		FieldParser parser = classParsers.get(expect);
		if (parser == null) return null;
		return parser.parse(jval);
	}

	public enum Status {
		move(t -> t.virtualGroup.addChild(t.template)),
		edit(t -> t.super$addChild(t.template)),
		delete(t -> t.virtualGroup.addChild(t.template));

		final Cons<DesignTable<?>> listener;
		Status(Cons<DesignTable<?>> listener) {
			this.listener = listener;
		}
	}

	public static class DesignLabel extends Table implements DesignElement{
		public String $text;
		public DesignLabel(String text) {
			$text = text;
			ModifiedLabel.build(() -> $text, _ -> true, (f, _) -> {
				$text = f.getText();
			}, this);
		}
		public ElementType $type() {
			return ElementType.label;
		}
	}
	public static class DesignImage extends Image implements DesignElement {
		public Drawable   drawable = Tex.nomap;
		static Drawable[] drawables = ShowUIList.iconKeyMap.keySet().toArray(Drawable[]::new);
		public DesignImage() {
			super(Tex.nomap);
			setSize(42);
			clicked(() -> drawable = Structs.random(drawables));
		}
		public void act(float delta) {
			super.act(delta);
			setDrawable(drawable);
		}
		public ElementType $type() {
			return ElementType.img;
		}
	}

	static ObjectMap<Class<?>, FieldStringify> classStringify = new ObjectMap<>() {{
		put(String.class, obj -> Jval.valueOf((String) obj));
		put(Drawable.class, obj -> Jval.valueOf(allDrawables.get((Drawable) obj)));
	}};
	interface FieldStringify {
		Jval toJval(Object object);
	}
	static ObjectMap<Drawable, String> allDrawables = new ObjectMap<>(){{
		ShowUIList.iconKeyMap.forEach(this::put);
		ShowUIList.styleIconKeyMap.forEach(this::put);
	}};
	static ObjectMap<Class<?>, FieldParser> classParsers = new ObjectMap<>() {{
		put(String.class, Jval::toString);
		put(Drawable.class, jval -> allDrawables.findKey(jval.asString(), false));
	}};
	interface FieldParser {
		Object parse(Jval jval);
	}

	public enum ElementType {
		label, img
	}
	public interface DesignElement {
		ElementType $type();
	}
	private static class VirtualGroup extends WidgetGroup {
		public void setScene(Scene stage) {
			super.setScene(stage);
		}
	}
}