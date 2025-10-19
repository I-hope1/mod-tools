package modtools.unsupported;

import android.os.*;
import android.os.StrictMode.*;
import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import mindustry.mod.Mod;

import java.io.*;
import java.lang.reflect.*;

import static modtools.IntVars.*;

public class TestAndroidVM {

    public static void main()
        throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, NoSuchFieldException {
        Class<?> VMDebug = mainLoader.loadClass("dalvik.system.VMDebug");
        Method getInstancesOfClasses = VMDebug.getDeclaredMethod(
            "getInstancesOfClasses",
            Class[].class,
            boolean.class
        );
        getInstancesOfClasses.setAccessible(true);
        Object[][] instances = (Object[][]) getInstancesOfClasses.invoke(
            null,
            new Class[] { Mod.class },
            true
        );
        for (Object[] instance : instances) {
            for (Object obj : instance) {
                if (obj instanceof Mod mod) {
                    Log.info("ModTools found mod: @", mod.getClass().getName());
                }
            }
        }

        // Lax policy to allow file operations and reflective access on the main thread for testing.
        StrictMode.setThreadPolicy(ThreadPolicy.LAX);
        StrictMode.setVmPolicy(VmPolicy.LAX);

        // Debug.waitForDebugger();
        Method method = TestAndroidVM.class.getDeclaredMethod("fakeMethod");
        method.setAccessible(true);
        Field artMethod = Executable.class.getDeclaredField("artMethod");
        artMethod.setAccessible(true);
        artMethod.set(method, artMethod.get(A.class.getDeclaredConstructor()));
        A a = new A();
        a.field = 10;
        Log.info("field: @", a.field);
        method.invoke(a);
        Log.info("field: @", a.field);

        attachAgent(VMDebug);
    }

    private static void attachAgent(Class<?> VMDebug) {
        try {
            Method attachAgent = VMDebug.getDeclaredMethod(
                "attachAgent",
                String.class
            );
            attachAgent.setAccessible(true);

            // Copy and load common C++ shared library dependency first.
            // This might not be needed depending on your agent's dependencies.
            copyLib("libc++_shared.so", true);

            // Copy the agent library but DO NOT load it via System.load().
            // The `attachAgent` call will handle loading it into the VM.
            String agentPath = copyLib("libmyagent.so", false);

            Log.info("Attempting to attach agent: @", agentPath);
            attachAgent.invoke(null, agentPath);
            Log.info("Successfully attached agent.");
        } catch (Exception e) {
            Log.err(
                "Failed to attach agent. This can be caused by SELinux policies, incorrect file permissions, or an incompatible agent binary.",
                e
            );
        }
    }

    /**
     * Copies a native library from the mod's assets to the app's private library directory,
     * making it executable and loadable. It respects different CPU architectures (ABIs).
     *
     * @param name The name of the library file (e.g., "libmyagent.so").
     * @param loadLib If true, loads the library using System.load() after copying.
     * @return The absolute path to the copied library file.
     * @throws IOException If the library file cannot be found or copied.
     */
    private static String copyLib(String name, boolean loadLib)
        throws IOException {
        // Determine the device's primary ABI (e.g., arm64-v8a).
        String abi = Build.SUPPORTED_ABIS[0];
        Fi source = root.child("libs").child(abi).child(name);

        // Fallback to a generic path if the ABI-specific library doesn't exist.
        if (!source.exists()) {
            Log.warn(
                "ABI-specific lib not found for '@' in '@'. Trying generic 'libs/@'",
                abi,
                name,
                name
            );
            source = root.child("libs").child(name);
        }

        if (!source.exists()) {
            throw new IOException(
                "Native library '" +
                    name +
                    "' not found for ABI '" +
                    abi +
                    "' or in the generic 'libs/' folder within the mod."
            );
        }

        // The app's private 'lib' directory is the correct place for native libraries.
        File libDir = new File(
            Core.settings.getDataDirectory().file(),
            "lib"
        );
        if (!libDir.exists()) {
            libDir.mkdirs();
        }

        Fi destination = new Fi(new File(libDir, name));

        // Copy the library from the mod to the app's lib directory.
        source.copyTo(destination);

        // Set the executable flag, which is required for loading.
        destination.file().setExecutable(true, false);

        String absolutePath = destination.absolutePath();
        Log.info("Copied native library '@' to '@'", name, absolutePath);

        if (loadLib) {
            System.load(absolutePath);
            Log.info("Loaded library: @", absolutePath);
        }

        return absolutePath;
    }

    static void fakeMethod() {}

    static class A {

        float field;

        A() {
            field = 100;
        }
    }
}
