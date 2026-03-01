package net.spartanb312.everett.bootstrap;

import java.awt.*;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Module(name = "Launch Wrapper", version = Main.LAUNCH_WRAPPER_VERSION, description = "Bootstrap launch wrapper", author = "B_312")
public class Main {

    public static final String LAUNCH_WRAPPER_VERSION = "2.1.0";
    public static String[] args;
    public static List<ExternalClassLoader> classLoaders = new ArrayList<>();
    private static final String defaultEntry = "net.spartanb312.everett.bootstrap.DefaultEntry";
    public static String applicationEntry;

    public static void main(String[] args) throws Exception {
        Main.args = args;
        launch(Arrays.stream(args).toList(), defaultEntry);
    }

    public static void launch(List<String> argsList, String defaultEntry) throws Exception {
        var startTime = System.currentTimeMillis();
        LaunchLogger.info("Initializing launch wrapper...");

        // Detect system
        LaunchLogger.info("Running on platform: " + Platform.getPlatform().getPlatformName());

        // Check program args
        boolean devMode = argsList.contains("-DevMode");
        boolean useMT = argsList.contains("-BootMT");
        applicationEntry = defaultEntry;

        if (!devMode) {
            // Scan files
            LaunchLogger.info("Scanning files");
            var libsTasks = new HashMap<File, Runnable>();
            scanFiles("libs/", ".jar", true, file -> {
                try (var classLoader = new ExternalClassLoader(
                        file.getName(),
                        new URL[0], Main.class.getClassLoader(), LaunchClassLoader.launchCTM)
                ) {
                    LaunchLogger.info(" - " + file.getName());
                    classLoader.loadJar(file);
                    classLoaders.add(classLoader);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, libsTasks);
            var engineComponentsTasks = new HashMap<File, Runnable>();
            scanFiles("engine/", ".jar", true, file -> {
                LaunchLogger.info(" - " + file.getName());
                try (var classLoader = new ExternalClassLoader(
                        file.getName(),
                        new URL[0], Main.class.getClassLoader(), LaunchClassLoader.launchCTM)
                ) {
                    classLoader.loadJar(file);
                    classLoaders.add(classLoader);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, engineComponentsTasks);
            var gameComponentsTasks = new HashMap<File, Runnable>();
            scanFiles("eons/", ".jar", true, file -> {
                LaunchLogger.info(" - " + file.getName());
                try (var classLoader = new ExternalClassLoader(
                        file.getName(),
                        new URL[0], Main.class.getClassLoader(), LaunchClassLoader.launchCTM)
                ) {
                    classLoader.loadJar(file);
                    classLoaders.add(classLoader);
                    applicationEntry = findEntry(classLoader.findResource("META-INF/MANIFEST.MF"), defaultEntry);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, gameComponentsTasks);
            // Read files
            int totalFiles = libsTasks.size() + engineComponentsTasks.size() + gameComponentsTasks.size();
            AtomicInteger count = new AtomicInteger(0);
            ExecutorService mtPool = null;
            if (useMT) mtPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            LaunchLogger.info("Loading dependencies...");
            readFiles(libsTasks, totalFiles, count, mtPool);
            LaunchLogger.info("Loading engine components...");
            readFiles(engineComponentsTasks, totalFiles, count, mtPool);
            LaunchLogger.info("Loading game components...");
            readFiles(gameComponentsTasks, totalFiles, count, mtPool);
            if (useMT) mtPool.shutdown();
            // Init game
            LaunchLogger.info("Initializing game...");
            System.out.println("Bootstrap took " + (System.currentTimeMillis() - startTime) + "ms");
            LaunchClassLoader.INSTANCE.initKotlinObject(applicationEntry);
        } else {
            URL manifest = Main.class.getResource("/META-INF/MANIFEST.MF");
            assert manifest != null;
            ExternalClassLoader.invokeKotlinObjectField(Class.forName(findEntry(manifest, defaultEntry)));
        }
    }

    private static String findEntry(URL manifest, String defaultEntry) {
        String entry = defaultEntry;
        try {
            InputStreamReader ir = new InputStreamReader(manifest.openStream());
            BufferedReader br = new BufferedReader(ir);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Launch-Entry: ")) {
                    entry = line.substring(14);
                }
            }
        } catch (Exception ignored) {
        }
        LaunchLogger.info("Using Entry: " + entry);
        return entry;
    }

    private interface FileTask {
        void invoke(File file);
    }

    private static void readFiles(
            Map<File, Runnable> fileTasks,
            int total,
            AtomicInteger counter,
            ExecutorService useMT
    ) throws InterruptedException {
        var cdl = new CountDownLatch(fileTasks.size());
        for (var entry : fileTasks.entrySet()) {
            if (useMT != null) {
                useMT.submit(() -> {
                    entry.getValue().run();
                    counter.incrementAndGet();
                    cdl.countDown();
                });
            } else {
                entry.getValue().run();
                counter.incrementAndGet();
            }
        }
        if (useMT != null) cdl.await();
    }

    @SuppressWarnings({"ResultOfMethodCallIgnored", "CallToPrintStackTrace"})
    private static void scanFiles(
            String path,
            String suffix,
            boolean ignoreCase,
            FileTask action,
            Map<File, Runnable> files
    ) {
        File current = new File(path);
        if (!current.exists()) {
            try {
                current.getParentFile().mkdirs();
                current.createNewFile();
            } catch (Exception exception) {
                exception.printStackTrace();
            }
            return;
        }
        if (current.isDirectory()) {
            String[] list = current.list();
            if (list != null) {
                for (String child : list) {
                    File childFile = new File(path + child);
                    if (childFile.isDirectory()) scanFiles(path + child, suffix, ignoreCase, action, files);
                    else if (endWith(childFile.getName(), suffix, ignoreCase)) try {
                        files.put(childFile, () -> action.invoke(childFile));
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                }
            }
        } else if (endWith(current.getName(), suffix, ignoreCase)) try {
            files.put(current, () -> action.invoke(current));
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private static boolean endWith(String src, String suffix, boolean ignoreCase) {
        if (ignoreCase) return src.toLowerCase().endsWith(suffix.toLowerCase());
        else return src.endsWith(suffix);
    }

}
