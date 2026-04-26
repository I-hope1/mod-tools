package modtools.graphics;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.gl.Shader;
import modtools.IntVars;


public class MyShaders {
	public static Shader specl, baseShader;
	/** 将任何纹理中有颜色的替换成{@code color} */
	public static MixScreen  mixScreen;

	// public static Shader blur;
	// public static FrontShader frontShader;

	// public static Batch maskBatch;

	public static Fi shaderFi = IntVars.root.child("shaders");
	public static void load() {
		baseShader = new Shader(
		 Core.files.internal("shaders/screenspace.vert"),
		 shaderFi.child("dist_base.frag"));
		mixScreen = new MixScreen();

		// blur = new Shader(
		//  Core.files.internal("shaders/screenspace.vert"),
		//  shaderFi.child("blur.frag"));
	}

	public static class MixScreen extends Shader {
		public MixScreen() {
			super(Core.files.internal("shaders/screenspace.vert"),
			 shaderFi.child("mix.frag"));
		}

		public Color color;
		public void apply() {
			setUniformf("color", color.r, color.g, color.b, color.a);
			setUniformi("u_texture0", 0);
			setUniformi("u_texture1", 1);
		}
	}
}
