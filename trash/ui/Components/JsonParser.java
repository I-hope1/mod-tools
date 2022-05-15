package modmake.ui.components;

import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;

public class JsonParser {
    public static JsonReader parser = new JsonReader();
    public static JsonValue parse(String name){
        if (name == "") return null;
        String t = name.replaceAll("\\s", "");
        return parser.parse(t.charAt(0) == '{' ? name : "{\n" +name+ "\n}");
    }
}
