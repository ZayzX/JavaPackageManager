package json;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import static utils.Debug.*;

public class json {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class PackageJson {
        public String name;
        public String version;
        public Map<String, String> scripts;
        public Map<String, String> dependencies;
        public Map<String, Map<String, String>> registry;
    }

    public static PackageJson readPackageJson() throws IOException {
        File file = new File("../package.json");
        if (!file.exists()) throw new FileNotFoundException("package.json not found.");
        try (FileReader r = new FileReader(file)) { return GSON.fromJson(r, PackageJson.class); }
    }

    public static void writePackageJson(PackageJson pkg) throws IOException {
        try (FileWriter w = new FileWriter("../package.json")) { GSON.toJson(pkg, w); }
        printDebug("package.json written");
    }

}
