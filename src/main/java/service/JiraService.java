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
            for (int i = 0; i < list.size(); i++) {
                JsonObject obj = list.get(i).deepCopy();
                obj.addProperty("releaseNumber", i + 1);
                trimmedArray.add(obj);
            }
            //list.forEach(trimmedArray::add);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(
                    Paths.get(RELEASES_FILE),
                    gson.toJson(trimmedArray),
                    StandardCharsets.UTF_8
            );

            List<Release> releases = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                JsonObject obj = list.get(i);
                String name = obj.get("name").getAsString();
                LocalDate date = LocalDate.parse(obj.get("releaseDate").getAsString());
                Release r =new Release(name, date, null,i);
                r.setReleaseNumber(i + 1);
                releases.add(r);
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
    public static JsonArray retrieveTickets() {
        JsonArray allIssues = new JsonArray();
        int startAt = 0;
        final int maxResults = 1000;
        final String jql =
                "project = " + PROJECT_NAME +
                        " AND issuetype = Bug" +
                        " AND status in (Closed, Resolved)" +
                        " AND resolution = Fixed";

        try {
            while (true) {
                String response = Unirest
                        .get(REST_API_SEARCH)
                        .queryString("jql", jql)
                        .queryString("startAt", startAt)
                        .queryString("maxResults", maxResults)
                        .queryString("fields", "key,created,resolutiondate,versions,fixVersions")
                        .header("Accept", "application/json")
                        .asString()
                        .getBody();

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();

                if (json.has("errorMessages")) {
                    LOGGER.severe("Jira error: " + json.get("errorMessages"));
                    break;
                }

                JsonArray issues = json.getAsJsonArray("issues");
                if (issues == null || issues.isEmpty()) break;

                for (JsonElement el : issues) {
                    JsonObject raw = el.getAsJsonObject();
                    JsonObject fields = raw.getAsJsonObject("fields");
                    JsonObject issue = new JsonObject();


                    issue.addProperty("key", raw.get("key").getAsString());
                    issue.addProperty("created", fields.get("created").getAsString());
                    JsonElement resolutionDate = fields.get("resolutiondate");
                    issue.addProperty("resolutiondate",
                            resolutionDate.isJsonNull() ? null : resolutionDate.getAsString());


                    // Affected versions (versions field in Jira)
                    JsonArray affectedVersions = new JsonArray();
                    JsonArray rawAffected = fields.getAsJsonArray("versions");
                    if (rawAffected != null) {
                        for (JsonElement v : rawAffected) {
                            affectedVersions.add(
                                    v.getAsJsonObject().get("name").getAsString()
                            );
                        }
                    }
                    issue.add("affectedVersions", affectedVersions);

                    // Fix versions
                    JsonArray fixVersions = new JsonArray();
                    JsonArray rawFix = fields.getAsJsonArray("fixVersions");
                    if (rawFix != null) {
                        for (JsonElement v : rawFix) {
                            fixVersions.add(
                                    v.getAsJsonObject().get("name").getAsString()
                            );
                        }
                    }
                    issue.add("fixVersions", fixVersions);

                    allIssues.add(issue);
                }

                int total = json.get("total").getAsInt();
                LOGGER.info("Fetched " + allIssues.size() + " / " + total);
                startAt += issues.size();
                if (startAt >= total) break;
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(
                    Paths.get(TICKETS_FILE),
                    gson.toJson(allIssues),
                    StandardCharsets.UTF_8
            );
            LOGGER.info("Fixed bugs saved: " + allIssues.size());

        } catch (Exception e) {
            LOGGER.severe("Failed to retrieve bugs: " + e.getMessage());
            e.printStackTrace();
        }

        return allIssues;
    }

    private static String getStringSafe(JsonObject obj, String field) {
        if (obj == null) return null;
        JsonElement el = obj.get(field);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }


}
