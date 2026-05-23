package service;

import com.google.gson.*;
import model.Release;
import model.Ticket;
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
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static utility.Constants.RELEASES_FILE;

public class GitService implements Closeable {


    private static final Logger LOGGER = Logger.getLogger(GitService.class.getName());


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

    /**
     * Scans the full Git history and associates each commit to the ticket
     * whose key appears in the commit message.
     *
     * @param tickets  fully-resolved ticket list (after ProportionService)
     */
    public void linkCommitsToTickets(List<Ticket> tickets) // no boundary parameter
            throws IOException {

        Map<Pattern, Ticket> patternToTicket = new HashMap<>();
        for (Ticket t : tickets) {
            Pattern p = Pattern.compile(
                    "\\b" + Pattern.quote(t.getIssueId()) + "\\b",
                    Pattern.CASE_INSENSITIVE
            );
            patternToTicket.put(p, t);
        }

        try (RevWalk revWalk = new RevWalk(repository)) {
            // Mark ALL refs as start points — branches, tags, everything
            for (Ref ref : repository.getRefDatabase().getRefs()) {
                ObjectId id = ref.getPeeledObjectId() != null
                        ? ref.getPeeledObjectId()
                        : ref.getObjectId();
                try {
                    revWalk.markStart(revWalk.parseCommit(id));
                } catch (Exception e) {
                    // ref may not point to a commit (e.g. a tag on a blob)
                }
            }

            for (RevCommit commit : revWalk) {
                String message = commit.getFullMessage();
                for (Map.Entry<Pattern, Ticket> entry : patternToTicket.entrySet()) {
                    if (entry.getKey().matcher(message).find()) {
                        entry.getValue().addCommit(commit);
                    }
                }
            }
        }

        long linked = tickets.stream().filter(t -> !t.getCommits().isEmpty()).count();
        LOGGER.info("Linked commits to " + linked + "/" + tickets.size() + " tickets.");
    }


    public File getWorkTree() {
        return repository.getWorkTree();
    }

    @Override
    public void close() {
        git.close();
    }
}