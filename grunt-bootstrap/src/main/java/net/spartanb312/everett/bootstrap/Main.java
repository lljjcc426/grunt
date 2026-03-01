package net.spartanb312.everett.bootstrap;

import java.util.Arrays;

public class Main {

    private static final String clientEntry = "net.spartanb312.grunteon.obfuscator.ClientEntry";
    private static final String serverEntry = "net.spartanb312.grunteon.obfuscator.ServerEntry";
    public static String[] args;

    public static void main(String[] args) throws Exception {
        Main.args = args;
        var serverMode = Arrays.asList(args).contains("-server");
        var entry = serverMode ? serverEntry : clientEntry;
        if (serverMode) System.out.println("Running on server mode");
        // TODO scan plugins
        ExternalClassLoader.invokeKotlinObjectField(Class.forName(entry));
    }

}
