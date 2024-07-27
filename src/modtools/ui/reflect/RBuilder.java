package modtools.ui.reflect;

import arc.struct.OrderedMap;
import arc.util.Reflect;
import arc.util.serialization.Json.FieldMetadata;
import modtools.IntVars;
import modtools.jsfunc.type.CAST;
import modtools.ui.content.SettingsUI.SettingsBuilder;
import modtools.utils.Tools.*;

import java.lang.reflect.Field;


@SuppressWarnings("StringTemplateMigration")
public class RBuilder extends SettingsBuilder {
	public static <T> void buildFor(Class<T> clazz) throws InstantiationException, IllegalAccessException {
		OrderedMap<String, FieldMetadata> metas = IntVars.json.getFields(clazz);

		T obj = clazz.newInstance();

		for (FieldMetadata meta : metas.values()) {
			Field    field = meta.field;
			String   name  = field.getName();
			Class<?> type  = CAST.box(field.getType());
			if (Boolean.class == type) {
				check(name, CBoolc.of("", b -> field.setBoolean(obj, b)),
				 CBoolp.of("Failed to get " + name, () -> field.getBoolean(obj)));
			} else if (Number.class.isAssignableFrom(type)) {
				number(name, b -> Reflect.set(obj, field, b), () -> Reflect.get(obj, field));
			}
		}
	}
}
