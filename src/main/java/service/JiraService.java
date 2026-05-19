package service;

import com.google.gson.*;
import kong.unirest.core.HttpResponse;
import kong.unirest.core.JsonNode;
import kong.unirest.core.Unirest;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static utility.Constants.*;
public class JiraService {

    private static final Logger LOGGER=Logger.getLogger(JiraService.class.getName());

    /*
    function used to retrive all the version of the project from Jira
     */
    public static void retrieveVersions(){
        try{
            String response=Unirest.get(REST_API_PROJECT+PROJECT_NAME+"/versions")
                    .header("Accept", "application/json")
                    .asString()
                    .getBody();
            Gson gson=new GsonBuilder().setPrettyPrinting().create();
            String prettyJson= gson.toJson(gson.fromJson(response, Object.class));
            Files.writeString(
                    Paths.get(VERSIONS_FILE),
                    prettyJson,
                    StandardCharsets.UTF_8
            );
            LOGGER.log(Level.INFO, "Versions saved");
        }catch(Exception e){
            LOGGER.log(Level.INFO, "Something went wrong");
            e.printStackTrace();
        }
    }

    public static void reorderJson(){
        try {
            JsonArray array = JsonParser
                    .parseReader(new FileReader(VERSIONS_FILE))
                    .getAsJsonArray();

            // order by releaseDate and remove release without releaseDate
            List<JsonObject> list = new ArrayList<>();
            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                JsonElement date = obj.get("releaseDate");

                if (date != null && !date.isJsonNull() && !date.getAsString().isEmpty())
                    list.add(obj);
            }
            list.sort(Comparator.comparing(o ->
                    LocalDate.parse(o.get("releaseDate").getAsString())
            ));
            // reconstruct
            JsonArray sortedArray = new JsonArray();
            for (JsonObject obj : list) {
                sortedArray.add(obj);
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(
                    Paths.get(VERSIONS_FILE_ORDERED),
                    gson.toJson(sortedArray),
                    StandardCharsets.UTF_8
            );
            LOGGER.log(Level.INFO,"Number of versions: " + sortedArray.size());
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "Something went wrong");
            e.printStackTrace();
        }
    }

    // function used to ignore the last 60% of release
    public static void trimRelease(){
        try {
            JsonArray array = JsonParser
                    .parseReader(new FileReader(VERSIONS_FILE_ORDERED))
                    .getAsJsonArray();
            int json_size=array.size();
            LOGGER.log(Level.INFO, "Number of release: " + json_size);

            int trimmedSize = ((int) Math.ceil(json_size * 0.40))+1;

            JsonArray trimmedArray = new JsonArray();
            for (int i = 0; i < trimmedSize; i++) {
                trimmedArray.add(array.get(i));
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(
                    Paths.get(VERSIONS_FILE_TRIMMED),
                    gson.toJson(trimmedArray),
                    StandardCharsets.UTF_8
            );
            LOGGER.log(Level.INFO, "New number of release: " + trimmedArray.size());
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "Something went wrong");
            e.printStackTrace();
        }
    }

    /*
    public static void countVersion(){
        try{
            JsonArray array = JsonParser
                    .parseReader(new FileReader(VERSIONS_FILE))
                    .getAsJsonArray();

            LOGGER.log(Level.INFO,"Number of versions: " + array.size());
        }catch (Exception e){
            LOGGER.log(Level.INFO, "Something went wrong");
            e.printStackTrace();
        }
    }*/

    public static void getVersionIssueCount(){
        //test con version 3.5.10
        HttpResponse<JsonNode> response = Unirest.get(REST_API_VERSION+"12349434"+"/relatedIssueCounts")
                .header("Accept", "application/json")
                .asJson();
        System.out.println(response.getBody());
    }

}
