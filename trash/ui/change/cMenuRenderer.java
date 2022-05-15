package modmake.ui.change;

import arc.Core;
import arc.files.Fi;
import arc.func.Floatc2;
import arc.graphics.Camera;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.math.Angles;
import arc.math.Mat;
import arc.math.Mathf;
import arc.scene.ui.layout.Scl;
import arc.util.Log;
import arc.util.Time;
import arc.util.Tmp;
import mindustry.content.Blocks;
import mindustry.graphics.MenuRenderer;
import mindustry.graphics.Pal;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.mod.Mods;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import modmake.ModMake;

import java.io.IOException;

import static mindustry.Vars.*;

public class cMenuRenderer extends MenuRenderer {

	public cMenuRenderer() {
		Time.mark();
		generate();
		cache();
		Log.debug("Time to generate menu: @", Time.elapsed());
	}

	private static final float darkness = 0.3f;
	private final int width = !mobile ? 100 : 60, height = !mobile ? 50 : 40;

	private int cacheFloor, cacheWall;
	private Camera camera = new Camera();
	private Mat mat = new Mat();
	private FrameBuffer shadows;
	private CacheBatch batch;
	private float time = 0f;
	private float flyerRot = 45f;
	private int flyers = Mathf.chance(0.2) ? Mathf.random(35) : Mathf.random(15);
	private UnitType flyerType = content.units().select(u -> !u.isHidden() && u.hitSize <= 20f && u.flying && u.onTitleScreen && u.region.found()).random();

	private void generate() {
		world.beginMapLoad();

		Map m = null;
		Mods.LoadedMod mod = mods.locateMod(ModMake.name);
		Fi fi = mod.root.child("_maps").child("map.msav");
		try {
			m = MapIO.createMap(fi, false);
		} catch (IOException e) {
			Log.err(e);
		}
		shadows = new FrameBuffer(width, height);

		Map finalM = m;
		Runnable loadMap = () -> {
			try {
				SaveIO.load(finalM.file, world.filterContext(finalM));
			} catch (Throwable e) {
				Log.err(e);
			}
		};

		if (m != null) loadMap.run();

		world.endMapLoad();
	}

	private void cache() {

		//draw shadows
		Draw.proj().setOrtho(0, 0, shadows.getWidth(), shadows.getHeight());
		shadows.begin(Color.clear);
		Draw.color(Color.black);

		for (Tile tile : world.tiles) {
			if (tile.block() != Blocks.air) {
				Fill.rect(tile.x + 0.5f, tile.y + 0.5f, 1, 1);
			}
		}

		Draw.color();
		shadows.end();

		Batch prev = Core.batch;

		Core.batch = batch = new CacheBatch(new SpriteCache(width * height * 6, false));
		batch.beginCache();

		for (Tile tile : world.tiles) {
			tile.floor().drawBase(tile);
		}

		for (Tile tile : world.tiles) {
			tile.overlay().drawBase(tile);
		}

		cacheFloor = batch.endCache();
		batch.beginCache();

		for (Tile tile : world.tiles) {
			tile.block().drawBase(tile);
		}

		cacheWall = batch.endCache();

		Core.batch = prev;
	}

	public void render() {
		time += Time.delta;
		float scaling = Math.max(Scl.scl(4f), Math.max(Core.graphics.getWidth() / ((width - 1f) * tilesize), Core.graphics.getHeight() / ((height - 1f) * tilesize)));
		camera.position.set(width * tilesize / 2f, height * tilesize / 2f);
		camera.resize(Core.graphics.getWidth() / scaling,
				Core.graphics.getHeight() / scaling);

		mat.set(Draw.proj());
		Draw.flush();
		Draw.proj(camera);
		batch.setProjection(camera.mat);
		batch.beginDraw();
		batch.drawCache(cacheFloor);
		batch.endDraw();
		Draw.color();
		Draw.rect(Draw.wrap(shadows.getTexture()),
				width * tilesize / 2f - 4f, height * tilesize / 2f - 4f,
				width * tilesize, -height * tilesize);
		Draw.flush();
		batch.beginDraw();
		batch.drawCache(cacheWall);
		batch.endDraw();

		drawFlyers();

		Draw.proj(mat);
		Draw.color(0f, 0f, 0f, darkness);
		Fill.crect(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());
		Draw.color();
	}

	private void drawFlyers() {
		Draw.color(0f, 0f, 0f, 0.4f);

		TextureRegion icon = flyerType.fullIcon;

		float size = Math.max(icon.width, icon.height) * Draw.scl * 1.6f;

		flyers((x, y) -> {
			Draw.rect(icon, x - 12f, y - 13f, flyerRot - 90);
		});

		flyers((x, y) -> {
			Draw.rect("circle-shadow", x, y, size, size);
		});
		Draw.color();

		flyers((x, y) -> {
			float engineOffset = flyerType.engineOffset, engineSize = flyerType.engineSize, rotation = flyerRot;

			Draw.color(Pal.engine);
			Fill.circle(x + Angles.trnsx(rotation + 180, engineOffset), y + Angles.trnsy(rotation + 180, engineOffset),
					engineSize + Mathf.absin(Time.time, 2f, engineSize / 4f));

			Draw.color(Color.white);
			Fill.circle(x + Angles.trnsx(rotation + 180, engineOffset - 1f), y + Angles.trnsy(rotation + 180, engineOffset - 1f),
					(engineSize + Mathf.absin(Time.time, 2f, engineSize / 4f)) / 2f);
			Draw.color();

			Draw.rect(icon, x, y, flyerRot - 90);
		});
	}

	private void flyers(Floatc2 cons) {
		float tw = width * tilesize * 1f + tilesize;
		float th = height * tilesize * 1f + tilesize;
		float range = 500f;
		float offset = -100f;

		for (int i = 0; i < flyers; i++) {
			Tmp.v1.trns(flyerRot, time * (flyerType.speed));

			cons.get(
					(Mathf.randomSeedRange(i, range) + Tmp.v1.x + Mathf.absin(time + Mathf.randomSeedRange(i + 2, 500), 10f, 3.4f) + offset) % (tw + Mathf.randomSeed(i + 5, 0, 500)),
					(Mathf.randomSeedRange(i + 1, range) + Tmp.v1.y + Mathf.absin(time + Mathf.randomSeedRange(i + 3, 500), 10f, 3.4f) + offset) % th
			);
		}
	}

}
