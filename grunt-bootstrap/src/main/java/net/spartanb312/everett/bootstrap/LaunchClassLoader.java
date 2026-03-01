package net.spartanb312.everett.bootstrap;

import java.io.File;
import java.net.URL;

public class LaunchClassLoader extends ExternalClassLoader {

    public final static LaunchClassLoader INSTANCE = new LaunchClassLoader();

    public LaunchClassLoader() {
        super("LaunchClassLoader", new URL[0], Main.class.getClassLoader());
    }

    public static void loadJarFile(String file) {
        loadJarFile(new File(file));
    }

    public static void loadJarFile(File file) {
        try {
            INSTANCE.loadJar(file);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public static URL loadResourceFile(File file) {
        try {
            return INSTANCE.loadResource(file);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return null;
    }

    public static void addResourcePath(String path) {
        INSTANCE.addPath(path);
    }

    public static void removeResourcePath(String path) {
        INSTANCE.removePath(path);
    }

}
