package service;

import com.google.gson.*;
import model.Release;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ReleaseService {

    /**
     * expected format in JSON: "releaseDate": "2026-10-01"
     */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * load releases from the JSON file.
     */
    public List<Release> loadFromFile(String jsonFilePath) throws IOException {
        List<Release> releases = new ArrayList<>();

        String content = Files.readString(Path.of(jsonFilePath));
        JsonArray jsonArray = JsonParser.parseString(content).getAsJsonArray();

        for (JsonElement element : jsonArray) {
            JsonObject obj = element.getAsJsonObject();

            String name = obj.get("name").getAsString();
            String dateStr = obj.get("releaseDate").getAsString();
            LocalDate date = LocalDate.parse(dateStr, FORMATTER);

            releases.add(new Release(name, date, null));
        }

        return releases;
    }
}