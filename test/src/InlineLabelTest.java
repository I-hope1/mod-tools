import arc.graphics.Color;
import arc.graphics.g2d.Font.Glyph;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.struct.*;
import arc.util.pooling.Pools;
import modtools.ui.comp.input.InlineLabel;
import org.junit.*;

import static org.junit.Assert.*;

public class InlineLabelTest {

    private Seq<GlyphRun> runs;
    private IntMap<Color> colorMap;
    private StringBuilder text;
    @Before
    public void setUp() {
        runs = new Seq<>();
        colorMap = new IntMap<>();
        text = new StringBuilder();
    }

    @Test
    public void splitAndColorize_EmptyRuns_ReturnsEmptyRuns() {
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertTrue(result.isEmpty());
    }

    @Test
    public void splitAndColorize_EmptyText_ReturnsEmptyRuns() {
        runs.add(Pools.obtain(GlyphRun.class, GlyphRun::new));
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertTrue(result.isEmpty());
    }

    @Test
    public void splitAndColorize_EmptyColorMap_ReturnsOriginalRuns() {
        runs.add(Pools.obtain(GlyphRun.class, GlyphRun::new));
        text.append("test");
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertEquals(1, result.size);
    }

    @Test
    public void splitAndColorize_ColorMapWithoutKeyZero_AddsWhiteColor() {
        colorMap.put(1, Color.red);
        text.append("test");
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertEquals(Color.white, result.get(0).color);
    }

    @Test
    public void splitAndColorize_ColorMapSizeOne_SetsSingleColor() {
        colorMap.put(0, Color.red);
        text.append("test");
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertEquals(Color.red, result.get(0).color);
    }

    @Test
    public void splitAndColorize_ColorMapSizeTwoWithTextLength_SetsSingleColor() {
        colorMap.put(0, Color.red);
        colorMap.put(text.length(), Color.white);
        text.append("test");
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertEquals(Color.red, result.get(0).color);
    }

    @Test
    public void splitAndColorize_ColorMapSizeGreaterThanTwo_SplitsAndColorsRuns() {
        colorMap.put(0, Color.red);
        colorMap.put(2, Color.green);
        text.append("test");
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertEquals(Color.red, result.get(0).color);
        assertEquals(Color.green, result.get(1).color);
    }

    @Test
    public void splitAndColorize_EmptyGlyphRun_IgnoresEmptyRuns() {
        runs.add(Pools.obtain(GlyphRun.class, GlyphRun::new));
        colorMap.put(0, Color.red);
        text.append("test");
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertEquals(1, result.size);
    }

    @Test
    public void splitAndColorize_NonEmptyGlyphRun_ColorizesCorrectly() {
        GlyphRun run = Pools.obtain(GlyphRun.class, GlyphRun::new);
        run.glyphs.add(new Glyph());
        runs.add(run);
        colorMap.put(0, Color.red);
        text.append("test");
        Seq<GlyphRun> result = InlineLabel.splitAndColorize(runs, colorMap, text);
        assertEquals(Color.red, result.get(0).color);
    }
}
