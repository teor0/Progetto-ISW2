package service;

import com.google.gson.*;
import kong.unirest.core.HttpResponse;
import kong.unirest.core.Unirest;

import java.util.logging.*;

public class SonarCloudService {

    private static final Logger LOGGER = Logger.getLogger(SonarCloudService.class.getName());
    private static final String BASE_URL = "https://sonarcloud.io";

    private final String token;
    private final String organizationKey;   // e.g. "apache"
    private final String projectKey;        // e.g. "apache_zookeeper"

    public SonarCloudService(String organizationKey, String projectKey, String token) {
        this.organizationKey = organizationKey;
        this.projectKey      = projectKey;
        this.token           = token != null ? token
                : System.getenv().getOrDefault("SONAR_TOKEN", "");
    }

    /**
     * Returns the number of open code smells for the given file at the given
     * release / branch.
     *
     * @param filePath    repo-relative file path (e.g. {@code src/main/java/Foo.java}).
     * @param branchName  SonarCloud branch name that corresponds to the release;
     *                    pass {@code null} to query the main branch.
     * @return smell count, or {@code 0} if the file/branch is not found on Sonar.
     */
    public int getSmellCount(String filePath, String branchName) {
        try {
            // SonarCloud uses the component key format:  projectKey:filePath
            String componentKey = projectKey + ":" + filePath;

            var request = Unirest.get(BASE_URL + "/api/issues/search")
                    .header("Authorization", "Bearer " + token)
                    .queryString("componentKeys",  componentKey)
                    .queryString("organization",   organizationKey)
                    .queryString("types",          "CODE_SMELL")
                    .queryString("statuses",       "OPEN,CONFIRMED,REOPENED")
                    .queryString("ps",             "1");   // only need the total

            if (branchName != null && !branchName.isBlank()) {
                request = request.queryString("branch", branchName);
            }

            HttpResponse<String> response = request.asString();

            if (!response.isSuccess()) {
                LOGGER.warning("SonarCloud API returned HTTP " + response.getStatus()
                        + " for " + filePath);
                return 0;
            }

            JsonObject json  = JsonParser.parseString(response.getBody()).getAsJsonObject();
            JsonObject paging = json.getAsJsonObject("paging");
            if (paging == null) return 0;

            return paging.get("total").getAsInt();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to fetch smell count for " + filePath, e);
            return 0;
        }
    }

    /**
     * Convenience overload – queries the main branch.
     */
    public int getSmellCount(String filePath) {
        return getSmellCount(filePath, null);
    }
}
