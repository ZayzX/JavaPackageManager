import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import utils.Config;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static utils.Colors.*;
import static utils.Debug.printDebug;

public class Jpm {

    static class PackageJson {
        String name;
        String version;
        Map<String, String> scripts;
        Map<String, String> dependencies;
        Map<String, Map<String, String>> registry;
    }

    static class MavenArtifact {
        String groupId;
        String artifactId;
        String latestVersion;
        String description;
        String url;
    }

    static final int    CONNECT_TIMEOUT = 15_000;
    static final int    READ_TIMEOUT    = 30_000;
    static final int    MAX_RETRIES     = 3;
    static final Gson   GSON            = new GsonBuilder().setPrettyPrinting().create();



    static String httpGet(String urlStr) throws IOException {
        printDebug("GET " + urlStr);
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setInstanceFollowRedirects(true);

                int status = conn.getResponseCode();
                printDebug("HTTP " + status + " (attempt " + attempt + ")");
                if (status != 200) throw new IOException("HTTP " + status);

                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    printDebug("Response: " + sb.length() + " chars");
                    return sb.toString();
                }
            } catch (IOException e) {
                lastError = e;
                printDebug("Attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < MAX_RETRIES) {
                    System.out.println("  " + DIM + "Attempt " + attempt + "/" + MAX_RETRIES + " failed, retrying..." + RESET);
                    try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw lastError;
    }

    static void downloadWithProgress(String urlStr, File dest) throws IOException {
        printDebug("Downloading: " + urlStr);
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);

                int status = conn.getResponseCode();
                if (status != 200) throw new IOException("HTTP " + status);

                long total    = conn.getContentLengthLong();
                long received = 0;
                long start    = System.currentTimeMillis();
                byte[] buf    = new byte[8192];

                printDebug("Content-Length: " + total + " bytes");

                try (InputStream  in  = conn.getInputStream();
                     OutputStream out = new FileOutputStream(dest)) {

                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        received += read;
                        printProgress(dest.getName(), received, total, start);
                    }
                }

                System.out.println();
                printDebug("Download complete: " + dest.getAbsolutePath());
                return;

            } catch (IOException e) {
                lastError = e;
                System.out.println();
                printDebug("Attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < MAX_RETRIES) {
                    System.out.println("  " + DIM + "Attempt " + attempt + "/" + MAX_RETRIES + " failed, retrying..." + RESET);
                    try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw lastError;
    }

    static void printProgress(String filename, long received, long total, long startMs) {
        int    BAR_WIDTH = 28;
        long   elapsed   = Math.max(System.currentTimeMillis() - startMs, 1);
        double speed     = (double) received / elapsed * 1000.0;
        String speedStr  = formatBytes((long) speed) + "/s";

        String prefix    = CYAN + BOLD + filename + RESET + "  ";

        if (total <= 0) {
            String bar = "[" + DIM + "?" + RESET + "]";
            System.out.printf("\r  %s%s  %s  %s",
                    prefix, bar, formatBytes(received), DIM + speedStr + RESET);
            return;
        }

        double pct      = (double) received / total;
        int    filled   = (int) (pct * BAR_WIDTH);
        long   etaSec   = speed > 0 ? (long) ((total - received) / speed) : 0;
        String etaStr   = etaSec > 0 ? "ETA " + formatTime(etaSec) : "done";

        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < BAR_WIDTH; i++) {
            if      (i < filled)         bar.append(GREEN + "█" + RESET);
            else if (i == filled)        bar.append(YELLOW + "▓" + RESET);
            else                         bar.append(DIM + "░" + RESET);
        }
        bar.append("]");

        System.out.printf("\r  %s%s  %s%3d%%%s  %s  %s",
                prefix,
                bar,
                BOLD, (int)(pct * 100), RESET,
                DIM + formatBytes(received) + "/" + formatBytes(total) + RESET,
                DIM + speedStr + "  " + etaStr + RESET);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    static String formatTime(long seconds) {
        if (seconds < 60)  return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m" + (seconds % 60) + "s";
        return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m";
    }

    static MavenArtifact searchMavenCentral(String query) throws IOException {
        System.out.println("  " + DIM + "Trying search.maven.org..." + RESET);
        try {
            MavenArtifact art = searchViaLegacyApi(query);
            if (art != null) return art;
        } catch (IOException e) {
            System.out.println("  " + DIM + "Fallback to central.sonatype.com..." + RESET);
        }
        return searchViaSonatypeApi(query);
    }

    static MavenArtifact searchViaLegacyApi(String query) throws IOException {
        String encoded = query.replace(" ", "+");
        String body    = httpGet("https://search.maven.org/solrsearch/select?q=a:" + encoded + "&rows=5&wt=json");
        JsonArray docs = GSON.fromJson(body, JsonObject.class)
                .getAsJsonObject("response").getAsJsonArray("docs");

        if (docs == null || docs.size() == 0) return null;

        for (int i = 0; i < docs.size(); i++) {
            JsonObject doc = docs.get(i).getAsJsonObject();
            if (doc.get("a").getAsString().equals(query)) {
                return artifactFromLegacyDoc(doc);
            }
        }
        return artifactFromLegacyDoc(docs.get(0).getAsJsonObject());
    }

    static MavenArtifact artifactFromLegacyDoc(JsonObject doc) {
        MavenArtifact art = new MavenArtifact();
        art.groupId       = doc.get("g").getAsString();
        art.artifactId    = doc.get("a").getAsString();
        art.latestVersion = doc.get("latestVersion").getAsString();
        printDebug("Artifact: " + art.groupId + ":" + art.artifactId + ":" + art.latestVersion);
        return art;
    }

    static MavenArtifact searchViaSonatypeApi(String query) throws IOException {
        String encoded  = query.replace(" ", "+");
        String body     = httpGet("https://central.sonatype.com/api/v1/search?q=" + encoded + "&name=" + encoded + "&size=5");
        JsonArray comps = GSON.fromJson(body, JsonObject.class).getAsJsonArray("components");

        if (comps == null || comps.size() == 0) return null;

        for (int i = 0; i < comps.size(); i++) {
            JsonObject comp = comps.get(i).getAsJsonObject();
            if (comp.get("name").getAsString().equals(query)) return artifactFromSonatypeComp(comp);
        }
        return artifactFromSonatypeComp(comps.get(0).getAsJsonObject());
    }

    static MavenArtifact artifactFromSonatypeComp(JsonObject comp) {
        MavenArtifact art = new MavenArtifact();
        art.groupId       = comp.get("namespace").getAsString();
        art.artifactId    = comp.get("name").getAsString();
        art.latestVersion = comp.get("version").getAsString();
        return art;
    }

    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseVersionPart(pa[i]) : 0;
            int nb = i < pb.length ? parseVersionPart(pb[i]) : 0;
            if (na != nb) return na - nb;
        }
        return 0;
    }

    static int parseVersionPart(String part) {
        try { return Integer.parseInt(part.replaceAll("[^0-9].*", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    static void resolveDep(String name, String version, Map<String, Map<String, String>> registry,
                           Map<String, String> resolved, Set<String> visiting) {
        if (visiting.contains(name)) {
            System.out.println(YELLOW + "Warning: circular dependency on " + name + ", skipping." + RESET);
            return;
        }
        if (resolved.containsKey(name)) {
            String existing = resolved.get(name);
            if (existing.equals(version)) return;
            System.out.println(YELLOW + "Conflict: " + name + " (" + existing + " vs " + version + ")" + RESET);
            if (compareVersions(version, existing) > 0) {
                printDebug("Upgrading " + name + ": " + existing + " → " + version);
                resolved.put(name, version);
            }
            return;
        }
        resolved.put(name, version);
        visiting.add(name);
        if (registry != null && registry.containsKey(name))
            for (Map.Entry<String, String> dep : registry.get(name).entrySet())
                resolveDep(dep.getKey(), dep.getValue(), registry, resolved, visiting);
        visiting.remove(name);
    }

    static String[] buildCommand(String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win"))
            return new String[]{"cmd.exe", "/c", command};
        return new String[]{"/bin/sh", "-c", command};
    }

    static String groupIdToPath(String groupId) { return groupId.replace('.', '/'); }

    static String findGroupId(Map<String, String> deps, String artifactId) {
        if (deps == null) return null;
        for (String key : deps.keySet()) {
            String[] p = key.split(":");
            if (p.length == 2 && p[1].equals(artifactId)) return p[0];
        }
        return null;
    }

    static File ensureLibsDir() {
        File dir = new File("libs");
        if (!dir.exists() && !dir.mkdir()) throw new RuntimeException("Could not create libs/");
        return dir;
    }

    static void downloadJar(MavenArtifact art, File libsDir) throws IOException {
        String urlStr = "https://repo1.maven.org/maven2/"
                + groupIdToPath(art.groupId) + "/"
                + art.artifactId + "/"
                + art.latestVersion + "/"
                + art.artifactId + "-" + art.latestVersion + ".jar";

        printDebug("URL: " + urlStr);
        File dest = new File(libsDir, art.artifactId + "-" + art.latestVersion + ".jar");
        downloadWithProgress(urlStr, dest);
    }

    static PackageJson readPackageJson() throws IOException {
        File file = new File("package.json");
        if (!file.exists()) throw new FileNotFoundException("package.json not found.");
        try (FileReader r = new FileReader(file)) { return GSON.fromJson(r, PackageJson.class); }
    }

    static void writePackageJson(PackageJson pkg) throws IOException {
        try (FileWriter w = new FileWriter("package.json")) { GSON.toJson(pkg, w); }
        printDebug("package.json written");
    }

    static void printHelp() {
        System.out.println(BOLD + "jpm — Java Package Manager" + RESET);
        System.out.println();
        System.out.println(BOLD + "Usage:" + RESET + " jpm <command> [args]");
        System.out.println();
        System.out.println(BOLD + "Project" + RESET);
        System.out.println("  " + CYAN + "init" + RESET + "                     Create a new package.json");
        System.out.println("  " + CYAN + "run" + RESET + " <script>            Run a script from package.json");
        System.out.println();
        System.out.println(BOLD + "Packages" + RESET);
        System.out.println("  " + CYAN + "install" + RESET + "                  Install all deps from package.json");
        System.out.println("  " + CYAN + "install" + RESET + " <name>           Search and install from Maven Central");
        System.out.println("  " + CYAN + "remove" + RESET + "  <artifactId>     Remove a dep and delete its jar");
        System.out.println("  " + CYAN + "update" + RESET + "  <artifactId>     Update a dep to its latest version");
        System.out.println("  " + CYAN + "update-all" + RESET + "               Update every dep to its latest version");
        System.out.println("  " + CYAN + "search" + RESET + "  <query>          Search Maven Central (no install)");
        System.out.println("  " + CYAN + "info" + RESET + "    <artifactId>     Show details about a package");
        System.out.println();
        System.out.println(BOLD + "Inspection" + RESET);
        System.out.println("  " + CYAN + "list" + RESET + "                     List deps and their install status");
        System.out.println("  " + CYAN + "outdated" + RESET + "                 Show deps with available updates");
        System.out.println("  " + CYAN + "tree" + RESET + "                     Show the full dependency tree");
        System.out.println("  " + CYAN + "doctor" + RESET + "                   Check for issues in the project");
        System.out.println();
        System.out.println(BOLD + "Maintenance" + RESET);
        System.out.println("  " + CYAN + "clean" + RESET + "                    Delete the libs/ directory");
        System.out.println("  " + CYAN + "debug" + RESET + "                    Toggle debug mode");
    }

    public static void main(String[] args) {

        if (args.length == 0) { printHelp(); return; }

        printDebug("Command: " + String.join(" ", args));

        switch (args[0]) {

            case "debug": {
                try {
                    File file = new File("config.json");
                    Config config;
                    if (!file.exists()) {
                        config = new Config();
                        config.debug = false;
                        try (FileWriter w = new FileWriter(file)) { GSON.toJson(config, w); }
                    }
                    try (FileReader r = new FileReader(file)) { config = GSON.fromJson(r, Config.class); }
                    config.debug = !config.debug;
                    try (FileWriter w = new FileWriter(file)) { GSON.toJson(config, w); }
                    System.out.println("Debug: " + (config.debug
                            ? GREEN + BOLD + "enabled" + RESET
                            : DIM + "disabled" + RESET));
                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "init": {
                File existing = new File("package.json");
                if (existing.exists()) {
                    System.out.println(YELLOW + "package.json already exists." + RESET);
                    return;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name",         "untitled-project");
                data.put("version",      "1.0.0");
                data.put("main",         "Main");
                data.put("scripts",      Map.of("start", "java -jar untitled-project.jar"));
                data.put("dependencies", new LinkedHashMap<>());
                try (FileWriter w = new FileWriter("package.json")) {
                    GSON.toJson(data, w);
                    printDebug("package.json written");
                    System.out.println(GREEN + "✓" + RESET + " Initialized project → package.json");
                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "run": {
                if (args.length < 2) { System.out.println("Usage: jpm run <script>"); return; }
                try {
                    PackageJson pkg = readPackageJson();
                    if (pkg.scripts == null || !pkg.scripts.containsKey(args[1])) {
                        System.out.println(RED + "Script not found: " + args[1] + RESET);
                        if (pkg.scripts != null) {
                            System.out.println("Available scripts: " + String.join(", ", pkg.scripts.keySet()));
                        }
                        return;
                    }
                    String command = pkg.scripts.get(args[1]);
                    System.out.println(DIM + "$ " + command + RESET);
                    printDebug("Shell: " + Arrays.toString(buildCommand(command)));
                    int code = new ProcessBuilder(buildCommand(command)).inheritIO().start().waitFor();
                    printDebug("Exit code: " + code);
                    if (code != 0) System.out.println(RED + "Exited with code: " + code + RESET);
                } catch (Exception e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "search": {
                if (args.length < 2) { System.out.println("Usage: jpm search <query>"); return; }
                String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                System.out.println("Searching for " + BOLD + "\"" + query + "\"" + RESET + " ...");
                try {
                    String encoded = query.replace(" ", "+");
                    String body    = httpGet("https://search.maven.org/solrsearch/select?q=a:" + encoded + "&rows=10&wt=json");
                    JsonArray docs = GSON.fromJson(body, JsonObject.class)
                            .getAsJsonObject("response").getAsJsonArray("docs");

                    if (docs == null || docs.size() == 0) {
                        System.out.println("No results found.");
                        return;
                    }

                    System.out.println();
                    for (int i = 0; i < docs.size(); i++) {
                        JsonObject doc = docs.get(i).getAsJsonObject();
                        String g = doc.get("g").getAsString();
                        String a = doc.get("a").getAsString();
                        String v = doc.get("latestVersion").getAsString();
                        System.out.println("  " + CYAN + BOLD + a + RESET
                                + "  " + DIM + g + RESET
                                + "  " + GREEN + v + RESET);
                    }

                    System.out.println();
                    System.out.println(DIM + "Install with: jpm install <artifactId>" + RESET);
                } catch (IOException e) {
                    System.err.println(RED + "Search failed: " + e.getMessage() + RESET);
                }
                break;
            }

            case "info": {
                if (args.length < 2) { System.out.println("Usage: jpm info <artifactId>"); return; }
                String query = args[1];
                System.out.println("Fetching info for " + BOLD + query + RESET + " ...");
                try {
                    String body = httpGet("https://search.maven.org/solrsearch/select?q=a:" + query + "&rows=1&wt=json&core=gav");
                    JsonArray docs = GSON.fromJson(body, JsonObject.class)
                            .getAsJsonObject("response").getAsJsonArray("docs");

                    if (docs == null || docs.size() == 0) { System.out.println("Not found."); return; }

                    JsonObject doc = docs.get(0).getAsJsonObject();
                    String groupId    = doc.get("g").getAsString();
                    String artifactId = doc.get("a").getAsString();
                    String version    = doc.get("v") != null
                            ? doc.get("v").getAsString()
                            : doc.get("latestVersion") != null
                              ? doc.get("latestVersion").getAsString()
                              : "unknown";

                    System.out.println();
                    System.out.println("  " + BOLD + CYAN + artifactId + RESET);
                    System.out.println("  " + DIM + "Group:    " + RESET + groupId);
                    System.out.println("  " + DIM + "Version:  " + RESET + GREEN + version + RESET);
                    System.out.println("  " + DIM + "Maven:    " + RESET
                            + "https://repo1.maven.org/maven2/"
                            + groupIdToPath(groupId) + "/" + artifactId + "/");
                    System.out.println("  " + DIM + "Search:   " + RESET
                            + "https://search.maven.org/artifact/" + groupId + "/" + artifactId);
                    System.out.println();
                    System.out.println(DIM + "  package.json dependency key:" + RESET);
                    System.out.println("  " + CYAN + "\"" + groupId + ":" + artifactId + "\": \"" + version + "\"" + RESET);
                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "list": {
                try {
                    PackageJson pkg = readPackageJson();
                    if (pkg.dependencies == null || pkg.dependencies.isEmpty()) {
                        System.out.println("No dependencies in package.json.");
                        return;
                    }
                    System.out.println(BOLD + "Dependencies (" + pkg.dependencies.size() + "):" + RESET);
                    System.out.println();
                    for (Map.Entry<String, String> dep : pkg.dependencies.entrySet()) {
                        String[] parts   = dep.getKey().split(":");
                        String artifactId = parts.length == 2 ? parts[1] : dep.getKey();
                        File jar = new File("libs", artifactId + "-" + dep.getValue() + ".jar");
                        boolean installed = jar.exists();
                        String status = installed
                                ? GREEN + "✓ installed" + RESET
                                : RED   + "✗ missing"   + RESET;
                        String sizeStr = installed
                                ? DIM + "  " + formatBytes(jar.length()) + RESET
                                : "";
                        System.out.println("  " + CYAN + BOLD + artifactId + RESET
                                + "  " + DIM + dep.getValue() + RESET
                                + "  " + status + sizeStr);
                        printDebug("Jar: " + jar.getAbsolutePath() + " exists=" + installed);
                    }
                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "outdated": {
                try {
                    PackageJson pkg = readPackageJson();
                    if (pkg.dependencies == null || pkg.dependencies.isEmpty()) {
                        System.out.println("No dependencies.");
                        return;
                    }

                    System.out.println("Checking for updates...");
                    System.out.println();

                    boolean anyOutdated = false;

                    for (Map.Entry<String, String> dep : pkg.dependencies.entrySet()) {
                        String[] parts    = dep.getKey().split(":");
                        String artifactId = parts.length == 2 ? parts[1] : dep.getKey();
                        String current    = dep.getValue();

                        System.out.print("  " + DIM + artifactId + "..." + RESET + "\r");

                        try {
                            MavenArtifact art = searchMavenCentral(artifactId);
                            if (art == null) {
                                System.out.println("  " + YELLOW + artifactId + RESET + DIM + "  (not found on Maven Central)" + RESET);
                                continue;
                            }

                            String latest = art.latestVersion;
                            printDebug(artifactId + ": current=" + current + " latest=" + latest);

                            if (compareVersions(latest, current) > 0) {
                                anyOutdated = true;
                                System.out.println("  " + CYAN + BOLD + artifactId + RESET
                                        + "  " + RED   + current + RESET
                                        + "  →  "
                                        + GREEN + latest + RESET);
                            } else {
                                System.out.println("  " + DIM + artifactId + "  " + current + "  (up to date)" + RESET);
                            }
                        } catch (IOException e) {
                            System.out.println("  " + YELLOW + artifactId + RESET + DIM + "  (check failed: " + e.getMessage() + ")" + RESET);
                        }
                    }

                    if (!anyOutdated) {
                        System.out.println();
                        System.out.println(GREEN + "All dependencies are up to date." + RESET);
                    } else {
                        System.out.println();
                        System.out.println(DIM + "Run jpm update <artifactId> or jpm update-all to upgrade." + RESET);
                    }

                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "tree": {
                try {
                    PackageJson pkg = readPackageJson();
                    if (pkg.dependencies == null || pkg.dependencies.isEmpty()) {
                        System.out.println("No dependencies.");
                        return;
                    }

                    String projectName = pkg.name != null ? pkg.name : "project";
                    System.out.println(BOLD + projectName + RESET + " " + DIM + (pkg.version != null ? pkg.version : "") + RESET);

                    List<Map.Entry<String, String>> deps = new ArrayList<>(pkg.dependencies.entrySet());

                    for (int i = 0; i < deps.size(); i++) {
                        Map.Entry<String, String> dep = deps.get(i);
                        boolean last      = (i == deps.size() - 1);
                        String  connector = last ? "└── " : "├── ";
                        String[] parts    = dep.getKey().split(":");
                        String artifactId = parts.length == 2 ? parts[1] : dep.getKey();
                        String groupId    = parts.length == 2 ? parts[0] : "";
                        File   jar        = new File("libs", artifactId + "-" + dep.getValue() + ".jar");
                        String installed  = jar.exists() ? GREEN + " ✓" + RESET : RED + " ✗" + RESET;

                        System.out.println(DIM + connector + RESET
                                + CYAN + BOLD + artifactId + RESET
                                + " " + DIM + dep.getValue() + RESET
                                + installed);

                        if (pkg.registry != null && pkg.registry.containsKey(artifactId)) {
                            Map<String, String> transitive = pkg.registry.get(artifactId);
                            String indent = last ? "    " : "│   ";
                            List<String> tKeys = new ArrayList<>(transitive.keySet());
                            for (int j = 0; j < tKeys.size(); j++) {
                                boolean tLast = (j == tKeys.size() - 1);
                                String  tConn = tLast ? "└── " : "├── ";
                                System.out.println(DIM + indent + tConn + RESET
                                        + tKeys.get(j)
                                        + " " + DIM + transitive.get(tKeys.get(j)) + RESET);
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "doctor": {
                System.out.println(BOLD + "Running diagnostics..." + RESET);
                System.out.println();
                boolean allGood = true;

                File pkgFile = new File("package.json");
                if (!pkgFile.exists()) {
                    System.out.println(RED + "  ✗ package.json not found" + RESET);
                    allGood = false;
                } else {
                    System.out.println(GREEN + "  ✓ package.json found" + RESET);
                    try {
                        PackageJson pkg = readPackageJson();

                        if (pkg.name == null || pkg.name.isEmpty()) {
                            System.out.println(YELLOW + "  ⚠ package.json: \"name\" is missing" + RESET);
                            allGood = false;
                        }
                        if (pkg.version == null || pkg.version.isEmpty()) {
                            System.out.println(YELLOW + "  ⚠ package.json: \"version\" is missing" + RESET);
                            allGood = false;
                        }

                        if (pkg.dependencies != null && !pkg.dependencies.isEmpty()) {
                            int missing = 0;
                            for (Map.Entry<String, String> dep : pkg.dependencies.entrySet()) {
                                String[] parts    = dep.getKey().split(":");
                                String artifactId = parts.length == 2 ? parts[1] : dep.getKey();
                                File   jar        = new File("libs", artifactId + "-" + dep.getValue() + ".jar");
                                if (!jar.exists()) {
                                    System.out.println(RED + "  ✗ Missing jar: libs/" + jar.getName() + RESET);
                                    missing++;
                                    allGood = false;
                                }
                            }
                            if (missing == 0) System.out.println(GREEN + "  ✓ All jars present in libs/" + RESET);
                            else System.out.println(DIM + "    Run jpm install to fix." + RESET);
                        } else {
                            System.out.println(DIM + "  - No dependencies declared" + RESET);
                        }

                        if (pkg.scripts == null || pkg.scripts.isEmpty()) {
                            System.out.println(YELLOW + "  ⚠ No scripts defined in package.json" + RESET);
                        } else {
                            System.out.println(GREEN + "  ✓ Scripts: " + String.join(", ", pkg.scripts.keySet()) + RESET);
                        }

                    } catch (IOException e) {
                        System.out.println(RED + "  ✗ package.json is invalid: " + e.getMessage() + RESET);
                        allGood = false;
                    }
                }

                File libsDir = new File("libs");
                if (libsDir.exists()) {
                    File[] jars    = libsDir.listFiles((d, n) -> n.endsWith(".jar"));
                    long totalSize = 0;
                    if (jars != null) for (File j : jars) totalSize += j.length();
                    printDebug("libs/ contains " + (jars != null ? jars.length : 0) + " jar(s)");
                    System.out.println(GREEN + "  ✓ libs/ found — "
                            + (jars != null ? jars.length : 0) + " jar(s), "
                            + formatBytes(totalSize) + RESET);
                } else {
                    System.out.println(YELLOW + "  ⚠ libs/ directory does not exist (run jpm install)" + RESET);
                }

                System.out.println();
                if (allGood) System.out.println(GREEN + BOLD + "Everything looks good." + RESET);
                else         System.out.println(YELLOW + BOLD + "Some issues were found." + RESET);
                break;
            }

            case "remove": {
                if (args.length < 2) { System.out.println("Usage: jpm remove <artifactId>"); return; }
                String target = args[1];
                printDebug("Removing: " + target);
                try {
                    PackageJson pkg = readPackageJson();
                    if (pkg.dependencies == null) { System.out.println("No dependencies."); return; }

                    String keyToRemove = null, version = null;
                    for (String key : pkg.dependencies.keySet()) {
                        if (key.endsWith(":" + target)) {
                            keyToRemove = key;
                            version     = pkg.dependencies.get(key);
                            break;
                        }
                    }

                    if (keyToRemove == null) {
                        System.out.println(RED + "Dependency not found: " + target + RESET);
                        return;
                    }

                    pkg.dependencies.remove(keyToRemove);
                    writePackageJson(pkg);
                    System.out.println(GREEN + "✓" + RESET + " Removed from package.json: " + keyToRemove);

                    File jar = new File("libs", target + "-" + version + ".jar");
                    printDebug("Jar path: " + jar.getAbsolutePath());
                    if (jar.exists()) {
                        jar.delete();
                        System.out.println(GREEN + "✓" + RESET + " Deleted: libs/" + jar.getName());
                    }
                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "update": {
                if (args.length < 2) { System.out.println("Usage: jpm update <artifactId>"); return; }
                updateDep(args[1]);
                break;
            }

            case "update-all": {
                try {
                    PackageJson pkg = readPackageJson();
                    if (pkg.dependencies == null || pkg.dependencies.isEmpty()) {
                        System.out.println("No dependencies.");
                        return;
                    }
                    List<String> artifactIds = new ArrayList<>();
                    for (String key : pkg.dependencies.keySet()) {
                        String[] parts = key.split(":");
                        artifactIds.add(parts.length == 2 ? parts[1] : key);
                    }
                    System.out.println("Updating " + artifactIds.size() + " dependenc(ies)...");
                    System.out.println();
                    for (String id : artifactIds) updateDep(id);
                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            case "clean": {
                File libsDir = new File("libs");
                if (!libsDir.exists()) { System.out.println("libs/ is already empty."); return; }
                File[] jars = libsDir.listFiles((d, n) -> n.endsWith(".jar"));
                int count = jars != null ? jars.length : 0;
                printDebug("Deleting " + count + " jar(s)");
                if (jars != null) for (File jar : jars) jar.delete();
                libsDir.delete();
                System.out.println(GREEN + "✓" + RESET + " Cleaned libs/ — " + count + " jar(s) deleted.");
                break;
            }

            case "install": {
                if (args.length >= 2) {
                    String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    System.out.println("Searching Maven Central for " + BOLD + "\"" + query + "\"" + RESET + " ...");
                    System.out.println();
                    try {
                        MavenArtifact art = searchMavenCentral(query);
                        if (art == null) {
                            System.out.println(RED + "Not found: " + query + RESET);
                            return;
                        }
                        System.out.println();
                        System.out.println("  " + BOLD + CYAN + art.artifactId + RESET
                                + "  " + DIM + art.groupId + RESET
                                + "  " + GREEN + art.latestVersion + RESET);
                        System.out.println();
                        downloadJar(art, ensureLibsDir());
                        System.out.println(GREEN + BOLD + "✓ Installed" + RESET + "  "
                                + art.artifactId + "-" + art.latestVersion + ".jar  →  libs/");

                        File pkgFile = new File("package.json");
                        if (pkgFile.exists()) {
                            PackageJson pkg = readPackageJson();
                            if (pkg.dependencies == null) pkg.dependencies = new LinkedHashMap<>();
                            String key = art.groupId + ":" + art.artifactId;
                            pkg.dependencies.put(key, art.latestVersion);
                            writePackageJson(pkg);
                            System.out.println(DIM + "Saved to package.json: " + key + " @ " + art.latestVersion + RESET);
                        }
                    } catch (IOException e) {
                        System.err.println(RED + "Install failed: " + e.getMessage() + RESET);
                    }
                    return;
                }

                try {
                    PackageJson pkg = readPackageJson();
                    if (pkg.dependencies == null || pkg.dependencies.isEmpty()) {
                        System.out.println("No dependencies in package.json.");
                        return;
                    }

                    Map<String, String> resolved = new HashMap<>();
                    for (Map.Entry<String, String> entry : pkg.dependencies.entrySet()) {
                        String[] parts = entry.getKey().split(":");
                        if (parts.length != 2) { System.out.println("Invalid format: " + entry.getKey()); continue; }
                        resolveDep(parts[1], entry.getValue(), pkg.registry, resolved, new HashSet<>());
                    }

                    printDebug("Resolved " + resolved.size() + " dep(s)");
                    System.out.println("Installing " + BOLD + resolved.size() + RESET + " dep(s)...");
                    System.out.println();

                    File libsDir = ensureLibsDir();
                    int ok = 0, fail = 0;

                    for (Map.Entry<String, String> dep : resolved.entrySet()) {
                        String artifactId = dep.getKey();
                        String version    = dep.getValue();
                        String groupId    = findGroupId(pkg.dependencies, artifactId);

                        if (groupId == null) {
                            System.out.println(YELLOW + "Unknown groupId for: " + artifactId + ", skipping." + RESET);
                            fail++; continue;
                        }

                        MavenArtifact art = new MavenArtifact();
                        art.groupId       = groupId;
                        art.artifactId    = artifactId;
                        art.latestVersion = version;

                        try {
                            downloadJar(art, libsDir);
                            System.out.println(GREEN + "✓ " + RESET + artifactId);
                            ok++;
                        } catch (IOException e) {
                            System.out.println(RED + "✗ " + artifactId + ": " + e.getMessage() + RESET);
                            fail++;
                        }
                        System.out.println();
                    }

                    System.out.println(BOLD + "Done" + RESET + " — "
                            + GREEN + ok + " installed" + RESET + ", "
                            + (fail > 0 ? RED : DIM) + fail + " failed" + RESET + ".");
                } catch (IOException e) {
                    System.err.println(RED + "Error: " + e.getMessage() + RESET);
                }
                break;
            }

            default:
                System.out.println(RED + "Unknown command: " + args[0] + RESET);
                System.out.println();
                printHelp();
        }
    }

    static void updateDep(String target) {
        System.out.println("Checking " + BOLD + target + RESET + " ...");
        try {
            PackageJson pkg = readPackageJson();
            if (pkg.dependencies == null) { System.out.println("No dependencies."); return; }

            String keyToUpdate = null, currentVersion = null;
            for (String key : pkg.dependencies.keySet()) {
                if (key.endsWith(":" + target)) {
                    keyToUpdate     = key;
                    currentVersion  = pkg.dependencies.get(key);
                    break;
                }
            }

            if (keyToUpdate == null) {
                System.out.println(RED + "Not in package.json: " + target
                        + ". Use jpm install " + target + " first." + RESET);
                return;
            }

            printDebug("Current: " + currentVersion);
            MavenArtifact art = searchMavenCentral(target);
            if (art == null) { System.out.println(RED + "Not found on Maven Central." + RESET); return; }

            printDebug("Latest: " + art.latestVersion);

            if (compareVersions(art.latestVersion, currentVersion) <= 0) {
                System.out.println(DIM + target + " is already up to date (" + currentVersion + ")." + RESET);
                return;
            }

            System.out.println("  " + RED + currentVersion + RESET + "  →  " + GREEN + art.latestVersion + RESET);
            System.out.println();

            File oldJar = new File("libs", target + "-" + currentVersion + ".jar");
            if (oldJar.exists()) {
                printDebug("Deleting old jar: " + oldJar.getName());
                oldJar.delete();
            }

            downloadJar(art, ensureLibsDir());
            pkg.dependencies.put(keyToUpdate, art.latestVersion);
            writePackageJson(pkg);
            System.out.println(GREEN + BOLD + "✓ Updated" + RESET + " " + target + " → " + art.latestVersion);

        } catch (IOException e) {
            System.err.println(RED + "Update failed: " + e.getMessage() + RESET);
        }
    }
}