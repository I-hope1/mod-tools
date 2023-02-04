package modtools.utils;

import arc.files.Fi;
import arc.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ClassUtil {
	public static byte[] getClassByteCode(Fi fi) {
        String jarname = fi.name();
        InputStream inputStream = ClassUtil.class.getResourceAsStream(jarname);
        if (inputStream == null) throw new IllegalArgumentException("InputStream is null");

        ByteArrayOutputStream bytestream = new ByteArrayOutputStream();
        int ch;
        byte[] imgdata = null;
        try {
            while ((ch = inputStream.read()) != -1) {
                bytestream.write(ch);
            }
            imgdata = bytestream.toByteArray();
        } catch (IOException e) {
            Log.err(e);
        } finally {
            try {
                bytestream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return imgdata;
    }
}
