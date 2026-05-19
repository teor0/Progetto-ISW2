package controller;

import model.ClassMetrics;
import model.CommitMetrics;
import model.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.logging.Logger;

/**
 * Computes class-level and commit-level metrics for every non-test Java source
 * file present in the repository at a release's boundary commit.
 *
 * <h3>Boundary commit and history window</h3>
 * <ul>
 *   <li><b>File list</b>: only files present in the boundary commit's tree.</li>
 *   <li><b>History window</b>: commits reachable from {@code boundary} but NOT
 *       from {@code prevBoundary} — i.e. the range {@code (prevBoundary, boundary]}.
 *       For the first release {@code prevBoundary} is {@code null} and the full
 *       history back to the root is used.</li>
 * </ul>
 *
 * <h3>CommitMetrics granularity</h3>
 * One {@link CommitMetrics} is produced per <b>(release, filePath, commit)</b>
 * triple — not per (release, commit).  This lets {@code CsvWriter} compute
 * AVG_NF/LA/LD/LT by averaging only over the commits that actually touched
 * each specific file, giving every class its own correct per-file averages.
 */
public class MetricsCollector implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(MetricsCollector.class.getName());

    private final Git        git;
    private final Repository repo;

    public MetricsCollector(String repoPath) throws IOException {
        this.git  = Git.open(new File(repoPath));
        this.repo = git.getRepository();
    }

    /**
     * Processes a single release and appends results to the two output lists.
     *
     * @param release        the release to process
     * @param prevBoundary   boundary commit of the immediately preceding release,
     *                       or {@code null} for the first release
     * @param classMetrics   output – one {@link ClassMetrics} per non-test Java file
     * @param commitMetrics  output – one {@link CommitMetrics} per (file, commit) pair
     */
    public void collect(Release release,
                        RevCommit  prevBoundary,
                        List<ClassMetrics>  classMetrics,
                        List<CommitMetrics> commitMetrics)
            throws IOException {

        RevCommit boundary = release.getBoundaryCommit();
        if (boundary == null) {
            LOGGER.warning("Release " + release.getName() + " has no boundary commit – skipping.");
            return;
        }

        // ── 1. Enumerate non-test Java files at the boundary snapshot ────────
        Set<String> javaFiles = listNonTestJavaFiles(boundary);
        if (javaFiles.isEmpty()) {
            LOGGER.info("Release " + release.getName() + ": no Java files found.");
            return;
        }
        LOGGER.info("Release " + release.getName() + ": " + javaFiles.size() + " Java files.");

        Map<String, FileAccumulator> accumulators = new LinkedHashMap<>();
        for (String f : javaFiles) accumulators.put(f, new FileAccumulator());

        // ── 3. Walk the release history window ───────────────────────────────
        try (RevWalk revWalk = new RevWalk(repo)) {
            revWalk.sort(RevSort.COMMIT_TIME_DESC, true);
            revWalk.markStart(revWalk.parseCommit(boundary));
            if (prevBoundary != null) {
                revWalk.markUninteresting(revWalk.parseCommit(prevBoundary));
            }

            for (RevCommit commit : revWalk) {

                List<DiffEntry> diffs = diffWithParent(commit);
                if (diffs == null) continue;

                int changeSetSize = diffs.size();

                for (DiffEntry diff : diffs) {
                    String path = effectivePath(diff);
                    if (!accumulators.containsKey(path)) continue;

                    // ── Compute hunk-level stats for this (file, commit) ─────
                    EditList edits   = getEdits(diff);
                    int      added   = 0;
                    int      deleted = 0;
                    for (Edit e : edits) {
                        added   += e.getLengthB();
                        deleted += e.getLengthA();
                    }
                    int lt = preImageLineCount(diff);

                    // ── Accumulate class-level metrics ───────────────────────
                    FileAccumulator acc = accumulators.get(path);
                    acc.authors.add(commit.getAuthorIdent().getEmailAddress());
                    acc.nr++;
                    acc.totalLocAdded   += added;
                    acc.maxLocAdded      = Math.max(acc.maxLocAdded, added);
                    acc.totalLocTouched += added + deleted;
                    int churn = Math.abs(added - deleted);
                    acc.totalChurn      += churn;
                    acc.maxChurn         = Math.max(acc.maxChurn, churn);
                    acc.totalChangeSet  += changeSetSize;
                    acc.maxChangeSet     = Math.max(acc.maxChangeSet, changeSetSize);
                    // Keep overwriting: because RevWalk is DESC, the last value
                    // written is the oldest commit that touched the file.
                    acc.firstCommitTime  = commit.getCommitTime();

                    // ── Emit one CommitMetrics per (release, file, commit) ───
                    CommitMetrics cm = new CommitMetrics(
                            release.getName(), path, commit.getName());
                    cm.setNf(changeSetSize);
                    cm.setLa(added);
                    cm.setLd(deleted);
                    cm.setLt(lt);
                    commitMetrics.add(cm);
                }
            }
        }

        LocalDate releaseDate = release.getReleaseDate();

        for (Map.Entry<String, FileAccumulator> entry : accumulators.entrySet()) {
            String          path = entry.getKey();
            FileAccumulator acc  = entry.getValue();

            ClassMetrics cm = new ClassMetrics(release.getName(), path);
            cm.setLoc(countLoc(boundary, path));
            cm.setNr(acc.nr);
            cm.setNAuth(acc.authors.size());
            cm.setLocAdded(acc.totalLocAdded);
            cm.setMaxLocAdded(acc.maxLocAdded);
            cm.setLocTouched(acc.totalLocTouched);
            cm.setChurn(acc.totalChurn);
            cm.setMaxChurn(acc.maxChurn);
            cm.setChangeSetSize(acc.totalChangeSet);
            cm.setMaxChangeSet(acc.maxChangeSet);

            long ageInDays = 0;
            if (acc.firstCommitTime > 0) {
                LocalDate firstDate = Instant.ofEpochSecond(acc.firstCommitTime)
                        .atZone(ZoneOffset.UTC).toLocalDate();
                ageInDays = Math.max(0,
                        java.time.temporal.ChronoUnit.DAYS.between(firstDate, releaseDate));
            }
            cm.setAgeInDays(ageInDays);
            cm.setWeightedAge(acc.totalLocTouched == 0 ? 0.0 : (double) ageInDays);

            classMetrics.add(cm);
        }
    }

    private Set<String> listNonTestJavaFiles(RevCommit commit) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            tw.setFilter(PathSuffixFilter.create(".java"));
            while (tw.next()) {
                String path = tw.getPathString();
                if (!isTestFile(path)) result.add(path);
            }
        }
        return result;
    }

    private static boolean isTestFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.endsWith("test.java")
                || lower.endsWith("tests.java")
                || lower.endsWith("testcase.java")
                || lower.contains("/mock/")
                || lower.contains("/stub/");
    }

    private List<DiffEntry> diffWithParent(RevCommit commit) {
        try (DiffFormatter df = newFormatter()) {
            df.setDetectRenames(true);
            AbstractTreeIterator oldTree = parentTree(commit);
            AbstractTreeIterator newTree = new RevTreeIter(repo, commit.getTree());
            return df.scan(oldTree, newTree);
        } catch (IOException e) {
            LOGGER.fine("Could not diff " + commit.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private AbstractTreeIterator parentTree(RevCommit commit) throws IOException {
        if (commit.getParentCount() == 0) return new EmptyTreeIterator();
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit parent = rw.parseCommit(commit.getParent(0).getId());
            return new RevTreeIter(repo, parent.getTree());
        }
    }

    private EditList getEdits(DiffEntry diff) throws IOException {
        try (DiffFormatter df = newFormatter()) {
            return df.toFileHeader(diff).toEditList();
        }
    }

    private int preImageLineCount(DiffEntry diff) {
        if (diff.getOldId() == null
                || diff.getChangeType() == DiffEntry.ChangeType.ADD) return 0;
        try {
            ObjectLoader loader = repo.open(diff.getOldId().toObjectId());
            return (int) new String(loader.getBytes(), StandardCharsets.UTF_8).lines().count();
        } catch (IOException e) {
            return 0;
        }
    }

    private int countLoc(RevCommit commit, String path) {
        try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (tw == null) return 0;
            ObjectLoader loader = repo.open(tw.getObjectId(0));
            return (int) new String(loader.getBytes(), StandardCharsets.UTF_8)
                    .lines().filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String effectivePath(DiffEntry diff) {
        return diff.getChangeType() == DiffEntry.ChangeType.DELETE
                ? diff.getOldPath() : diff.getNewPath();
    }

    private DiffFormatter newFormatter() {
        DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
        df.setRepository(repo);
        return df;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static class FileAccumulator {
        Set<String> authors      = new HashSet<>();
        int  nr                  = 0;
        int  totalLocAdded       = 0;
        int  maxLocAdded         = 0;
        int  totalLocTouched     = 0;
        int  totalChurn          = 0;
        int  maxChurn            = 0;
        int  totalChangeSet      = 0;
        int  maxChangeSet        = 0;
        int  firstCommitTime     = 0;
    }

    private static class RevTreeIter extends CanonicalTreeParser {
        RevTreeIter(Repository repo, RevTree tree) throws IOException {
            super(null, repo.newObjectReader(), tree);
        }
    }

    @Override
    public void close() { git.close(); }
}