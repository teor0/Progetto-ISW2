package service;

import com.google.gson.*;
import kong.unirest.core.Unirest;
import model.Release;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import static utility.Constants.*;

public class JiraService {

    private static final Logger LOGGER=Logger.getLogger(JiraService.class.getName());

    /**
     * Fetches versions from Jira, filters out those without a release date,
     * sorts them chronologically, keeps only the first 40%, and saves the
     * result to VERSIONS_FILE_TRIMMED.
     */
    public static List<Release> retrieveRelease() {
        try {
            String response = Unirest.get(REST_API_PROJECT + PROJECT_NAME + "/versions")
                    .header("Accept", "application/json")
                    .asString()
                    .getBody();

            JsonArray array = JsonParser.parseString(response).getAsJsonArray();

            List<JsonObject> list = new ArrayList<>();
            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                JsonElement date = obj.get("releaseDate");
                if (date != null && !date.isJsonNull() && !date.getAsString().isEmpty())
                    list.add(obj);
            }
            list.sort(Comparator.comparing(o ->
                    LocalDate.parse(o.get("releaseDate").getAsString())));

            LOGGER.log(Level.INFO, "Versions with release date: " + list.size());

            int trimmedSize = (int) Math.ceil(list.size() * 0.40) + 1;
            list = list.subList(0, Math.min(trimmedSize, list.size()));

            LOGGER.log(Level.INFO, "Versions after trimming: " + list.size());

            JsonArray trimmedArray = new JsonArray();
            list.forEach(trimmedArray::add);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(
                    Paths.get(RELEASES_FILE),
                    gson.toJson(trimmedArray),
                    StandardCharsets.UTF_8
            );

            List<Release> releases = new ArrayList<>();
            for (JsonObject obj : list) {
                String name = obj.get("name").getAsString();
                LocalDate date = LocalDate.parse(obj.get("releaseDate").getAsString());
                releases.add(new Release(name, date, null));
            }
            return releases;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve/prepare versions", e);
            return Collections.emptyList();
        }
    }

    /**
     * Fetches all Bug tickets for PROJECT that are Closed/Resolved with
     * resolution=Fixed, handling pagination automatically.
     * Returns a list of issue keys, e.g. ["PROJECT-1234", ...]
     */
    public static List<String> retrieveFixedBugs() {
        List<String> issueKeys = new ArrayList<>();
        int startAt = 0;
        final int maxResults = 1000;
        final String jql =
                "project=" + PROJECT_NAME +
                        " AND issuetype=Bug" +
                        " AND status in (Closed, Resolved)" +
                        " AND resolution=Fixed";

        try {
            while (true) {
                String response = Unirest
                        .get(REST_API_SEARCH)
                        .queryString("jql", jql)
                        .queryString("startAt", startAt)
                        .queryString("maxResults", maxResults)
                        .queryString("fields", "key,status,resolution,issuetype,fixVersions,created,resolutiondate")
                        .header("Accept", "application/json")
                        .asString()
                        .getBody();

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                JsonArray issues = json.getAsJsonArray("issues");

                if (issues == null || issues.isEmpty()) break;

                for (JsonElement el : issues) {
                    issueKeys.add(el.getAsJsonObject().get("key").getAsString());
                }

                int total = json.get("total").getAsInt();
                startAt += issues.size();
                if (startAt >= total) break;
            }
            LOGGER.log(Level.INFO, "Fixed bugs found: " + issueKeys.size());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve bugs", e);
        }
        return issueKeys;
    }

}
