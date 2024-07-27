package modtools.ui.reflect;

import arc.struct.OrderedMap;
import arc.util.serialization.Json.FieldMetadata;
import modtools.IntVars;
import modtools.ui.content.SettingsUI.SettingsBuilder;

public class RBuilder extends SettingsBuilder {
	public static <T> void buildFor(Class<T> clazz) {
		OrderedMap<String, FieldMetadata> fields = IntVars.json.getFields(clazz);

		for (FieldMetadata field : fields.values()) {
			String name = field.field.getName();
			/* if (field.keyType)check(name, b -> {}, () -> false);
				case Number.class -> number(name, i -> {}, () -> 0);
				default -> throw new RuntimeException();
			} */
		}
	}
}
