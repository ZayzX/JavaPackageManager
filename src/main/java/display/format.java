package display;

public class format {

    public static String formatBytes(long bytes) {
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    public static String formatTime(long seconds) {
        if (seconds < 60)  return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m" + (seconds % 60) + "s";
        return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m";
    }

}
