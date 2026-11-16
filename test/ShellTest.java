package com.cybersoft.sctvshell.core;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Tests of the update logic against a real local web server and real releases made by tools/release.py. */
public final class ShellTest {
    static File site, key, src, state; static String base, pub, tools; static int passed = 0, failed = 0;
    static final UpdateEngine.Http HTTP = url -> { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(3000); c.setReadTimeout(5000);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url); try (InputStream in = c.getInputStream()) { return in.readAllBytes(); } };

    public static void main(String[] a) throws Exception {
        tools = a[0]; File tmp = Files.createTempDirectory("shelltest").toFile(); site = new File(tmp, "site"); state = new File(tmp, "device"); site.mkdirs();
        key = new File(tmp, "test.pem"); src = new File(tmp, "player"); src.mkdirs();
        run("python3", "-c", "from cryptography.hazmat.primitives.asymmetric import ec;from cryptography.hazmat.primitives import serialization as s;import base64;k=ec.generate_private_key(ec.SECP256R1());"
            + "open('" + key + "','wb').write(k.private_bytes(s.Encoding.PEM,s.PrivateFormat.PKCS8,s.NoEncryption()));print(base64.b64encode(k.public_key().public_bytes(s.Encoding.DER,s.PublicFormat.SubjectPublicKeyInfo)).decode())");
        pub = lastOut.trim();
        Files.writeString(new File(src, "index.html").toPath(), "<html><head><title>player</title></head><body><div id=stage></div></body></html>");
        Files.writeString(new File(src, "app.js").toPath(), "console.log('player')");
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> { File f = new File(site, ex.getRequestURI().getPath().substring(1)); byte[] b = f.isFile() ? Files.readAllBytes(f.toPath()) : null;
            ex.sendResponseHeaders(b == null ? 404 : 200, b == null ? -1 : b.length); if (b != null) ex.getResponseBody().write(b); ex.close(); });
        srv.start(); base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/";
        try { tests(); } finally { srv.stop(0); }
        System.out.println((failed == 0 ? "PASS" : "FAIL") + ": " + passed + " checks passed, " + failed + " failed"); System.exit(failed == 0 ? 0 : 1);
    }

    static UpdateEngine engine(String id) throws Exception { return new UpdateEngine(new File(state, id), base, pub, id, HTTP); }
    static void release(String... extra) throws Exception { List<String> c = new ArrayList<>(List.of("python3", tools + "/release.py", "--source", src.getPath(), "--site", site.getPath(), "--key", key.getPath())); c.addAll(List.of(extra)); run(c.toArray(new String[0])); }

    static void tests() throws Exception {
        long t = 1_000_000;
        UpdateEngine e = engine("screen-A");
        check("1 first start uses the player in the app", e.entryFile() == null && e.current() == null, e.status());
        check("1 no host yet: reports it couldn't check", e.check(t).startsWith("couldn't check"), e.status());

        release("--build", "2", "--version", "Two");
        String s = e.check(t);
        check("2 a new build downloads and waits", e.hasPending() && s.contains("build 2") && s.contains("waiting"), s);
        e.switchToPending(t, 60_000);
        check("2 switching puts it on probation", e.onProbation() && e.current().build == 2 && e.entryFile() != null, e.status());
        check("2 the hook is in the page", Files.readString(e.entryFile().toPath()).contains("sctvSafeToSwitch"), "");
        e.ready(2, t);
        check("2 ready ends probation", !e.onProbation() && e.status().contains("running"), e.status());
        check("3 nothing new: up to date", e.check(t).startsWith("up to date (build 2)"), e.status());

        release("--build", "3"); byte[] m = Files.readAllBytes(new File(site, "manifest.json").toPath());
        Files.writeString(new File(site, "manifest.json").toPath(), new String(m, StandardCharsets.UTF_8).replace("\"rollout\": 100", "\"rollout\": 99"));
        check("4 a tampered manifest is refused", e.check(t).contains("signature is not Cybersoft") && !e.hasPending(), e.status());

        release("--build", "4"); Files.writeString(new File(site, "versions/4/app.js").toPath(), "alert('swapped')");
        s = e.check(t);
        check("5 a file that doesn't match its checksum is refused", s.contains("doesn't match its checksum") && !e.hasPending() && !new File(state, "screen-A/versions/4.part").exists() && !new File(state, "screen-A/versions/4").exists(), s);

        release("--build", "5", "--broken"); e.check(t); e.switchToPending(t, 60_000);
        check("6 a broken build isn't expired before its deadline", !e.probationExpired(t + 59_000), "");
        check("6 the deadline passes", e.probationExpired(t + 61_000), "");
        e.rollback("it didn't report ready within 60 s");
        check("6 rollback returns to the previous build", e.current().build == 2 && e.status().contains("back to build 2") && !new File(state, "screen-A/versions/5").exists(), e.status());
        check("6 a rolled-back build is never retried", e.check(t).contains("build 5 was rolled back"), e.status());

        release("--build", "6", "--rollout", "0");
        check("7 rollout 0%: not taken", e.check(t).contains("isn't rolled out to this screen yet (0%)") && !e.hasPending(), e.status());
        int in = 0; for (int i = 0; i < 1000; i++) if (engine("screen-" + i).inRollout(7, 30)) in++;
        check("7 rollout 30% takes about 30% of 1,000 screens", in > 250 && in < 350, in + " of 1000");
        boolean same = true; for (int i = 0; i < 50; i++) { UpdateEngine x = engine("screen-" + i); if (x.inRollout(8, 10) && !x.inRollout(9, 30)) same = false; }
        check("7 the same screens go first (10% are inside 30%)", same, "");

        File oldMan = new File(site, "manifest.json"), oldSig = new File(site, "manifest.sig");
        release("--build", "7"); byte[] man7 = Files.readAllBytes(oldMan.toPath()), sig7 = Files.readAllBytes(oldSig.toPath());
        e.check(t); e.switchToPending(t, 60_000); e.ready(7, t);
        release("--build", "8"); e.check(t); e.switchToPending(t, 60_000); e.ready(8, t);
        Files.write(oldMan.toPath(), man7); Files.write(oldSig.toPath(), sig7);
        check("8 a replayed older signed release is ignored", e.check(t).startsWith("up to date (build 8)") && e.current().build == 8, e.status());

        release("--build", "9", "--min-shell", "2");
        check("9 a build needing a newer app is skipped", e.check(t).contains("needs a newer app (shell 2)") && !e.hasPending(), e.status());

        release("--build", "10"); e.check(t); e.switchToPending(t, 60_000);
        e.onAppStart(t, 60_000); e.onAppStart(t, 60_000);
        check("10 two restarts on probation: still on the new build", e.current().build == 10 && e.onProbation(), e.status());
        e.onAppStart(t, 60_000);
        check("10 the third restart rolls back", e.current().build == 8 && !e.onProbation() && e.status().contains("restarted 3 times"), e.status());

        Map<String, Object> evil = new LinkedHashMap<>(); evil.put("build", 11.0); evil.put("version", "evil"); evil.put("entry", "index.html"); evil.put("rollout", 100.0); evil.put("minShell", 1.0);
        Map<String, Object> f = new LinkedHashMap<>(); f.put("path", "../../escape.html"); f.put("size", 1.0); f.put("sha256", "00"); evil.put("files", List.of(f));
        Files.writeString(oldMan.toPath(), MiniJson.stringify(evil)); run("python3", new File(tools, "../test/sign.py").getPath(), key.getPath(), oldMan.getPath(), oldSig.getPath());
        check("11 a signed manifest with an escaping path is refused", e.check(t).contains("unsafe file path") && !e.hasPending(), e.status());

        UpdateEngine again = engine("screen-A");
        check("12 state survives a restart", again.current() != null && again.current().build == 8 && again.entryFile() != null && again.infoJson().contains("\"rolledBack\":[5,10]"), again.infoJson());
    }

    static String lastOut = "";
    static void run(String... cmd) throws Exception { Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); lastOut = new String(p.getInputStream().readAllBytes()); if (p.waitFor() != 0) throw new RuntimeException(String.join(" ", cmd) + "\n" + lastOut); }
    static void check(String name, boolean ok, String detail) { if (ok) passed++; else failed++; System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok || detail.isEmpty() ? "" : "  [" + detail + "]")); }
}
