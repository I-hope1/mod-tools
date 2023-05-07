package modtools.ui.effect;

import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.FrameBuffer;
import arc.graphics.gl.Shader;
import modtools.IntVars;
import modtools.ui.effect.MyDraw.DrawEffect;
import modtools.utils.MySettings.Data;

import static modtools.ui.Contents.settingsUI;
import static modtools.utils.MySettings.D_BLUR;

public class Blur implements DrawEffect {
	public void resize(int width, int height) {}

	public void capture() {}

	public void render() {}
}