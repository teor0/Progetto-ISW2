package model;

import org.eclipse.jgit.revwalk.RevCommit;

import java.time.LocalDate;

public class Release {

    private String name;
    private LocalDate releaseDate;
    private RevCommit boundaryCommit;

    public Release(String name, LocalDate releaseDate, RevCommit boundaryCommit) {
        this.name = name;
        this.releaseDate = releaseDate;
        this.boundaryCommit = boundaryCommit;
    }

    public String getName() { return name; }
    public LocalDate getReleaseDate() { return releaseDate; }
    public RevCommit  getBoundaryCommit() { return boundaryCommit; }
    public void setBoundaryCommit(RevCommit commit){
        this.boundaryCommit=commit;
    }

    @Override
    public String toString() {
        return "Release{name='" + name + "', date=" + releaseDate + "}";
    }
}