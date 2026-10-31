import arc.Core;
import arc.Graphics;
import arc.Settings;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.TextureRegion;
import arc.mock.MockApplication;
import arc.mock.MockGraphics;
import arc.scene.Scene;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Label.LabelStyle;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.gen.Tex;
import mindustry.mod.ModClassLoader;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.events.E_JSFunc;
import modtools.ui.comp.utils.PlainValueLabel;
import modtools.ui.comp.utils.Viewers;

public class ColorTest {
    public static void main(String[] args) throws Exception {
        Core.app = new MockApplication();
        Core.settings = new Settings();
        Core.graphics = new MockGraphics();

        Tex.whiteui = new TextureRegionDrawable(new TextureRegion());
        Styles.flatOver = new TextureRegionDrawable(new TextureRegion());
        Styles.none = new TextureRegionDrawable(new TextureRegion());
        Styles.cleart = new arc.scene.ui.TextButton.TextButtonStyle();
        Styles.cleart.up = Styles.none;
        Font mockFont = (Font) ihope_lib.MyReflect.unsafe.allocateInstance(Font.class);
        Reflect.set(Font.class, mockFont, "regions", Seq.with(new TextureRegion()));
        Reflect.set(Font.class, mockFont, "data", new Font.FontData());
        Styles.cleart.font = mockFont;
        Styles.defaultb = new arc.scene.ui.Button.ButtonStyle();
        Styles.defaultb.up = Styles.none;
        Styles.defaultLabel = new LabelStyle();
        Styles.defaultLabel.font = mockFont;
        modtools.ui.gen.HopeIcons.whiteuiCircle = new TextureRegionDrawable(new TextureRegion());

        Vars.dataDirectory = Fi.get("./build/tmp/mindustry_data");
        Vars.dataDirectory.mkdirs();
        Vars.mods = (Mods) ihope_lib.MyReflect.unsafe.allocateInstance(Mods.class);
        ModClassLoader loader = new ModClassLoader(ColorTest.class.getClassLoader());
        Reflect.set(Mods.class, Vars.mods, "mainLoader", loader);

        IntVars.dataDirectory = Vars.dataDirectory.child("mod_tools").child("b0kkihope");
        IntVars.dataDirectory.mkdirs();

        Core.scene = (Scene) ihope_lib.MyReflect.unsafe.allocateInstance(Scene.class);
        Reflect.set(Scene.class, Core.scene, "styleDefaults", new ObjectMap<>());
        LabelStyle style = new LabelStyle();
        style.font = (Font) ihope_lib.MyReflect.unsafe.allocateInstance(Font.class);
        Reflect.set(Font.class, style.font, "regions", Seq.with(new TextureRegion()));
        Reflect.set(Font.class, style.font, "data", new Font.FontData());
        Core.scene.addStyle(LabelStyle.class, style);

        Class.forName(Viewers.class.getName());

        // 1. Color[] with pretty_print = true
        E_JSFunc.pretty_print.set(true);
        Color[] colors = new Color[]{
            Color.valueOf("82759aff"),
            Color.valueOf("c0c1c5ff"),
            Color.valueOf("ffffffff")
        };
        PlainValueLabel<Color[]> vl = new PlainValueLabel<>(Color[].class, () -> colors);
        vl.expandVal.put(colors, true);
        vl.flushVal();
        String outPretty = vl.getText().toString();
        System.out.println("Color[] Output (pretty_print=true):\n[" + outPretty + "]");

        // 2. Color[] with pretty_print = false
        E_JSFunc.pretty_print.set(false);
        PlainValueLabel<Color[]> vlCompact = new PlainValueLabel<>(Color[].class, () -> colors);
        vlCompact.expandVal.put(colors, true);
        vlCompact.flushVal();
        String outCompact = vlCompact.getText().toString();
        System.out.println("Color[] Output (pretty_print=false):\n[" + outCompact + "]");

        // 3. Standalone Color
        PlainValueLabel<Color> vlSingle = new PlainValueLabel<>(Color.class, () -> Color.valueOf("82759aff"));
        vlSingle.flushVal();
        String outSingle = vlSingle.getText().toString();
        System.out.println("Single Color Output:\n[" + outSingle + "]");

        // Assertions
        if (outPretty.contains(",\n\n") || outPretty.contains(",,\n") || outPretty.contains(",\n  ,")) {
            throw new AssertionError("Color[] contains double comma in pretty mode: " + outPretty);
        }
        if (outCompact.contains(",,")) {
            throw new AssertionError("Color[] contains double comma in compact mode: " + outCompact);
        }
        if (outSingle.endsWith(",")) {
            throw new AssertionError("Single Color has trailing comma: " + outSingle);
        }
        System.out.println("\n>>> ALL COLOR/VIEWER DELIMITER TESTS PASSED! <<<");
    }
}
