package modtools.ui.comp.input;

import arc.Core;
import arc.Graphics.Cursor;
import arc.Graphics.Cursor.SystemCursor;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.mock.MockGL20;
import arc.mock.MockGraphics;
import arc.math.geom.Point2;
import arc.scene.Scene;
import arc.scene.event.InputEvent;
import arc.scene.event.InputEvent.InputEventType;
import arc.scene.ui.Label;
import arc.scene.ui.Label.LabelStyle;
import arc.struct.IntMap;
import arc.struct.Seq;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link InlineLabel}'s highlight geometry and hit testing.
 * <p>
 * Everything runs headless: glyph runs are built by hand (one glyph per character, a fixed advance per
 * glyph) and injected into the label's layout, so no font texture or OpenGL context is needed.
 */
class InlineLabelHitTestTest {
	private static final float ADV    = 10f;
	private static final float LINE   = 20f;
	private static final float CAP    = 20f;
	private static final String TEXT  = "abcde";

	private static CursorRecorder graphics;

	@BeforeAll
	static void installMocks() {
		// Scene's viewport calls into GL; a mock GL keeps the whole suite headless.
		Core.gl = new MockGL20();
		Core.gl20 = new MockGL20();
		graphics = new CursorRecorder();
		Core.graphics = graphics;
	}

	@BeforeEach
	void resetSharedState() throws ReflectiveOperationException {
		chunk("overChunk").set(-1, -1);
		chunk("downChunk").set(-1, -1);
		graphics.last = null;
	}

	@Test
	@DisplayName("a region ending at the last character emits exactly one rect")
	void rectAtTextEndIsNotDuplicated() {
		InlineLabel label = make(TEXT);

		Seq<float[]> rects = new Seq<>();
		label.getRect(new Point2(2, TEXT.length()), r -> rects.add(new float[]{r.x, r.y, r.width, r.height}));

		assertEquals(1, rects.size, "region [2, 5) must be covered by exactly one rect");
		float[] rect = rects.first();
		assertAll(
			() -> assertEquals(2 * ADV, rect[0], 1e-3f, "rect must start at the pen position of index 2"),
			() -> assertEquals(0f, rect[1], 1e-3f, "rect must sit on the text line"),
			() -> assertEquals(3 * ADV, rect[2], 1e-3f, "rect must cover indices 2..4"),
			() -> assertEquals(LINE, rect[3], 1e-3f, "rect must be one line tall")
		);
	}

	@Test
	@DisplayName("hovering the character right after the region is not a hit")
	void hoverUsesHalfOpenRegion() {
		InlineLabel label = make(TEXT);
		label.clickedRegion(() -> new Point2(2, 4), () -> {});

		moveMouse(label, 35f, 10f); // character at index 3 is inside [2, 4)
		assertEquals(2, overChunk().x, "index 3 must be hovered");
		assertEquals(4, overChunk().y, "index 3 must be hovered");
		assertSame(SystemCursor.hand, graphics.last, "a hovered clickable region must show the hand cursor");

		moveMouse(label, 45f, 10f); // character at index 4 is the first index outside [2, 4)
		assertTrue(isUnset(overChunk()), "index 4 is outside [2, 4) and must not be hovered");
		assertSame(SystemCursor.arrow, graphics.last, "a non-hovered character must not show the hand cursor");
	}

	@Test
	@DisplayName("pressing the character right after the region does not click")
	void clickUsesHalfOpenRegion() {
		InlineLabel label = make(TEXT);
		attach(label);
		int[] clicks = {0};
		label.clickedRegion(() -> new Point2(2, 4), () -> clicks[0]++);

		fire(label, InputEventType.touchDown, 45f, 10f); // character at index 4, outside [2, 4)
		fire(label, InputEventType.touchUp, 45f, 10f);
		assertEquals(0, clicks[0], "index 4 is outside [2, 4) and must not be clickable");

		fire(label, InputEventType.touchDown, 35f, 10f); // character at index 3, inside [2, 4)
		fire(label, InputEventType.touchUp, 35f, 10f);
		assertEquals(1, clicks[0], "index 3 is inside [2, 4) and must be clickable");
	}

	@Test
	@DisplayName("leaving the label restores the arrow cursor")
	void exitRestoresArrowCursor() {
		InlineLabel label = make(TEXT);
		label.clickedRegion(() -> new Point2(0, TEXT.length()), () -> {});

		moveMouse(label, 5f, 10f); // character at index 0 is clickable
		assertSame(SystemCursor.hand, graphics.last, "hovering a clickable region must show the hand cursor");

		InputEvent exit = new InputEvent();
		exit.type = InputEventType.exit;
		exit.pointer = -1;
		label.fire(exit);
		assertSame(SystemCursor.arrow, graphics.last, "leaving the label must restore the arrow cursor");
	}

	@Test
	@DisplayName("a trailing empty glyph run does not break colorization")
	void trailingEmptyRunIsIgnored() {
		Seq<GlyphRun> runs = buildRuns("ab");
		runs.add(new GlyphRun()); // the blank line produced by a trailing newline

		IntMap<Color> colors = new IntMap<>();
		colors.put(0, new Color(Color.white));
		colors.put(3, new Color(Color.red));

		assertDoesNotThrow(() -> InlineLabel.splitAndColorize(runs, colors, new StringBuilder("ab\n")));
		assertEquals(1, runs.size, "the empty trailing run must not survive colorization");
		assertEquals("ab", glyphsOf(runs.first()), "colorization must keep every glyph");
	}

	// region helpers

	/** A label whose layout is overridden with hand-built runs for {@link #TEXT}. */
	private static InlineLabel make(String text) {
		InlineLabel label = new InlineLabel("", new LabelStyle(font(), Color.white));
		label.setText(text);
		GlyphLayout layout = layoutOf(label);
		layout.runs.clear();
		layout.runs.addAll(buildRuns(text));
		label.labelX = 0;
		label.labelY = LINE;
		label.setSize(200, 40);
		return label;
	}

	/** A one-line run sequence; glyph i starts at {@code i * ADV}, runs are stacked downwards. */
	private static Seq<GlyphRun> buildRuns(String... lines) {
		Seq<GlyphRun> runs = new Seq<>();
		float y = 0;
		for (String line : lines) {
			GlyphRun run = new GlyphRun();
			for (int i = 0; i < line.length(); i++) {
				Font.Glyph glyph = new Font.Glyph();
				glyph.id = line.charAt(i);
				glyph.xadvance = (int) ADV;
				run.glyphs.add(glyph);
			}
			run.xAdvances.add(0f);
			for (int i = 0; i < line.length(); i++) run.xAdvances.add(ADV);
			run.width = line.length() * ADV;
			run.x = 0;
			run.y = y;
			runs.add(run);
			y -= LINE;
		}
		return runs;
	}

	private static Font font() {
		FontData data = new FontData();
		data.lineHeight = LINE;
		data.capHeight = CAP;
		data.down = LINE;
		data.descent = 0;
		// The constructor only requires one (unused) page region; no texture is touched.
		return new Font(data, Seq.with(new TextureRegion()), false);
	}

	private static GlyphLayout layoutOf(Label label) {
		try {
			Field field = Label.class.getDeclaredField("layout");
			field.setAccessible(true);
			return (GlyphLayout) field.get(label);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	/** The static chunk currently highlighted / pressed, e.g. {@code overChunk} or {@code downChunk}. */
	private static Point2 chunk(String name) throws ReflectiveOperationException {
		Field field = InlineLabel.class.getDeclaredField(name);
		field.setAccessible(true);
		return (Point2) field.get(null);
	}

	private static Point2 overChunk() {
		try {
			return chunk("overChunk");
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static boolean isUnset(Point2 chunk) {
		return chunk.x == InlineLabel.UNSET_I && chunk.y == InlineLabel.UNSET_I;
	}

	/** Puts the label into a scene so touch handling can resolve {@code Core.scene.hit}. */
	private static void attach(InlineLabel label) {
		// keep the label and its scene reachable for the whole test
		scene = new Scene();
		scene.add(label);
		Core.scene = scene;
	}

	private static Scene scene;

	private static void moveMouse(InlineLabel label, float x, float y) {
		fire(label, InputEventType.mouseMoved, x, y);
	}

	private static void fire(InlineLabel label, InputEventType type, float x, float y) {
		InputEvent event = new InputEvent();
		event.type = type;
		event.stageX = x;
		event.stageY = y;
		event.pointer = 0;
		event.keyCode = KeyCode.mouseLeft;
		label.fire(event);
	}

	private static String glyphsOf(GlyphRun run) {
		StringBuilder builder = new StringBuilder(run.glyphs.size);
		for (Font.Glyph glyph : run.glyphs) builder.append((char) glyph.id);
		return builder.toString();
	}

	/** Records every cursor the code under test asks for, including cache hits. */
	static class CursorRecorder extends MockGraphics {
		Cursor last;

		@Override
		public void cursor(Cursor cursor) {
			last = cursor;
			super.cursor(cursor);
		}
	}
	// endregion
}
