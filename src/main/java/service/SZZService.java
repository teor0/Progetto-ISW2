package service;

import model.ClassMetrics;
import model.Release;
import model.Ticket;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Labels each {@link ClassMetrics} instance as <em>buggy</em>
 * using the SZZ algorithm.
 *
 * <h3>Algorithm overview</h3>
 * <ol>
 *   <li>For every validated {@link Ticket} with IV and FV resolved:
 *     <ul>
 *       <li>Determine the <b>buggy window</b>: all releases {@code r} such that
 *           {@code IV.releaseNumber ≤ r.releaseNumber < FV.releaseNumber}.
 *           The fix release itself is excluded because the bug was already
 *           repaired there.</li>
 *       <li>Find the <b>files touched by the fix commits</b> (i.e. commits
 *           linked to this ticket in Git).  These are the files that are
 *           assumed to have contained the defect.</li>
 *     </ul>
 *   </li>
 *   <li>For every {@code (release, filePath)} pair that falls inside the
 *       buggy window, set {@link ClassMetrics#setBuggy(boolean)} to
 *       {@code true} on the matching {@link ClassMetrics} row.</li>
 * </ol>
 *
 * <h3>Fix-commit file resolution</h3>
 * The files modified by a fix commit are obtained by diffing the commit
 * against its first parent, keeping only non-test {@code .java} files.
 * Renamed files are tracked via their <em>new</em> path (post-rename), which
 * is consistent with how {@link controller.MetricsCollector} names files.
 */
public class SZZService implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(SZZService.class.getName());

    private final Git        git;
    private final Repository repo;

    public SZZService(String repoPath) throws IOException {
        this.git  = Git.open(new File(repoPath));
        this.repo = git.getRepository();
    }

    /**
     * Labels every entry in {@code classMetricsList} as buggy or not buggy.
     *
     * <p>A {@code (release, filePath)} pair is marked <b>buggy</b> if there
     * exists at least one ticket whose buggy window contains that release
     * <em>and</em> whose fix commits touched that file.
     *
     * @param classMetricsList metrics rows produced by
     *                         {@link controller.MetricsCollector}; modified
     *                         in-place via {@link ClassMetrics#setBuggy}
     * @param ticketList       tickets whose IV/OV/FV have all been resolved
     *                         (by {@link ProportionService#proportion})
     * @param releaseList      chronologically-sorted, numbered release list
     */
    public void label(List<ClassMetrics> classMetricsList,
                      List<Ticket>       ticketList,
                      List<Release>      releaseList) {

        // Build a fast lookup: releaseName -> releaseNumber
        Map<String, Integer> releaseIndex = buildReleaseIndex(releaseList);

        // Build a lookup: (releaseName, filePath) -> ClassMetrics
        Map<String, ClassMetrics> metricsMap = buildMetricsMap(classMetricsList);

        int labelledRows = 0;

        for (Ticket ticket : ticketList) {

            // Guard: ticket must have IV and FV
            if (ticket.getInjectedVersion() == null
                    || ticket.getFixVersion()     == null) continue;

            int injectedVersion = ticket.getInjectedVersion().getReleaseNumber();
            int fixedVersion = ticket.getFixVersion().getReleaseNumber();

            if (injectedVersion <= 0 || fixedVersion <= 0 || injectedVersion >= fixedVersion) continue;

            // Files touched by fix commits
            Set<String> fixedFiles = resolveFixedFiles(ticket);
            if (fixedFiles.isEmpty()) {
                LOGGER.fine("Ticket " + ticket.getIssueId()
                        + ": no .java files found in fix commits.");
                continue;
            }

            // Mark every (release, file) in the buggy window
            for (Release release : releaseList) {
                int releaseNumber = release.getReleaseNumber();
                if (releaseNumber < injectedVersion || releaseNumber >= fixedVersion)
                    continue;  // outside [IV, FV)

                for (String filePath : fixedFiles) {
                    String key = metricsKey(release.getName(), filePath);
                    ClassMetrics cm = metricsMap.get(key);
                    if (cm != null && !cm.isBuggy()) {
                        cm.setBuggy(true);
                        labelledRows++;
                    }
                }
            }
        }

        long totalBuggy = classMetricsList.stream().filter(ClassMetrics::isBuggy).count();
        LOGGER.info(String.format(
                "SZZ labelling complete. Buggy (release, file) pairs: %d / %d  (%.2f%%)",
                totalBuggy, classMetricsList.size(),
                classMetricsList.isEmpty() ? 0.0
                        : 100.0 * totalBuggy / classMetricsList.size()));
    }

    /**
     * Collects all non-test Java files modified by the fix commits of
     * {@code ticket}.  The new (post-rename) path is used so that it
     * matches the names stored in {@link ClassMetrics#getClassName()}.
     */
    private Set<String> resolveFixedFiles(Ticket ticket) {
        Set<String> files = new HashSet<>();

        for (RevCommit fixCommit : ticket.getCommits()) {

            // Reparse to ensure the commit is fully loaded
            RevCommit fullCommit;
            try (RevWalk rw = new RevWalk(repo)) {
                fullCommit = rw.parseCommit(fixCommit.getId());
            } catch (IOException e) {
                LOGGER.fine("Cannot parse fix commit "
                        + fixCommit.getName() + ": " + e.getMessage());
                continue;
            }

            try {
                List<DiffEntry> diffs = diffWithParent(fullCommit);
                if (diffs == null) continue;

                for (DiffEntry diff : diffs) {
                    // Use new path (post-rename) to match MetricsCollector naming
                    String path = diff.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? diff.getOldPath()
                            : diff.getNewPath();

                    if (path.endsWith(".java") && !isTestFile(path)) {
                        files.add(path);
                    }
                }
            } catch (IOException e) {
                LOGGER.fine("Diff failed for fix commit "
                        + fullCommit.getName() + ": " + e.getMessage());
            }
        }

        return files;
    }

    /**
     * Returns the list of {@link DiffEntry} objects representing the diff
     * between {@code commit} and its first parent (or an empty tree for root
     * commits).  Returns {@code null} on I/O error.
     */
    private List<DiffEntry> diffWithParent(RevCommit commit) throws IOException {
        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repo);
            df.setDetectRenames(true);

            AbstractTreeIterator oldTree = parentTree(commit);
            AbstractTreeIterator newTree = newTree(commit);

            return df.scan(oldTree, newTree);
        }
    }

    private AbstractTreeIterator parentTree(RevCommit commit) throws IOException {
        if (commit.getParentCount() == 0) return new EmptyTreeIterator();
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit parent = rw.parseCommit(commit.getParent(0).getId());
            CanonicalTreeParser p = new CanonicalTreeParser();
            p.reset(repo.newObjectReader(), parent.getTree());
            return p;
        }
    }

    private AbstractTreeIterator newTree(RevCommit commit) throws IOException {
        CanonicalTreeParser p = new CanonicalTreeParser();
        p.reset(repo.newObjectReader(), commit.getTree());
        return p;
    }

    /** Builds a map from release name to its 1-based sequential number. */
    private static Map<String, Integer> buildReleaseIndex(List<Release> releases) {
        Map<String, Integer> map = new HashMap<>();
        for (Release r : releases)
            map.put(r.getName(), r.getReleaseNumber());
        return map;
    }

    /**
     * Builds a lookup from the composite key {@code releaseName|filePath}
     * to the corresponding {@link ClassMetrics} instance.
     */
    private static Map<String, ClassMetrics> buildMetricsMap(List<ClassMetrics> rows) {
        Map<String, ClassMetrics> map = new HashMap<>();
        for (ClassMetrics cm : rows) {
            map.put(metricsKey(cm.getReleaseName(), cm.getClassName()), cm);
        }
        return map;
    }

    private static String metricsKey(String releaseName, String filePath) {
        return releaseName + "|" + filePath;
    }

    private static boolean isTestFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.endsWith("test.java")
                || lower.endsWith("tests.java")
                || lower.endsWith("testcase.java");
    }

    @Override
    public void close() { git.close(); }
}