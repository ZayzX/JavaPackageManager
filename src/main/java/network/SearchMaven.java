package network;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import static display.progressBar.*;
import static utils.Colors.*;
import static utils.Debug.*;
import static network.http.*;
import maven.maven.*;

public class SearchMaven {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static MavenArtifact searchMavenCentral(String query) throws IOException {
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

    public static int compareVersions(String a, String b) {
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

    public static void resolveDep(String name, String version, Map<String, Map<String, String>> registry,
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

    public static String groupIdToPath(String groupId) { return groupId.replace('.', '/'); }

    public static String findGroupId(Map<String, String> deps, String artifactId) {
        if (deps == null) return null;
        for (String key : deps.keySet()) {
            String[] p = key.split(":");
            if (p.length == 2 && p[1].equals(artifactId)) return p[0];
        }
        return null;
    }

    public static File ensureLibsDir() {
        File dir = new File("libs");
        if (!dir.exists() && !dir.mkdir()) throw new RuntimeException("Could not create libs/");
        return dir;
    }

    public static void downloadJar(MavenArtifact art, File libsDir) throws IOException {
        String urlStr = "https://repo1.maven.org/maven2/"
                + groupIdToPath(art.groupId) + "/"
                + art.artifactId + "/"
                + art.latestVersion + "/"
                + art.artifactId + "-" + art.latestVersion + ".jar";

        printDebug("URL: " + urlStr);
        File dest = new File(libsDir, art.artifactId + "-" + art.latestVersion + ".jar");
        downloadWithProgress(urlStr, dest);
    }

}
