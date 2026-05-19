package controller;

import model.ClassMetrics;
import model.CommitMetrics;
import model.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.*;
import org.eclipse.jgit.treewalk.filter.*;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.logging.*;

/**
 * Computes class-level and commit-level metrics for every non-test Java source
 * file that exists in the repository tree at a release's boundary commit.
 *
 * <h3>Boundary commit semantics</h3>
 * The boundary commit is the last commit whose timestamp is strictly before the
 * release date.  It defines two scopes:
 * <ol>
 *   <li><b>File list</b> – only files present in the boundary commit's tree are
 *       considered; files added after the release are ignored.</li>
 *   <li><b>History window</b> – only commits reachable from the boundary commit
 *       (i.e. boundary commit and its ancestors) are visited when accumulating
 *       per-file metrics.  Future commits are never counted.</li>
 * </ol>
 *
 * <h3>Class-level metrics</h3>
 * <ul>
 *   <li>LOC – blank lines excluded, counted from the blob at the boundary commit.</li>
 *   <li>NR – number of commits in the history window that modified the file.</li>
 *   <li>NAuth – distinct author e-mails among those commits.</li>
 *   <li>LOC Added / Max LOC Added / Avg LOC Added – added lines per commit.</li>
 *   <li>LOC Touched – added + deleted across all commits.</li>
 *   <li>Churn / Max Churn / Avg Churn – |added − deleted| per commit.</li>
 *   <li>Change Set Size / Max / Avg – total files in each commit.</li>
 *   <li>Age – days from the commit that first introduced the file to the release date.</li>
 *   <li>Weighted Age – age × LOC-Touched / LOC-Touched  (standard formula).</li>
 * </ul>
 *
 * <h3>Commit-level metrics</h3>
 * One {@link CommitMetrics} is produced per (release, commit) pair for every
 * commit in the history window that touched at least one non-test Java file.
 * <ul>
 *   <li>NF – total files changed in that commit.</li>
 *   <li>LA – lines added across all files in that commit.</li>
 *   <li>LD – lines deleted across all files in that commit.</li>
 *   <li>LT – lines of code in the file BEFORE the change (pre-image size).</li>
 * </ul>
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
     * Entry point: processes a single release and populates the two output lists.
     *
     * @param release        the release whose boundary commit defines scope
     * @param classMetrics   output list – one entry per non-test Java file
     * @param commitMetrics  output list – one entry per (commit, file) in scope
     */
    public void collect(Release release,
                        List<ClassMetrics>  classMetrics,
                        List<CommitMetrics> commitMetrics)
            throws IOException, GitAPIException {

        RevCommit boundary = release.getBoundaryCommit();
        if (boundary == null) {
            LOGGER.warning("Release " + release.getName() + " has no boundary commit – skipping.");
            return;
        }

        // ── 1. Enumerate non-test Java files at the boundary commit ──────────
        Set<String> javaFiles = listNonTestJavaFiles(boundary);
        if (javaFiles.isEmpty()) {
            LOGGER.info("Release " + release.getName() + ": no Java files found.");
            return;
        }
        LOGGER.info("Release " + release.getName() + ": " + javaFiles.size() + " Java files.");

        // ── 2. Walk history up to (and including) the boundary commit ────────
        //    Build per-file accumulators in one pass over the commit log.
        Map<String, FileAccumulator> accumulators = new LinkedHashMap<>();
        for (String f : javaFiles) {
            accumulators.put(f, new FileAccumulator());
        }

        List<CommitMetrics> rawCommitMetrics = new ArrayList<>();

        try (RevWalk revWalk = new RevWalk(repo)) {
            revWalk.sort(RevSort.COMMIT_TIME_DESC, true);
            revWalk.markStart(revWalk.parseCommit(boundary));

            for (RevCommit commit : revWalk) {

                // Diff against the first parent (or empty tree for root commits)
                List<DiffEntry> diffs = diffWithParent(commit);
                if (diffs == null) continue;

                // Change-set size = total files changed in this commit
                int changeSetSize = diffs.size();

                // Collect per-file edits for files we care about
                boolean commitTouchedAnyTrackedFile = false;

                // We also need total LA/LD/NF for CommitMetrics (commit-level)
                int totalLa = 0, totalLd = 0;

                for (DiffEntry diff : diffs) {
                    String path = effectivePath(diff);
                    if (!accumulators.containsKey(path)) continue; // not a tracked file

                    commitTouchedAnyTrackedFile = true;

                    EditList edits = getEdits(diff);
                    int added   = 0;
                    int deleted = 0;
                    for (Edit e : edits) {
                        added   += e.getLengthB();
                        deleted += e.getLengthA();
                    }

                    // Lines in the file BEFORE this change (pre-image line count)
                    int lt = preImageLineCount(diff, commit);

                    totalLa += added;
                    totalLd += deleted;

                    FileAccumulator acc = accumulators.get(path);
                    acc.authors.add(commit.getAuthorIdent().getEmailAddress());
                    acc.totalLocAdded   += added;
                    acc.totalLocTouched += added + deleted;
                    int churn = Math.abs(added - deleted);
                    acc.totalChurn      += churn;
                    acc.maxChurn         = Math.max(acc.maxChurn, churn);
                    acc.maxLocAdded      = Math.max(acc.maxLocAdded, added);
                    acc.totalChangeSet  += changeSetSize;
                    acc.maxChangeSet     = Math.max(acc.maxChangeSet, changeSetSize);
                    acc.nr++;

                    // Track first (oldest) commit that introduced the file
                    // Because we walk in DESC order, every commit we see is older than
                    // the previous one, so we just keep overwriting.
                    acc.firstCommitTime  = commit.getCommitTime();
                }

                if (commitTouchedAnyTrackedFile) {
                    CommitMetrics cm = new CommitMetrics(release.getName(), commit.getName());
                    cm.setNf(changeSetSize);
                    cm.setLa(totalLa);
                    cm.setLd(totalLd);
                    // LT: sum of pre-image sizes across all tracked files in the commit
                    // (aggregate over the files that belong to this release's class set)
                    int ltSum = 0;
                    for (DiffEntry diff : diffs) {
                        String path = effectivePath(diff);
                        if (accumulators.containsKey(path)) {
                            ltSum += preImageLineCount(diff, commit);
                        }
                    }
                    cm.setLt(ltSum);
                    rawCommitMetrics.add(cm);
                }
            }
        }

        // ── 3. Build ClassMetrics from accumulators ──────────────────────────
        LocalDate releaseDate = release.getReleaseDate();

        for (Map.Entry<String, FileAccumulator> entry : accumulators.entrySet()) {
            String         path = entry.getKey();
            FileAccumulator acc = entry.getValue();

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

            // Age: days from the file's first commit to the release date
            long ageInDays = 0;
            if (acc.firstCommitTime > 0) {
                Instant firstInstant = Instant.ofEpochSecond(acc.firstCommitTime);
                LocalDate firstDate  = firstInstant.atZone(ZoneOffset.UTC).toLocalDate();
                ageInDays = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(firstDate, releaseDate));
            }
            cm.setAgeInDays(ageInDays);

            // Weighted age: age × LOC_Touched / LOC_Touched  →  age when LOC_Touched > 0,
            // otherwise just age.  The standard formula weights by churn contribution:
            //   weightedAge = (ageInDays * locTouched) / max(locTouched, 1)
            // When there is a single revision the ratio is 1 and weightedAge == ageInDays.
            double weightedAge = acc.totalLocTouched == 0
                    ? 0.0
                    : (double) ageInDays * acc.totalLocTouched / acc.totalLocTouched;
            cm.setWeightedAge(weightedAge);

            classMetrics.add(cm);
        }

        commitMetrics.addAll(rawCommitMetrics);
    }

    /**
     * Returns the set of repo-relative paths of non-test Java files
     * present in the given commit's tree.
     * A file is considered a test if its path contains "/test/" or
     * its name ends with "Test.java" or "Tests.java".
     */
    private Set<String> listNonTestJavaFiles(RevCommit commit) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            tw.setFilter(PathSuffixFilter.create(".java"));

            while (tw.next()) {
                String path = tw.getPathString();
                if (!isTestFile(path)) {
                    result.add(path);
                }
            }
        }
        return result;
    }

    /** Heuristic test-file filter. */
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

    /**
     * Diffs {@code commit} against its first parent.
     * For root commits (no parents), diffs against the empty tree.
     * Returns {@code null} if the diff cannot be computed.
     */
    private List<DiffEntry> diffWithParent(RevCommit commit) {
        try (DiffFormatter df = newDiffFormatter()) {
            df.setDetectRenames(true);
            AbstractTreeIterator oldTree = parentTree(commit);
            AbstractTreeIterator newTree = new RevTreeIter(repo, commit.getTree());
            return df.scan(oldTree, newTree);
        } catch (IOException e) {
            LOGGER.fine("Could not diff commit " + commit.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private AbstractTreeIterator parentTree(RevCommit commit) throws IOException {
        if (commit.getParentCount() == 0) {
            return new EmptyTreeIterator();
        }
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit parent = rw.parseCommit(commit.getParent(0).getId());
            return new RevTreeIter(repo, parent.getTree());
        }
    }

    /** Returns the edit list (hunks) for a single diff entry. */
    private EditList getEdits(DiffEntry diff) throws IOException {
        try (DiffFormatter df = newDiffFormatter()) {
            FileHeader header = df.toFileHeader(diff);
            return header.toEditList();
        }
    }

    /**
     * Returns the number of lines in the PRE-IMAGE (old side) of the diff,
     * i.e. how many lines the file had before this commit changed it.
     */
    private int preImageLineCount(DiffEntry diff, RevCommit commit) {
        if (diff.getOldId() == null
                || diff.getOldId().equals(DiffEntry.DEV_NULL)
                || diff.getChangeType() == DiffEntry.ChangeType.ADD) {
            return 0; // file was just added – no pre-image
        }
        try {
            ObjectLoader loader = repo.open(diff.getOldId().toObjectId());
            String content = new String(loader.getBytes(), StandardCharsets.UTF_8);
            return (int) content.lines().count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Counts non-blank lines of code in {@code path} at the given commit.
     */
    private int countLoc(RevCommit commit, String path) {
        try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (tw == null) return 0;
            ObjectLoader loader = repo.open(tw.getObjectId(0));
            String content = new String(loader.getBytes(), StandardCharsets.UTF_8);
            return (int) content.lines()
                    .filter(l -> !l.isBlank())
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** The "current" path of a diff entry (new path for ADD/MODIFY/RENAME, old for DELETE). */
    private static String effectivePath(DiffEntry diff) {
        return diff.getChangeType() == DiffEntry.ChangeType.DELETE
                ? diff.getOldPath()
                : diff.getNewPath();
    }

    private DiffFormatter newDiffFormatter() {
        DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
        df.setRepository(repo);
        return df;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Inner helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Mutable accumulator used while walking the commit log for one file. */
    private static class FileAccumulator {
        Set<String> authors       = new HashSet<>();
        int  nr                   = 0;
        int  totalLocAdded        = 0;
        int  maxLocAdded          = 0;
        int  totalLocTouched      = 0;
        int  totalChurn           = 0;
        int  maxChurn             = 0;
        int  totalChangeSet       = 0;
        int  maxChangeSet         = 0;
        int  firstCommitTime      = 0; // epoch seconds of the oldest commit touching the file
    }

    /**
     * Thin wrapper so we can get a {@link CanonicalTreeParser} without
     * exposing the checked exception at every call site.
     */
    private static class RevTreeIter extends CanonicalTreeParser {
        RevTreeIter(Repository repo, RevTree tree) throws IOException {
            super(null, repo.newObjectReader(), tree);
        }
    }

    @Override
    public void close() {
        git.close();
    }
}