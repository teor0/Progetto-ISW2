package utility;

import com.opencsv.CSVWriter;
import model.ClassMetrics;
import model.CommitMetrics;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes class-level and commit-level metrics to a CSV file.
 *
 * <h3>Commit-level aggregation</h3>
 * Each {@link CommitMetrics} record belongs to a (release, filePath) pair.
 * AVG_NF, AVG_LA, AVG_LD, and AVG_LT are therefore averaged only over the
 * commits that touched <em>that specific file</em> within its release window —
 * so each class row gets its own correct per-file averages, not a
 * release-wide average shared by every class.
 *
 * <h3>CSV columns</h3>
 * Release, FilePath, LOC,
 * LOC_Added, MAX_LOC_Added, AVG_LOC_Added,
 * LOC_Touched, NR, NAuth,
 * Churn, MAX_Churn, AVG_Churn,
 * ChangeSetSize, MAX_ChangeSet, AVG_ChangeSet,
 * Age_In_Days, Weighted_Age,
 * AVG_NF, AVG_LA, AVG_LD, AVG_LT,
 * Smells, Buggy
 */
public class CsvWriter {

    private static final String[] HEADER = {
            "Release", "FilePath",
            "LOC",
            "LOC_Added", "MAX_LOC_Added", "AVG_LOC_Added",
            "LOC_Touched",
            "NR", "NAuth",
            "Churn", "MAX_Churn", "AVG_Churn",
            "ChangeSetSize", "MAX_ChangeSet", "AVG_ChangeSet",
            "Age_In_Days", "Weighted_Age",
            "AVG_NF", "AVG_LA", "AVG_LD", "AVG_LT",
            "Smells",
            "Buggy"
    };

    public void write(String path,
                      Collection<ClassMetrics>  classMetrics,
                      Collection<CommitMetrics> commitMetrics) throws IOException {

        /*
         * Group commit records by (releaseName, filePath).
         * Key: releaseName + "|" + filePath  — the pipe is safe because
         * neither field contains a pipe character.
         */
        Map<String, List<CommitMetrics>> commitsByClassKey = commitMetrics.stream()
                .collect(Collectors.groupingBy(
                        cm -> cm.getReleaseName() + "|" + cm.getFilePath()
                ));

        try (CSVWriter writer = new CSVWriter(new FileWriter(path))) {
            writer.writeNext(HEADER);

            for (ClassMetrics cm : classMetrics) {

                String key = cm.getReleaseName() + "|" + cm.getClassName();
                List<CommitMetrics> fileCommits = commitsByClassKey.getOrDefault(key, List.of());

                // Per-file averages — each value is the average over the commits
                // that specifically touched this file in this release window.
                double avgNf = fileCommits.stream().mapToInt(CommitMetrics::getNf).average().orElse(0.0);
                double avgLa = fileCommits.stream().mapToInt(CommitMetrics::getLa).average().orElse(0.0);
                double avgLd = fileCommits.stream().mapToInt(CommitMetrics::getLd).average().orElse(0.0);
                double avgLt = fileCommits.stream().mapToInt(CommitMetrics::getLt).average().orElse(0.0);

                writer.writeNext(new String[]{
                        cm.getReleaseName(),
                        cm.getClassName(),
                        String.valueOf(cm.getLoc()),
                        String.valueOf(cm.getLocAdded()),
                        String.valueOf(cm.getMaxLocAdded()),
                        String.format("%.4f", cm.getAvgLocAdded()),
                        String.valueOf(cm.getLocTouched()),
                        String.valueOf(cm.getNr()),
                        String.valueOf(cm.getNAuth()),
                        String.valueOf(cm.getChurn()),
                        String.valueOf(cm.getMaxChurn()),
                        String.format("%.4f", cm.getAvgChurn()),
                        String.valueOf(cm.getChangeSetSize()),
                        String.valueOf(cm.getMaxChangeSet()),
                        String.format("%.4f", cm.getAvgChangeSet()),
                        String.valueOf(cm.getAgeInDays()),
                        String.format("%.4f", cm.getWeightedAge()),
                        String.format("%.4f", avgNf),
                        String.format("%.4f", avgLa),
                        String.format("%.4f", avgLd),
                        String.format("%.4f", avgLt),
                        String.valueOf(cm.getSmells()),
                        "no"
                });
            }
        }
    }
}