package model;

import org.eclipse.jgit.revwalk.RevCommit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a Jira bug ticket and its relationship to Git history.
 *
 * <ul>
 *   <li><b>injectedVersion</b>  – the release in which the bug was introduced
 *       (Jira "Affects Version" field, also called IV in defect-prediction literature).</li>
 *   <li><b>openingVersion</b>   – the release in which the ticket was created / opened.</li>
 *   <li><b>fixVersion</b>       – the release in which the bug was resolved / fixed.</li>
 *   <li><b>commits</b>          – all Git commits whose message references this ticket key.</li>
 *   <li><b>lastCommit</b>       – the most recent commit among those in {@code commits}
 *       (by commit time); {@code null} if no commits are associated yet.</li>
 * </ul>
 */
public class Ticket {

    /** Jira issue key, e.g. {@code "ZOOKEEPER-1234"}. */
    private final String issueId;

    /** Date the Jira ticket was created. */
    private LocalDate creationDate;

    /** Date the Jira ticket was resolved/closed; may be {@code null} if still open. */
    private LocalDate resolutionDate;

    /**
     * The release in which the defect was <em>introduced</em>
     * (Jira "Affects Version" / IV).
     */
    private Release injectedVersion;

    /** The release that was current when the ticket was <em>opened</em>. */
    private Release openingVersion;

    /** The release in which the ticket was <em>fixed</em>. */
    private Release fixVersion;

    /**
     * Git commits whose message references this ticket key, in the order they
     * were added.  Use {@link #addCommit(RevCommit)} to append.
     */
    private final List<RevCommit> commits = new ArrayList<>();

    /**
     * The most recent commit in {@link #commits} by commit timestamp, or
     * {@code null} when the list is empty.  Kept in sync by {@link #addCommit}.
     */
    private RevCommit lastCommit;

    public Ticket(String issueId) {
        this.issueId = issueId;
    }

    /**
     * Appends a commit to the associated-commit list and updates
     * {@link #lastCommit} if this commit is more recent than the current one.
     *
     * @param commit the commit to associate with this ticket
     */
    public void addCommit(RevCommit commit) {
        commits.add(commit);
        if (lastCommit == null || commit.getCommitTime() > lastCommit.getCommitTime()) {
            lastCommit = commit;
        }
    }

    /**
     * Returns an unmodifiable view of the associated commits.
     */
    public List<RevCommit> getCommits() {
        return Collections.unmodifiableList(commits);
    }

    public String getIssueId() { return issueId; }

    public LocalDate getCreationDate()               { return creationDate;    }
    public void      setCreationDate(LocalDate d)    { this.creationDate = d;  }

    public LocalDate getResolutionDate()             { return resolutionDate;  }
    public void      setResolutionDate(LocalDate d)  { this.resolutionDate = d;}

    public Release getInjectedVersion()              { return injectedVersion; }
    public void    setInjectedVersion(Release r)     { this.injectedVersion = r;}

    public Release getOpeningVersion()               { return openingVersion;  }
    public void    setOpeningVersion(Release r)      { this.openingVersion = r;}

    public Release getFixVersion()                   { return fixVersion;      }
    public void    setFixVersion(Release r)          { this.fixVersion = r;    }

    /**
     * The most recent commit associated with this ticket, or {@code null} if
     * no commits have been added yet.
     */
    public RevCommit getLastCommit()                 { return lastCommit;      }

    // ── Object overrides ─────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "Ticket{" +
                "issueId='"        + issueId                                          + '\'' +
                ", creationDate="  + creationDate                                            +
                ", resolutionDate="+ resolutionDate                                          +
                ", injectedVersion="+ (injectedVersion != null ? injectedVersion.getName() : "null") +
                ", openingVersion=" + (openingVersion  != null ? openingVersion.getName()  : "null") +
                ", fixVersion="     + (fixVersion      != null ? fixVersion.getName()      : "null") +
                ", commits="       + commits.size()                                          +
                ", lastCommit="    + (lastCommit != null ? lastCommit.getName() : "null")   +
                '}';
    }
}