package utils;

import com.google.gson.Gson;

import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDate;

public class Debug {

    static Gson gson = new Gson();
    static LocalDate date = LocalDate.now();


    public static void printDebug(String msg) {
        try {

            FileReader reader = new FileReader("config.json");
            Config config = gson.fromJson(reader, Config.class);
            reader.close();

            if (config.debug) {
                System.out.println("[DEBUG] " + date + " : " + msg);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
