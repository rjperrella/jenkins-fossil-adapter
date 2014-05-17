package hudson.plugins.fossil;

import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * List of "change logs" that went into a build.
 * 
 * 
 * 
 * @author Trond Norbye (original)
 * @author Ron Perrella
 */

public class FossilChangeLogSet extends ChangeLogSet<FossilChangeLogEntry> {
     private final List<FossilChangeLogEntry> changeSets;
    
     /**
      * FossilChangeLogSet ctor
      * @param build
      * @param logs 
      */
    FossilChangeLogSet(AbstractBuild build, List<FossilChangeLogEntry> logs) {
        super(build);
        this.changeSets = Collections.unmodifiableList(logs);
        for (FossilChangeLogEntry log : logs) {
            log.setParent(this);
        }
    }
    /**
     * @return true if the set is empty (no change logs in the collection)
     */
    public boolean isEmptySet() {
        return changeSets.isEmpty();
    }

    /**
     * @return an iterator for the changelog
     */
    public Iterator<FossilChangeLogEntry> iterator() {
        return changeSets.iterator();
    }

    /**
     * @return the list of fossil change logs
     */
    public List<FossilChangeLogEntry> getLogs() {
        return changeSets;
    }

    /**
     * @return the kind of changelog list this is (fossil)
     */
    @Override
    public String getKind() {
        return "fossil";
    }
}
