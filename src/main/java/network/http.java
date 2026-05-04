package network;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;


import com.google.gson.Gson;

import static utils.Colors.*;
import static utils.Debug.*;
import utils.Config;

public class http {

    public static String httpGet(String urlStr) throws IOException {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader("config.json")) {   
            Config config = gson.fromJson(reader, Config.class);

            if (config.maxRetries <= 0) {
                throw new IOException("maxRetries must be > 0, got: " + config.maxRetries);
            }

            printDebug("GET " + urlStr);
            IOException lastError = null;

            for (int attempt = 1; attempt <= config.maxRetries; attempt++) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setConnectTimeout(config.connectTimeout);
                    conn.setReadTimeout(config.readTimeout);
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
                    if (attempt < config.maxRetries) {
                        System.out.println("  " + DIM + "Attempt " + attempt + "/" + config.maxRetries + " failed, retrying..." + RESET);
                        try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                }
            }

            throw lastError; 
        }
    }
}