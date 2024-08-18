package modtools.ui.reflect;

import arc.func.*;
import arc.graphics.Color;
import arc.math.Interp;
import arc.struct.*;
import arc.util.Reflect;
import arc.util.serialization.Json.FieldMetadata;
import modtools.IntVars;
import modtools.jsfunc.type.CAST;
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.utils.Tools.*;

import java.lang.reflect.Field;

import static modtools.utils.Tools.as;


@SuppressWarnings("StringTemplateMigration")
public class RBuilder extends SettingsBuilder {
	public static <T> void buildFor(Class<T> clazz, T obj, Boolf<Field> boolf) {
		OrderedMap<String, FieldMetadata> metas = IntVars.json.getFields(clazz);

		for (FieldMetadata meta : metas.values()) {
			Field field = meta.field;
			if (!boolf.get(field)) continue;
			String   name = field.getName();
			Class<?> type = CAST.box(field.getType());
			if (Boolean.class == type) {
				check(name, CBoolc.of("", b -> field.setBoolean(obj, b)),
				 CBoolp.of("Failed to get " + name, () -> field.getBoolean(obj)));
			} else if (Number.class.isAssignableFrom(type)) {
				number(name, b -> Reflect.set(obj, field, b), () -> ((Number) Reflect.get(obj, field)).floatValue());
			} else if (Color.class == type) {
				Color d = Reflect.get(obj, field);
				if (d == null) Reflect.set(obj, field, d = Color.white);
				color(name, d, b -> Reflect.set(obj, field, b));
			} else if (Interp.class == type) {
				Interp d = Reflect.get(obj, field);
				if (d == null) Reflect.set(obj, field, d = Interp.linear);
				interpolator(name, b -> Reflect.set(obj, field, b), () -> Reflect.get(obj, field));
			} else if (type.isEnum()) {
				enum_(name, as(type), b -> Reflect.set(obj, field, b), () -> Reflect.get(obj, field), null);
			} else if (type == Runnable.class) {
				Reflect.set(obj, field, IntVars.EMPTY_RUN);
			}
		}
	}

	public static <T> void buildFor(Class<T> clazz, T obj, Seq<String> blackList) {
		buildFor(clazz, obj, f -> !blackList.contains(f.getName()));
	}
}
