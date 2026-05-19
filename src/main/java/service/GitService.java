package service;

import com.google.gson.*;
import model.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static utility.Constants.RELEASES_FILE;

public class GitService implements Closeable {

    private final Git git;
    private final Repository repository;

    public GitService(String repoPath) throws IOException {
        this.git = Git.open(new File(repoPath));
        this.repository = git.getRepository();
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

    public List<Release> constructReleases(List<Release> releases)
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
                .parseReader(new FileReader(RELEASES_FILE))
                .getAsJsonArray();

        Map<String, JsonObject> jsonMap = new HashMap<>();
        for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            jsonMap.put(obj.get("name").getAsString(), obj);
        }

        JsonArray updatedArray = new JsonArray();
        for (Release release : releases) {
            JsonObject obj = jsonMap.get(release.getName());
            if (obj != null) {
                obj.addProperty("lastCommit", release.getBoundaryCommit().getName());
                updatedArray.add(obj);
            }
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(
                Paths.get(RELEASES_FILE),
                gson.toJson(updatedArray),
                StandardCharsets.UTF_8
        );
        return releases;
    }

    public void checkoutCommit(RevCommit commit) throws GitAPIException {
        git.checkout()
                .setName(commit.getName())   // SHA-1 del commit
                .call();
    }

    /**
     * Checks out a named branch, restoring the working tree to a normal
     * (non-detached) state.  Call this at the end of the pipeline to leave
     * the repository clean.
     *
     * @param branchName  local branch name, e.g. {@code "master"} or {@code "main"}
     */
    public void checkoutBranch(String branchName) throws GitAPIException {
        git.checkout()
                .setName(branchName)
                .call();
    }


    public File getWorkTree() {
        return repository.getWorkTree();
    }

    @Override
    public void close() {
        git.close();
    }
}