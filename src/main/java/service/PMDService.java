package service;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Runs PMD on a local source tree and returns the number of violations
 * (code smells) per file.
 *
 * <h3>Ruleset</h3>
 * The default ruleset covers the four PMD built-in categories most closely
 * aligned with "code smells" as understood in defect-prediction research:
 * <ul>
 *   <li>Design     – God class, excessive complexity, etc.</li>
 *   <li>CodeStyle  – naming, unused imports, etc.</li>
 *   <li>ErrorProne – null-check issues, empty catch blocks, etc.</li>
 *   <li>BestPractices – array copies, override, etc.</li>
 * </ul>
 * Pass a custom ruleset XML path to the constructor to override.
 */
public class PMDService {

    private static final Logger LOGGER = Logger.getLogger(PMDService.class.getName());

    /**
     * Built-in PMD rulesets used when no custom ruleset is provided.
     * Each string is a classpath resource understood by PMD's RuleSetLoader.
     */
    private static final String[] DEFAULT_RULESETS = {
            "category/java/design.xml",
            "category/java/codestyle.xml",
            "category/java/errorprone.xml",
            "category/java/bestpractices.xml"
    };

    private final String[] rulesets;

    /** Uses the default rulesets (Design + CodeStyle + ErrorProne + BestPractices). */
    public PMDService() {
        this.rulesets = DEFAULT_RULESETS;
    }

    /**
     * Runs PMD on all non-test Java files under {@code repoRoot} and returns
     * a map from <b>repo-relative file path</b> to <b>violation count</b>.
     *
     * Only files with at least one violation appear in the map; missing keys
     * mean 0 smells.
     *
     * @param repoRoot  the root directory of the checked-out repository
     * @return          map of repo-relative path → smell count
     */
    public Map<String, Integer> analyzeRelease(File repoRoot) {

        LOGGER.info("Running PMD on: " + repoRoot.getAbsolutePath());

        PMDConfiguration config = buildConfiguration(repoRoot);

        Map<String, Integer> smellsPerFile = new HashMap<>();

        try (PmdAnalysis analysis = PmdAnalysis.create(config)) {

            // Add rulesets
            for (String ruleset : rulesets) {
                analysis.addRuleSet(analysis.newRuleSetLoader().loadFromResource(ruleset));
            }

            // Run and collect the report
            Report report = analysis.performAnalysisAndCollectReport();

            String repoRootPath = repoRoot.getAbsolutePath();

            for (RuleViolation violation : report.getViolations()) {

                // PMD gives us the absolute path; convert to repo-relative
                String absPath = violation.getFileId().getAbsolutePath().toString();
                String relPath = toRelativePath(repoRootPath, absPath);

                // Skip test files (same heuristic as MetricsCollector)
                if (isTestFile(relPath)) continue;

                smellsPerFile.merge(relPath, 1, Integer::sum);
            }

            LOGGER.info("PMD found " + report.getViolations().size()
                    + " total violations in " + smellsPerFile.size() + " files.");

        } catch (Exception e) {
            LOGGER.severe("PMD analysis failed: " + e.getMessage());
        }

        return smellsPerFile;
    }

    private PMDConfiguration buildConfiguration(File repoRoot) {
        PMDConfiguration config = new PMDConfiguration();

        LanguageVersion javaVersion = LanguageRegistry.PMD
                .getLanguageById("java")
                .getDefaultVersion();
        config.setDefaultLanguageVersion(javaVersion);

        // Source directory: only src/main/java subtree to skip test sources
        // If the project layout differs, add more paths or use repoRoot directly.
        File mainSrc = new File(repoRoot, "src/main/java");
        if (mainSrc.exists()) {
            config.addInputPath(mainSrc.toPath());
        } else {
            // Fallback: scan the whole repo root (test filter applied later)
            config.addInputPath(repoRoot.toPath());
        }

        List<Path> pathToExclude=new ArrayList<>();
        pathToExclude.add(new File(repoRoot, "target").toPath());
        pathToExclude.add(new File(repoRoot, "build").toPath());
        config.setExcludes(pathToExclude);

        config.setIgnoreIncrementalAnalysis(true); // always do a full analysis
        config.setThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

        return config;
    }

    /**
     * Converts an absolute file path to a repo-relative path using '/' separators.
     */
    private static String toRelativePath(String repoRootPath, String absPath) {
        String rel = absPath;
        if (rel.startsWith(repoRootPath)) {
            rel = rel.substring(repoRootPath.length());
        }
        // Normalise OS separators to '/'
        rel = rel.replace(File.separatorChar, '/');
        // in PMDService.toRelativePath()
        rel = rel.replace(File.separatorChar, '/');
        if (rel.startsWith("/") || rel.startsWith("./"))
            rel = rel.replaceFirst("^[./]+", "");
        return rel;
    }

    //used to exclude test files
    private static boolean isTestFile(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.endsWith("test.java")
                || lower.endsWith("tests.java")
                || lower.endsWith("testcase.java")
                || lower.contains("/mock/")
                || lower.contains("/stub/");
    }
}