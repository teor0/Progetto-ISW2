package utility;

import com.opencsv.CSVWriter;
import model.ClassMetrics;
import model.CommitMetrics;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * Writes the full dataset to {@code path}.
     *
     * @param path          output CSV file path
     * @param classMetrics  all class-level metrics (across all releases)
     * @param commitMetrics all commit-level metrics (across all releases)
     */
    public void write(String path,
                      Collection<ClassMetrics>  classMetrics,
                      Collection<CommitMetrics> commitMetrics) throws IOException {

        // Group commit metrics by release so we can look them up per class
        Map<String, List<CommitMetrics>> commitsByRelease = commitMetrics.stream()
                .collect(Collectors.groupingBy(CommitMetrics::getReleaseName));

        try (CSVWriter writer = new CSVWriter(new FileWriter(path))) {
            writer.writeNext(HEADER);

            for (ClassMetrics cm : classMetrics) {

                List<CommitMetrics> commits = commitsByRelease
                        .getOrDefault(cm.getReleaseName(), List.of());

                // Aggregate commit-level metrics over all commits in this release
                double avgNf = commits.stream().mapToInt(CommitMetrics::getNf).average().orElse(0.0);
                double avgLa = commits.stream().mapToInt(CommitMetrics::getLa).average().orElse(0.0);
                double avgLd = commits.stream().mapToInt(CommitMetrics::getLd).average().orElse(0.0);
                double avgLt = commits.stream().mapToInt(CommitMetrics::getLt).average().orElse(0.0);

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
                        "no"                           // Buggy – placeholder; set by labelling step
                });
            }
        }
    }
}