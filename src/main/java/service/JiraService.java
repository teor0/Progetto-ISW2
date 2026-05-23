package service;

import com.google.gson.*;
import kong.unirest.core.Unirest;
import model.Release;
import model.Ticket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static utility.Constants.*;

public class JiraService {

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

            int trimmedSize = (int) Math.ceil(list.size() * 0.40) + 1;
            list = list.subList(0, Math.min(trimmedSize, list.size()));

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
            return Collections.emptyList();
        }
    }

    /**
     * Fetches all Bug tickets for PROJECT that are Closed/Resolved with
     * resolution=Fixed, handling pagination automatically.
     * Resolves each ticket's version strings to Release objects and
     * saves the raw JSON to TICKETS_FILE.
     *
     * @param releases chronologically-sorted, numbered release list
     * @return fully-populated tickets ready for ProportionService and SZZService
     */
    public static List<Ticket> retrieveTickets(List<Release> releases) {

        Map<String, Release> releaseByName = releases.stream()
                .collect(Collectors.toMap(Release::getName, r -> r));

        JsonArray allIssues = new JsonArray();
        List<Ticket> tickets = new ArrayList<>();
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
                    break;
                }

                JsonArray issues = json.getAsJsonArray("issues");
                if (issues == null || issues.isEmpty()) break;

                for (JsonElement el : issues) {
                    JsonObject raw = el.getAsJsonObject();
                    JsonObject fields = raw.getAsJsonObject("fields");

                    // ── Build raw JSON record for persistence ────────────────
                    JsonObject issue = new JsonObject();
                    issue.addProperty("key", raw.get("key").getAsString());
                    issue.addProperty("created", fields.get("created").getAsString());
                    JsonElement resolutionDate = fields.get("resolutiondate");
                    issue.addProperty("resolutiondate",
                            resolutionDate.isJsonNull() ? null : resolutionDate.getAsString());

                    JsonArray affectedVersions = new JsonArray();
                    JsonArray rawAffected = fields.getAsJsonArray("versions");
                    if (rawAffected != null) {
                        for (JsonElement v : rawAffected)
                            affectedVersions.add(v.getAsJsonObject().get("name").getAsString());
                    }
                    issue.add("affectedVersions", affectedVersions);

                    JsonArray fixVersions = new JsonArray();
                    JsonArray rawFix = fields.getAsJsonArray("fixVersions");
                    if (rawFix != null) {
                        for (JsonElement v : rawFix)
                            fixVersions.add(v.getAsJsonObject().get("name").getAsString());
                    }
                    issue.add("fixVersions", fixVersions);
                    allIssues.add(issue);

                    // ── Build and resolve Ticket object ──────────────────────
                    Ticket ticket = new Ticket(raw.get("key").getAsString());

                    ticket.setCreationDate(parseDate(fields.get("created").getAsString()));

                    if (!resolutionDate.isJsonNull())
                        ticket.setResolutionDate(parseDate(resolutionDate.getAsString()));

                    Release fv = firstMatchingRelease(fixVersions, releaseByName);
                    if (fv == null) continue;   // no usable fix version, skip
                    ticket.setFixVersion(fv);

                    if (ticket.getCreationDate() != null)
                        ticket.setOpeningVersion(releaseForDate(ticket.getCreationDate(), releases));
                    if (ticket.getOpeningVersion() == null) continue;

                    ticket.setInjectedVersion(firstMatchingRelease(affectedVersions, releaseByName));
                    tickets.add(ticket);
                }

                int total = json.get("total").getAsInt();
                startAt += issues.size();
                if (startAt >= total) break;
            }

            // Persist raw JSON
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(Paths.get(TICKETS_FILE), gson.toJson(allIssues), StandardCharsets.UTF_8);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return tickets;
    }


    private static Release firstMatchingRelease(JsonArray versionsArray,
                                                Map<String, Release> releaseByName) {
        if (versionsArray == null) return null;
        for (JsonElement el : versionsArray) {
            Release r = releaseByName.get(el.getAsString());
            if (r != null) return r;
        }
        return null;
    }

    private static Release releaseForDate(LocalDate date, List<Release> releases) {
        Release result = releases.get(0);
        for (Release r : releases) {
            if (!r.getReleaseDate().isAfter(date)) result = r;
            else break;
        }
        return result;
    }

    private static LocalDate parseDate(String raw) {
        try {
            return raw.contains("T")
                    ? LocalDate.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
                    : LocalDate.parse(raw);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


}
