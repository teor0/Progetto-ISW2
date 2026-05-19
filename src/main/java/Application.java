import controller.MetricsCollector;
import model.ClassMetrics;
import model.CommitMetrics;
import model.Release;
import org.eclipse.jgit.api.errors.GitAPIException;
import service.*;
import utility.Constants;
import utility.CsvWriter;

import java.util.logging.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static utility.Constants.*;

public class Application {

    /**
     * Orchestrates the full pipeline:
     *
     * <ol>
     *   <li>Load releases from the trimmed JSON file.</li>
     *   <li>For each release, resolve the boundary commit (last commit before the release date).</li>
     *   <li>Collect class-level and commit-level metrics via {@link MetricsCollector}.</li>
     *   <li>Enrich each class with its SonarCloud smell count.</li>
     *   <li>Write the merged dataset to {@link Constants#CSV_FILE}.</li>
     * </ol>
     *
     * <h3>SonarCloud configuration</h3>
     * Set the following constants (or externalise them to a config file / env vars):
     * <ul>
     *   <li>{@code SONAR_ORG}     – your SonarCloud organisation slug, e.g. {@code "apache"}.</li>
     *   <li>{@code SONAR_PROJECT} – the SonarCloud project key, e.g. {@code "apache_zookeeper"}.</li>
     *   <li>{@code SONAR_TOKEN}   – a SonarCloud user token.  Can also be supplied via the
     *                               {@code SONAR_TOKEN} environment variable.</li>
     * </ul>
     * If you do not have a SonarCloud analysis set up, set {@code ENABLE_SONAR = false}
     * and the smell column will be written as {@code 0} for every row.
     */
    private static final Logger LOGGER = Logger.getLogger(Application.class.getName());

    public static void main(String[] args) throws IOException, GitAPIException {
        GitService gitService = new GitService(PROJECT_REPOSITORY_PATH);
        ReleaseService releaseService = new ReleaseService();
        List<Release> releases = gitService.findLastCommit(releaseService.loadFromFile(VERSIONS_FILE_TRIMMED));
        boolean ENABLE_SONAR   = true;
        // Token is read from SONAR_TOKEN env-var; set it here only as a fallback.
        final String  SONAR_TOKEN    = null;
        LOGGER.info("Loaded " + releases.size() + " releases.");
        // ── 3. Collect metrics ───────────────────────────────────────────
        List<ClassMetrics>  allClassMetrics  = new ArrayList<>();
        List<CommitMetrics> allCommitMetrics = new ArrayList<>();
        try (MetricsCollector collector = new MetricsCollector(Constants.REPO_PATH)) {
            for (Release release : releases) {
                LOGGER.info("Processing release: " + release.getName());
                collector.collect(release, allClassMetrics, allCommitMetrics);
            }
        }
        LOGGER.info("Total class entries  : " + allClassMetrics.size());
        LOGGER.info("Total commit entries : " + allCommitMetrics.size());
        // ── 4. Enrich with SonarCloud smells ─────────────────────────────
        if (ENABLE_SONAR) {
            SonarCloudService sonar = new SonarCloudService(SONAR_ORG, SONAR_PROJECT, SONAR_TOKEN);
            int done = 0;
            for (ClassMetrics cm : allClassMetrics) {
                        /*
                         * Strategy: query by release name as branch.
                         * SonarCloud branch names must exactly match what was analysed.
                         * Adapt the mapping below if your branch naming differs.
                         */
                int smells = sonar.getSmellCount(cm.getClassName(), cm.getReleaseName());
                cm.setSmells(smells);

                if (++done % 100 == 0)
                            LOGGER.info("Sonar progress: " + done + " / " + allClassMetrics.size());
                }
        }
        // ── 5. Write CSV ─────────────────────────────────────────────────
        new CsvWriter().write(Constants.CSV_FILE, allClassMetrics, allCommitMetrics);
        LOGGER.info("Dataset written to: " + Constants.CSV_FILE);
}
