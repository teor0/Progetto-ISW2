package model;

import org.eclipse.jgit.revwalk.RevCommit;

import java.time.LocalDate;

public class Release {

    private final String name;
    private final LocalDate releaseDate;
    private RevCommit boundaryCommit;
    private int releaseNumber;

    public Release(String name, LocalDate releaseDate, RevCommit boundaryCommit, int releaseNumber) {
        this.name = name;
        this.releaseDate = releaseDate;
        this.boundaryCommit = boundaryCommit;
        this.releaseNumber = releaseNumber;
    }

    public String getName() { return name; }
    public LocalDate getReleaseDate() { return releaseDate; }
    public RevCommit  getBoundaryCommit() { return boundaryCommit; }
    public int getReleaseNumber() { return releaseNumber; }
    public void setReleaseNumber(int releaseNumber) { this.releaseNumber = releaseNumber; }
    public void setBoundaryCommit(RevCommit commit){
        this.boundaryCommit=commit;
    }

    @Override
    public String toString() {
        return "Release{name='" + name + "', date=" + releaseDate + "}";
    }
}