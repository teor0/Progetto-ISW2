package controller;

import model.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes class-level and commit-level metrics for every release.
 */
public class MetricsController{

    private final Git git;
    private final Repository repo;

    public MetricsController(Git git) {
        this.git  = git;
        this.repo = git.getRepository();
    }

    /**
     * For every release, walks all commits up to the boundary commit and
     * computes the full set of class-level metrics for every Java file
     * present in the repository at that boundary.
     */
    public List<ClassMetrics> computeClassMetrics(List<Release> releases) throws IOException {

        List<ClassMetrics> result = new ArrayList<>();

        for (Release release : releases) {

            RevCommit boundary = release.getBoundaryCommit();
            if (boundary == null)
                continue;

            LocalDate releaseDate = release.getReleaseDate();

            //  collect all commits reachable till boundary
            List<RevCommit> commits = commitsUpTo(boundary);

            // collect all .java files present at boundary
            Set<String> javaFiles = listJavaFiles(boundary);

            // per-file accumulators
            //   key = repo-relative file path
            Map<String, ClassMetrics>       metricsMap     = new HashMap<>();
            Map<String, Set<String>>        authorsMap     = new HashMap<>();
            Map<String, LocalDate>          firstSeenMap   = new HashMap<>();

            for (String path : javaFiles) {
                metricsMap.put(path, new ClassMetrics(release.getName(), path));
                authorsMap.put(path, new HashSet<>());
            }

            // walk commits from oldest to newest
            //   (commitsUpTo returns newest-first; reverse for chronological)
            List<RevCommit> chronological = new ArrayList<>(commits);
            java.util.Collections.reverse(chronological);

            try (DiffFormatter df = buildDiffFormatter()) {

                for (RevCommit commit : chronological) {

                    LocalDate commitDate = toLocalDate(commit);
                    int changeSetSize = countJavaFilesInCommit(commit, df);

                    List<DiffEntry> diffs = getDiffs(commit, df);

                    for (DiffEntry diff : diffs) {

                        String path = effectivePath(diff);
                        if (path == null || !path.endsWith(".java"))
                            continue;
                        if (!metricsMap.containsKey(path))
                            continue;

                        // line-level delta for this file in this commit
                        int added   = 0;
                        int deleted = 0;
                        for (Edit edit : df.toFileHeader(diff).toEditList()) {
                            added   += edit.getEndB() - edit.getBeginB();
                            deleted += edit.getEndA() - edit.getBeginA();
                        }

                        int churnThisRevision = Math.abs(added - deleted);

                        ClassMetrics m = metricsMap.get(path);

                        // NR
                        m.setNr(m.getNr() + 1);

                        // authors
                        PersonIdent author = commit.getAuthorIdent();
                        if (author != null) {
                            authorsMap.get(path).add(author.getEmailAddress());
                        }

                        // LOC Added
                        m.setLocAdded(m.getLocAdded() + added);
                        m.setMaxLocAdded(Math.max(m.getMaxLocAdded(), added));

                        // LOC Touched
                        m.setLocTouched(m.getLocTouched() + added + deleted);

                        // Churn
                        m.setChurn(m.getChurn() + churnThisRevision);
                        m.setMaxChurn(Math.max(m.getMaxChurn(), churnThisRevision));

                        // Change Set Size
                        m.setChangeSetSize(m.getChangeSetSize() + changeSetSize);
                        m.setMaxChangeSet(Math.max(m.getMaxChangeSet(), changeSetSize));

                        // Track creation date for Age
                        firstSeenMap.merge(path, commitDate,
                                (existing, candidate) -> candidate.isBefore(existing) ? candidate : existing);
                    }
                }
            }

            // finalise per-file metrics
            for (String path : javaFiles) {

                ClassMetrics m = metricsMap.get(path);

                // LOC at boundary
                m.setLoc(countLoc(boundary, path));

                // NAuth
                m.setNAuth(authorsMap.get(path).size());

                // Age (days from first commit touching the file to release date)
                LocalDate firstSeen = firstSeenMap.getOrDefault(path, releaseDate);
                long ageInDays = ChronoUnit.DAYS.between(firstSeen, releaseDate);
                m.setAgeInDays(Math.max(0, ageInDays));

                // Weighted Age
                long locTouched = m.getLocTouched();
                double weightedAge = locTouched == 0
                        ? m.getAgeInDays()
                        : (double) m.getAgeInDays() / locTouched;
                m.setWeightedAge(weightedAge);

                result.add(m);
            }
        }

        return result;
    }

    /**
     * For every release, computes commit-level metrics (NF, LA, LD) for each
     * commit in the boundary window.
     */
    public List<CommitMetrics> computeCommitMetrics(List<Release> releases) throws IOException {

        List<CommitMetrics> result = new ArrayList<>();

        for (Release release : releases) {

            RevCommit boundary = release.getBoundaryCommit();
            if (boundary == null) continue;

            List<RevCommit> commits = commitsUpTo(boundary);

            try (DiffFormatter df = buildDiffFormatter()) {

                for (RevCommit commit : commits) {

                    CommitMetrics cm = new CommitMetrics(release.getName(), commit.getName());

                    List<DiffEntry> diffs = getDiffs(commit, df);

                    int nf = 0, la = 0, ld = 0;

                    for (DiffEntry diff : diffs) {
                        nf++;
                        for (Edit edit : df.toFileHeader(diff).toEditList()) {
                            la += edit.getEndB() - edit.getBeginB();
                            ld += edit.getEndA() - edit.getBeginA();
                        }
                    }

                    cm.setNf(nf);
                    cm.setLa(la);
                    cm.setLd(ld);

                    result.add(cm);
                }
            }
        }

        return result;
    }

    /**
     * Returns all commits reachable from {@code head}, newest-first.
     */
    private List<RevCommit> commitsUpTo(RevCommit head) throws IOException {
        List<RevCommit> list = new ArrayList<>();
        try (RevWalk rw = new RevWalk(repo)) {
            rw.markStart(rw.parseCommit(head.getId()));
            for (RevCommit c : rw) {
                list.add(c);
            }
        }
        return list;
    }

    /**
     * Lists all .java file paths present in the tree of {@code commit}.
     */
    private Set<String> listJavaFiles(RevCommit commit) throws IOException {
        Set<String> paths = new HashSet<>();
        RevTree tree = commit.getTree();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(true);
            while (tw.next()) {
                String path = tw.getPathString();
                if (path.endsWith(".java") && !path.contains("/test/")) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    /**
     * Counts the lines of code in {@code path} at the given {@code commit}.
     * Empty lines and lines containing only whitespace are excluded.
     */
    private int countLoc(RevCommit commit, String path) {
        try {
            ObjectId treeId = commit.getTree().getId();
            try (TreeWalk tw = TreeWalk.forPath(repo, path, treeId)) {
                if (tw == null) return 0;
                ObjectId blobId = tw.getObjectId(0);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                repo.open(blobId).openStream(),
                                StandardCharsets.UTF_8))) {
                    int count = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) count++;
                    }
                    return count;
                }
            }
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Returns all {@link DiffEntry} objects for a commit.
     * For the root commit (no parent), diffs against an empty tree.
     */
    private List<DiffEntry> getDiffs(RevCommit commit, DiffFormatter df) throws IOException {
        if (commit.getParentCount() == 0) {
            // root commit: compare against empty tree
            try (RevWalk rw = new RevWalk(repo)) {
                return df.scan(new RevWalk(repo).parseCommit(
                                repo.resolve("4b825dc642cb6eb9a060e54bf8d69288fbee4904")), // empty tree
                        commit.getTree());
            } catch (Exception e) {
                return List.of();
            }
        }
        RevCommit parent = commit.getParent(0);
        try (RevWalk rw = new RevWalk(repo)) {
            rw.parseHeaders(parent);
        }
        return df.scan(parent.getTree(), commit.getTree());
    }

    /**
     * Counts the number of Java files touched in a single commit
     * (used as the change-set size metric).
     */
    private int countJavaFilesInCommit(RevCommit commit, DiffFormatter df) throws IOException {
        int count = 0;
        for (DiffEntry diff : getDiffs(commit, df)) {
            String path = effectivePath(diff);
            if (path != null && path.endsWith(".java")) count++;
        }
        return count;
    }

    /**
     * Returns the effective path of a {@link DiffEntry}: the new path for
     * additions/modifications, the old path for deletions.
     */
    private static String effectivePath(DiffEntry diff) {
        if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
            return diff.getOldPath();
        }
        return diff.getNewPath();
    }

    private static LocalDate toLocalDate(RevCommit commit) {
        return Instant.ofEpochSecond(commit.getCommitTime())
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
    }

    /** Builds a {@link DiffFormatter} configured for line-level diff. */
    private DiffFormatter buildDiffFormatter() {
        DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
        df.setRepository(repo);
        df.setDiffComparator(RawTextComparator.DEFAULT);
        df.setDetectRenames(true);
        return df;
    }
}