package display;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

import com.google.gson.Gson;

import static display.format.*;
import static utils.Colors.*;
import static utils.Debug.*;
import utils.Config;

public class progressBar {

    static Config config;

    static {
        try {
            Gson gson = new Gson();
            FileReader reader = new FileReader("config.json");
            config = gson.fromJson(reader, Config.class);
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void printProgress(String filename, long received, long total, long startMs) {
        int BAR_WIDTH = 28;
        long elapsed = Math.max(System.currentTimeMillis() - startMs, 1);
        double speed = (double) received / elapsed * 1000.0;
        String speedStr = formatBytes((long) speed) + "/s";

        String prefix = CYAN + BOLD + filename + RESET + "  ";

        if (total <= 0) {
            String bar = "[" + DIM + "?" + RESET + "]";
            System.out.printf("\r  %s%s  %s  %s",
                    prefix, bar, formatBytes(received), DIM + speedStr + RESET);
            return;
        }

        double pct = (double) received / total;
        int filled = (int) (pct * BAR_WIDTH);
        long etaSec = speed > 0 ? (long) ((total - received) / speed) : 0;
        String etaStr = etaSec > 0 ? "ETA " + formatTime(etaSec) : "done";

        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < BAR_WIDTH; i++) {
            if (i < filled) bar.append(GREEN + "█" + RESET);
            else if (i == filled) bar.append(YELLOW + "▓" + RESET);
            else bar.append(DIM + "░" + RESET);
        }
        bar.append("]");

        System.out.printf("\r  %s%s  %s%3d%%%s  %s  %s",
                prefix,
                bar,
                BOLD, (int) (pct * 100), RESET,
                DIM + formatBytes(received) + "/" + formatBytes(total) + RESET,
                DIM + speedStr + "  " + etaStr + RESET);
    }

    public static void downloadWithProgress(String urlStr, File dest) throws IOException {
        printDebug("Downloading: " + urlStr);
        IOException lastError = null;

        for (int attempt = 1; attempt <= config.maxRetries; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(config.connectTimeout);
                conn.setReadTimeout(config.readTimeout);

                int status = conn.getResponseCode();
                if (status != 200) throw new IOException("HTTP " + status);

                long total = conn.getContentLengthLong();
                long received = 0;
                long start = System.currentTimeMillis();
                byte[] buf = new byte[8192];

                try (InputStream in = conn.getInputStream();
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

                if (attempt < config.maxRetries) {
                    System.out.println("  " + DIM + "Retry..." + RESET);
                    try { Thread.sleep(1500L * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw lastError;
    }
}