package modtools.graphics;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.Shader;
import arc.math.Mat;
import arc.math.geom.Vec2;
import modtools.*;


public class MyShaders {
	public static Shader specl, baseShader;
	/** 将任何纹理2中有颜色的替换成{@code color} */
	public static MixShader mixShader;
	// public static FrontShader frontShader;

	public static Fi shaderFi = IntVars.root.child("shaders");
	public static void load() {
		/* specl = new Shader(shaderFi.child("screenspace.vert"), shaderFi.child("毛玻璃.frag")) {
			public void apply() {
				setUniformf("u_time", Time.time / Scl.scl(1f));
				float width  = Core.camera.width;
				float height = Core.camera.height;
				setUniformf("u_offset",
						Core.camera.position.x - width / 2,
						Core.camera.position.y - height / 2);
				setUniformf("u_texsize", width, height);
				setUniformf("u_invsize", 1f / width, 1f / height);
			}
		}; */
		Shader last = Draw.getShader();
		Draw.shader();
		baseShader = Draw.getShader();
		Draw.shader(last);
		mixShader = new MixShader();
		// frontShader = new FrontShader();

		// blur = new BlurShader();

		// FrameBuffer buffer = new FrameBuffer();
		// Shaders.shield = shader;
		/* Events.run(Trigger.draw, () -> {
			// Draw.alpha(0.7f);
			// Fill.rect(0, 0, 1000, 1000);
			// Draw.shader(shader);
			// Draw.blit(shader);
			buffer.resize(graphics.getWidth(), graphics.getHeight());
			// shader.bind();
			Draw.drawRange(Layer.shields, 1f, () -> buffer.begin(Color.clear), () -> {
				buffer.end();
				buffer.blit(shader);
				// shader.apply();
			});
		}); */
	}

	public static class BlurShader extends Shader {
		private final Mat  convMat = new Mat();
		private final Vec2 size    = new Vec2();

		public BlurShader() {
			super(Core.files.internal("bloomshaders/blurspace.vert"),
			 shaderFi.child("gaussian_blur.frag"));
		}

		/* public void setConvMat(float... conv) {
			convMat.set(conv);
		}

		public void setBlurSize(float width, float height) {
			size.set(width, height);
		} */

		@Override
		public void apply() {
			setUniformMatrix("conv", convMat);
			setUniformf("size", size);
		}
	}
	public static class MixShader extends Shader {
		public Color color;
		public MixShader() {
			super(Core.files.internal("shaders/screenspace.vert"), shaderFi.child("mix.frag"));
		}
		public void apply() {
			setUniformf("color", color.r, color.g, color.b, color.a);
			setUniformi("u_texture0", 0);
			setUniformi("u_texture1", 1);
		}
	}
	public static class FrontShader extends Shader {
		public FrontShader() {
			super(Core.files.internal("shaders/screenspace.vert"), shaderFi.child("frontOnly.frag"));
		}
		public void apply() {
			setUniformi("u_texture0", 0);
			setUniformi("u_texture1", 1);
		}
	}
}
