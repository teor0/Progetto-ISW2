package service;

import com.google.gson.*;
import model.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;
import java.util.logging.Level;

import static utility.Constants.*;

public class GitService implements Closeable {

    private final Git git;
    private final Repository repository;

    public GitService(String repoPath) throws IOException {
        this.git = Git.open(new File(repoPath));
        this.repository = git.getRepository();
    }

    public RevCommit findLastCommitByTag(Release release) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            // try common tag name patterns
            String[] candidates = {
                    "release-" + release.getName(),
                    "v" + release.getName(),
                    release.getName()
            };

            for (String tagName : candidates) {
                Ref ref = repository.findRef(tagName);
                if (ref == null) continue;

                // peel in case it's an annotated tag
                ObjectId objectId = ref.getPeeledObjectId() != null
                        ? ref.getPeeledObjectId()
                        : ref.getObjectId();

                return revWalk.parseCommit(objectId);
            }
        }
        return null; // no matching tag found
    }

    public RevCommit findLastCommit(Release current, Release next)
            throws IOException {

        Instant lowerBound = current.getReleaseDate()
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        Instant upperBound = next == null ? Instant.now() :
                next.getReleaseDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        RevCommit bestCommit = null;

        try (RevWalk revWalk = new RevWalk(repository)) {
            //iterate in every ref: branches, tags, ecc.
            for (Ref ref : repository.getRefDatabase().getRefs()) {
                ObjectId objectId = ref.getPeeledObjectId() != null
                        ? ref.getPeeledObjectId()
                        : ref.getObjectId();

                RevObject obj;
                try { obj = revWalk.parseAny(objectId); }
                catch (IOException e) { continue; }
                if (!(obj instanceof RevCommit headCommit)) continue;

                revWalk.reset();
                revWalk.markStart(headCommit);

                for (RevCommit commit : revWalk) {
                    Instant commitTime = Instant.ofEpochSecond(commit.getCommitTime());

                    if (commitTime.isBefore(lowerBound)) break; // too old, stop
                    if (commitTime.isBefore(upperBound)) {      // within window
                        if (bestCommit == null ||
                                commit.getCommitTime() > bestCommit.getCommitTime()) {
                            bestCommit = commit;
                        }
                        break;
                    }
                }
            }
        }
        return bestCommit;
    }

    public List<Release> findLastCommit(List<Release> releases)
            throws IOException {
        for (int i = 0; i < releases.size(); i++) {
            // the last release is extra and used only to compute the boundary commit of the actual last release.
            if (i == releases.size() - 1)
                break;

            Release current = releases.get(i);
            Release next = releases.get(i + 1);
            current.setBoundaryCommit(this.findLastCommit(current, next));
        }

        // remove releases with null boundary commit
        releases.removeIf(r -> r.getBoundaryCommit() == null);

        // rewrite the JSON file
        JsonArray array = JsonParser
                .parseReader(new FileReader(VERSIONS_FILE_TRIMMED))
                .getAsJsonArray();

        Map<String, JsonObject> jsonMap = new HashMap<>();
        for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            jsonMap.put(obj.get("name").getAsString(), obj);
        }

        JsonArray updatedArray = new JsonArray();
        for (Release release : releases) {
            JsonObject obj = jsonMap.get(release.getName());
            if (obj != null){
                obj.addProperty("lastCommit", release.getBoundaryCommit().getName());
                updatedArray.add(obj);
            }
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(
                Paths.get(VERSIONS_FILE_FINAL),
                gson.toJson(updatedArray),
                StandardCharsets.UTF_8
        );
        return releases;
    }

    public static void trimRelease(){
        try {
            JsonArray array = JsonParser
                    .parseReader(new FileReader(VERSIONS_FILE_ORDERED))
                    .getAsJsonArray();
            int json_size=array.size();

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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void checkoutCommit(RevCommit commit) throws GitAPIException {
        git.checkout()
                .setName(commit.getName())   // SHA-1 del commit
                .call();

        System.out.println("  → Checkout su: " + commit.getName()
                + " [" + commit.getShortMessage() + "]");
    }

    public File getWorkTree() {
        return repository.getWorkTree();
    }

    @Override
    public void close() {
        git.close();
    }
}