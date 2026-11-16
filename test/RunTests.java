import javax.tools.*;
import java.io.File;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Compiles the update logic with its tests and runs them:   java test/RunTests.java   (from thin-shell/) */
public class RunTests {
    public static void main(String[] a) throws Exception {
        File out = Files.createTempDirectory("shellclasses").toFile();
        List<String> srcs = new ArrayList<>(List.of("test/ShellTest.java"));
        for (File f : new File("android/app/src/main/java/com/cybersoft/sctvshell/core").listFiles()) srcs.add(f.getPath());
        List<String> args = new ArrayList<>(List.of("-d", out.getPath(), "-Xlint:none")); args.addAll(srcs);
        if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])) != 0) { System.out.println("compile failed"); System.exit(1); }
        try (URLClassLoader cl = new URLClassLoader(new URL[]{ out.toURI().toURL() })) {
            cl.loadClass("com.cybersoft.sctvshell.core.ShellTest").getMethod("main", String[].class).invoke(null, (Object) new String[]{ new File("tools").getAbsolutePath() });
        }
    }
}
