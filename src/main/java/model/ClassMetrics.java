package model;

/**
 * Holds all class-level metrics for a single Java source file
 * up to a given release boundary commit, plus the SonarCloud smell count.
 */
public class ClassMetrics {

    private final String releaseName;
    private final String className;        // repo-relative path, e.g. src/main/…/Foo.java

    private int loc;                       // lines of code at boundary commit
    private int nr;                        // number of revisions (commits that touched the file)
    private int nAuth;                     // number of distinct authors
    private int locAdded;                  // sum of added lines over all revisions
    private int maxLocAdded;               // max added lines in a single revision
    // avgLocAdded = locAdded / nr  (computed on demand)
    private int locTouched;               // sum of (added + deleted) over all revisions
    private int churn;                    // sum of |added - deleted| over all revisions
    private int maxChurn;                 // max churn in a single revision
    // avgChurn = churn / nr  (computed on demand)
    private int changeSetSize;            // sum of change-set sizes over all revisions
    private int maxChangeSet;             // max change-set size in a single revision
    // avgChangeSet = changeSetSize / nr  (computed on demand)
    private long ageInDays;               // days from file creation to release date
    private double weightedAge;           // ageInDays weighted by locTouched

    /** Number of code smells reported by PMD for this file at this release. */
    private int smells;

    /**
     * SZZ-derived label: {@code true} if at least one bug ticket has this
     * (release, file) pair inside its injected-to-fix window [IV, FV).
     */
    private boolean buggy = false;



    public ClassMetrics(String releaseName, String className) {
        this.releaseName = releaseName;
        this.className   = className;
    }

    // ── getters / setters ────────────────────────────────────────────────────

    public String getReleaseName()  { return releaseName; }
    public String getClassName()    { return className;   }

    public int  getLoc()            { return loc;         }
    public void setLoc(int loc)     { this.loc = loc;     }

    public int  getNr()             { return nr;          }
    public void setNr(int nr)       { this.nr = nr;       }

    public int  getNAuth()          { return nAuth;       }
    public void setNAuth(int n)     { this.nAuth = n;     }

    public int  getLocAdded()       { return locAdded;    }
    public void setLocAdded(int v)  { this.locAdded = v;  }

    public int  getMaxLocAdded()         { return maxLocAdded;   }
    public void setMaxLocAdded(int v)    { this.maxLocAdded = v; }

    public double getAvgLocAdded() {
        return nr == 0 ? 0.0 : (double) locAdded / nr;
    }

    public int  getLocTouched()          { return locTouched;    }
    public void setLocTouched(int v)     { this.locTouched = v;  }

    public int  getChurn()               { return churn;         }
    public void setChurn(int v)          { this.churn = v;       }

    public int  getMaxChurn()            { return maxChurn;      }
    public void setMaxChurn(int v)       { this.maxChurn = v;    }

    public double getAvgChurn() {
        return nr == 0 ? 0.0 : (double) churn / nr;
    }

    public int  getChangeSetSize()       { return changeSetSize;    }
    public void setChangeSetSize(int v)  { this.changeSetSize = v;  }

    public int  getMaxChangeSet()        { return maxChangeSet;  }
    public void setMaxChangeSet(int v)   { this.maxChangeSet = v;}

    public double getAvgChangeSet() {
        return nr == 0 ? 0.0 : (double) changeSetSize / nr;
    }

    public long   getAgeInDays()         { return ageInDays;     }
    public void   setAgeInDays(long v)   { this.ageInDays = v;   }

    public double getWeightedAge()       { return weightedAge;   }
    public void   setWeightedAge(double v){ this.weightedAge = v; }

    public int  getSmells()              { return smells;        }
    public void setSmells(int v)         { this.smells = v;      }

    public boolean isBuggy()             { return buggy;         }
    public void    setBuggy(boolean b)   { this.buggy = b;       }

}