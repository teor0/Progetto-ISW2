package model;

/**
 * Holds commit-level metrics for a single Git commit touching one specific file.
 *
 * One {@code CommitMetrics} instance is created per (release, commit, file) triple so
 * that per-file LT (lines before change) can be recorded correctly.
 */
public class CommitMetrics {

    private final String commitHash;
    private final String releaseName;

    /** Number of files modified in this commit (change-set size). */
    private int nf;

    /** Lines added to THIS file in this commit. */
    private int la;

    /** Lines deleted from THIS file in this commit. */
    private int ld;

    /**
     * Lines of code in THIS file BEFORE the change was applied.
     * Larger files are more likely to receive defect-introducing changes.
     */
    private int lt;

    public CommitMetrics(String releaseName, String commitHash) {
        this.releaseName = releaseName;
        this.commitHash  = commitHash;
    }

    // ── getters / setters ────────────────────────────────────────────────────

    public String getReleaseName() { return releaseName; }
    public String getCommitHash()  { return commitHash;  }

    public int  getNf()          { return nf; }
    public void setNf(int nf)    { this.nf = nf; }

    public int  getLa()          { return la; }
    public void setLa(int la)    { this.la = la; }

    public int  getLd()          { return ld; }
    public void setLd(int ld)    { this.ld = ld; }

    public int  getLt()          { return lt; }
    public void setLt(int lt)    { this.lt = lt; }
}