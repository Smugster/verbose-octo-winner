package com.cybersoft.sctvshell.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * The thin shell's update logic (E7-14), free of Android so it can be tested on any JVM.
 *
 * A player release on the update host is:   manifest.json, manifest.sig (base64 ECDSA P-256 / SHA-256 signature of
 * manifest.json's exact bytes), and versions/<build>/<files>. The manifest lists the build (an integer that only goes up),
 * a version label, the entry page, the rollout percentage, the minimum shell version, and every file with its size and SHA-256.
 *
 * Rules: a manifest whose signature fails is ignored; a build not newer than the current one is ignored (no replaying old
 * signed releases); a build that was rolled back is never retried; a screen takes a build only if its device falls inside the
 * rollout percentage; every file must match its size and SHA-256 and stay inside its version folder. A downloaded build waits
 * until the shell says it's a safe moment, then goes on probation: it must report ready within the probation time, and the
 * app may restart at most twice during probation, or the shell rolls back to the previous build (or to the player inside the APK).
 */
public final class UpdateEngine {
    public interface Http { byte[] get(String url) throws IOException; }
    public static final int SHELL_VERSION = 1;

    public static final class Build { public final int build; public final String version, entry;
        Build(int build, String version, String entry) { this.build = build; this.version = version; this.entry = entry; }
        Map<String, Object> json() { Map<String, Object> m = new LinkedHashMap<>(); m.put("build", (double) build); m.put("version", version); m.put("entry", entry); return m; }
        static Build of(Object o) { if (!(o instanceof Map)) return null; Map<?, ?> m = (Map<?, ?>) o; return new Build(((Double) m.get("build")).intValue(), (String) m.get("version"), (String) m.get("entry")); } }

    private final File root, versions, stateFile; private final String baseUrl, deviceId; private final PublicKey key; private final Http http;
    private Build current, previous, pending; private int probationBuild = -1, probationBoots = 0; private long probationDeadline = 0, lastCheck = 0;
    private final List<Integer> bad = new ArrayList<>(); private String status = "starting";

    public UpdateEngine(File root, String baseUrl, String publicKeyBase64, String deviceId, Http http) throws GeneralSecurityException {
        this.root = root; this.versions = new File(root, "versions"); this.stateFile = new File(root, "state.json");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/"; this.deviceId = deviceId; this.http = http;
        this.key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())));
        versions.mkdirs(); load();
    }

    /* ── State ── */
    private void load() {
        try { if (!stateFile.exists()) return; Map<?, ?> m = (Map<?, ?>) MiniJson.parse(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
            current = Build.of(m.get("current")); previous = Build.of(m.get("previous")); pending = Build.of(m.get("pending"));
            if (m.get("probationBuild") != null) { probationBuild = ((Double) m.get("probationBuild")).intValue(); probationBoots = ((Double) m.get("probationBoots")).intValue(); probationDeadline = ((Double) m.get("probationDeadline")).longValue(); }
            for (Object b : (List<?>) m.get("bad")) bad.add(((Double) b).intValue());
            if (m.get("lastCheck") != null) lastCheck = ((Double) m.get("lastCheck")).longValue(); if (m.get("status") != null) status = (String) m.get("status");
        } catch (Exception e) { status = "state unreadable, starting fresh: " + e.getMessage(); current = previous = pending = null; }
    }
    private void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", current == null ? null : current.json()); m.put("previous", previous == null ? null : previous.json()); m.put("pending", pending == null ? null : pending.json());
        m.put("probationBuild", probationBuild < 0 ? null : (Object) (double) probationBuild); m.put("probationBoots", (double) probationBoots); m.put("probationDeadline", (double) probationDeadline);
        List<Object> b = new ArrayList<>(); for (int x : bad) b.add((double) x); m.put("bad", b); m.put("lastCheck", (double) lastCheck); m.put("status", status);
        try { File t = new File(root, "state.json.tmp"); Files.write(t.toPath(), MiniJson.stringify(m).getBytes(StandardCharsets.UTF_8)); Files.move(t.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException e) { status = "couldn't save state: " + e.getMessage(); }
    }

    /* ── Checking the host ── */
    /** Looks for a newer build and downloads it if this screen should take it. Returns a one-line status. */
    public synchronized String check(long now) {
        lastCheck = now;
        try {
            byte[] man = http.get(baseUrl + "manifest.json"), sig = http.get(baseUrl + "manifest.sig");
            Signature v = Signature.getInstance("SHA256withECDSA"); v.initVerify(key); v.update(man);
            if (!v.verify(Base64.getDecoder().decode(new String(sig, StandardCharsets.US_ASCII).trim()))) return set("refused an update: its signature is not Cybersoft's");
            Map<?, ?> m = (Map<?, ?>) MiniJson.parse(new String(man, StandardCharsets.UTF_8));
            int build = ((Double) m.get("build")).intValue(), rollout = m.get("rollout") == null ? 100 : ((Double) m.get("rollout")).intValue();
            int minShell = m.get("minShell") == null ? 1 : ((Double) m.get("minShell")).intValue();
            String version = String.valueOf(m.get("version")), entry = String.valueOf(m.get("entry"));
            if (current != null && build <= current.build) return set("up to date (build " + current.build + ")");
            if (bad.contains(build)) return set("build " + build + " was rolled back here; waiting for a newer one");
            if (pending != null && pending.build >= build) return set("build " + pending.build + " downloaded; waiting for a safe moment to switch");
            if (minShell > SHELL_VERSION) return set("build " + build + " needs a newer app (shell " + minShell + ")");
            if (!inRollout(build, rollout)) return set("build " + build + " isn't rolled out to this screen yet (" + rollout + "%)");
            File part = new File(versions, build + ".part"), dir = new File(versions, String.valueOf(build)); deleteTree(part); part.mkdirs();
            for (Object o : (List<?>) m.get("files")) { Map<?, ?> f = (Map<?, ?>) o; String path = (String) f.get("path");
                if (path.startsWith("/") || path.contains("..") || path.contains("\\")) { deleteTree(part); return set("refused build " + build + ": unsafe file path " + path); }
                byte[] data = http.get(baseUrl + "versions/" + build + "/" + path);
                if (data.length != ((Double) f.get("size")).longValue() || !hex(sha256(data)).equals(f.get("sha256"))) { deleteTree(part); return set("refused build " + build + ": " + path + " doesn't match its checksum"); }
                File out = new File(part, path); out.getParentFile().mkdirs(); Files.write(out.toPath(), data); }
            if (!new File(part, entry).exists()) { deleteTree(part); return set("refused build " + build + ": its entry page " + entry + " is missing"); }
            deleteTree(dir); Files.move(part.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE);
            if (pending != null && pending.build != build) deleteTree(new File(versions, String.valueOf(pending.build)));
            pending = new Build(build, version, entry);
            return set("build " + build + " (" + version + ") downloaded; waiting for a safe moment to switch");
        } catch (Exception e) { return set("couldn't check for updates: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    /** The same screens take each new build first: a fixed share of devices, chosen from the device ID. */
    boolean inRollout(int build, int rollout) {
        if (rollout >= 100) return true; if (rollout <= 0) return false;
        byte[] h = sha256((deviceId + ":rollout").getBytes(StandardCharsets.UTF_8)); return ((h[0] & 0xff) << 8 | (h[1] & 0xff)) % 100 < rollout;
    }

    /* ── Switching, probation and rollback ── */
    public synchronized boolean hasPending() { return pending != null; }
    /** Makes the downloaded build current and starts its probation. */
    public synchronized Build switchToPending(long now, long probationMs) {
        if (pending == null) return current;
        previous = current; current = pending; pending = null; probationBuild = current.build; probationBoots = 0; probationDeadline = now + probationMs;
        set("switched to build " + current.build + "; on probation until it reports ready"); return current;
    }
    /** The player reports it is running properly; ends its probation and tidies old builds. */
    public synchronized void ready(int build, long now) {
        if (current == null || build != current.build) return;
        if (probationBuild == build) { probationBuild = -1; probationBoots = 0; set("build " + build + " is running"); }
        File[] all = versions.listFiles(); if (all != null) for (File f : all) { String n = f.getName();
            if (!(n.equals(String.valueOf(current.build)) || (previous != null && n.equals(String.valueOf(previous.build))) || (pending != null && n.equals(String.valueOf(pending.build))))) deleteTree(f); }
    }
    public synchronized boolean onProbation() { return probationBuild >= 0; }
    public synchronized boolean probationExpired(long now) { return probationBuild >= 0 && now > probationDeadline; }
    /** Called when the app starts: a build that keeps the app restarting is rolled back after the third start. */
    public synchronized void onAppStart(long now, long probationMs) {
        if (probationBuild < 0) return; probationBoots++;
        if (probationBoots >= 3) rollback("the app restarted " + probationBoots + " times"); else { probationDeadline = now + probationMs; save(); }
    }
    /** Goes back to the previous build (or the player inside the APK) and never retries the failed one. */
    public synchronized Build rollback(String reason) {
        if (current == null) return null; int failed = current.build; if (!bad.contains(failed)) bad.add(failed);
        deleteTree(new File(versions, String.valueOf(failed)));
        current = previous; previous = null; probationBuild = -1; probationBoots = 0;
        set("build " + failed + " failed (" + reason + "); back to " + (current == null ? "the player in the app" : "build " + current.build)); return current;
    }

    /** The page to load: the current build's entry, or null for the player inside the APK. */
    public synchronized File entryFile() {
        if (current == null) return null; File f = new File(new File(versions, String.valueOf(current.build)), current.entry);
        return f.exists() ? f : null;
    }
    public synchronized Build current() { return current; }
    public synchronized String status() { return status; }
    public synchronized String infoJson() {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("shell", (double) SHELL_VERSION); m.put("build", current == null ? null : (Object) (double) current.build);
        m.put("version", current == null ? "in app" : current.version); m.put("previous", previous == null ? null : (Object) (double) previous.build);
        m.put("pending", pending == null ? null : (Object) (double) pending.build); m.put("onProbation", probationBuild >= 0); m.put("rolledBack", new ArrayList<Object>(bad)); m.put("lastCheck", (double) lastCheck); m.put("status", status);
        return MiniJson.stringify(m);
    }

    private String set(String s) { status = s; save(); return s; }
    static byte[] sha256(byte[] d) { try { return MessageDigest.getInstance("SHA-256").digest(d); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static void deleteTree(File f) { if (f == null || !f.exists()) return; File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }
}
