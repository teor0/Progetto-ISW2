import controller.MetricsCollector;
import model.ClassMetrics;
import model.CommitMetrics;
import model.Release;
import org.eclipse.jgit.revwalk.RevCommit;
import service.GitService;
import service.JiraService;
import service.PMDService;
import utility.CsvWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static utility.Constants.*;

/**
 * Orchestrates the full pipeline:
 *
 * <ol>
 *   <li>Load releases from the JSON file.</li>
 *   <li>For each release, resolve the boundary commit (last commit before the release date).</li>
 *   <li>Collect class-level and commit-level metrics via {@link MetricsCollector}.</li>
 *   <li>Enrich each class with its PMD smell count.</li>
 *   <li>Write the merged dataset to CSV_FILE.</li>
 * </ol>
 */
public class Application {


    private static final Logger LOGGER = Logger.getLogger(Application.class.getName());

    public static void main(String[] args) {
        try(GitService gitService = new GitService(PROJECT_REPOSITORY_PATH);
            MetricsCollector collector = new MetricsCollector(REPO_PATH);) {
            List<String> l=JiraService.retrieveFixedBugs();
            List<Release> releases = gitService.constructReleases(JiraService.retrieveRelease());
            LOGGER.info("Found " + releases.size() + " releases.");
            List<ClassMetrics> allClassMetrics = new ArrayList<>();
            List<CommitMetrics> allCommitMetrics = new ArrayList<>();
            PMDService pmd = new PMDService();


            RevCommit prevBoundary = null;
            for (Release release : releases) {
                //we skip the null check on boundaryCommit
                LOGGER.info("=== Processing release: " + release.getName() + " ===");

                // 1. Checkout boundary commit → working tree = release snapshot
                gitService.checkoutCommit(release.getBoundaryCommit());

                // 2. Run PMD on the checked-out source tree
                File repoRoot = gitService.getWorkTree();
                Map<String, Integer> smellsPerFile = pmd.analyzeRelease(repoRoot);

                // 3 Collect Git metrics windowed to (prevBoundary, boundary]
                List<ClassMetrics>  releaseClassMetrics  = new ArrayList<>();
                List<CommitMetrics> releaseCommitMetrics = new ArrayList<>();
                collector.collect(release, prevBoundary, releaseClassMetrics, releaseCommitMetrics);

                // 4 Merge PMD smell counts into each ClassMetrics entry
                for (ClassMetrics cm : releaseClassMetrics) {
                    cm.setSmells(smellsPerFile.getOrDefault(cm.getClassName(), 0));
                }
                allClassMetrics.addAll(releaseClassMetrics);
                allCommitMetrics.addAll(releaseCommitMetrics);

                prevBoundary = release.getBoundaryCommit();
            }

            gitService.checkoutBranch(DEFAULT_BRANCH);
            LOGGER.info("Total class entries  : " + allClassMetrics.size());
            LOGGER.info("Total commit entries : " + allCommitMetrics.size());

            new CsvWriter().write(CSV_FILE, allClassMetrics, allCommitMetrics);
            LOGGER.info("Dataset written to: " + CSV_FILE);
        }
        catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Pipeline failed", e);
        }
    }
}
