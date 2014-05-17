package hudson.plugins.fossil;

import hudson.scm.SCMRevisionState;

/**
 * This class represents the revision state of a Fossil repository's most recent commit.
 * 
 * Most often, this will come from the RSS feed provided by the Fossil Server.
 * 
 * A Fossil artifact revision only has one revision id (unlike Bazaar)
 * 
 * @author Ron Perrella
 */

public class FossilRevisionState extends SCMRevisionState {

    private final String rev_id;    // Fossil only needs a single hash to represent a Check-in.

    /**
     * @param revId a Fossil revision id (Check-in)
     */
    
    public FossilRevisionState(String revId) {
        this.rev_id = revId;
    }
    
    /**
     * 
     * @return the commit in a form that is suitable for display.
     */
    @Override
    public String getDisplayName()
    {
        return getRevId();
    }
    
    /**
     * Returns null for now, since returning a good URL for Fossil Revision State requires the URL to the fossil server.
     * 
     * @return return a good URL for Fossil Revision State
     */
    
    
    @Override
    public String getUrlName() 
    {
        return null;
    }
    
    /**
     * @return a Fossil revision id (aka checkin or commit).
     */
    public String getRevId() {
        return this.rev_id;
    }

    @Override
    public String toString() {
        return "FossilRevisionState revid:" + this.rev_id;
    }

    /*
     * As we will be using Collections of these, we have to implement
     * Equals(Object) and hashcode()
     */
    
    @Override
    public boolean equals(Object other) {
        boolean result = false;
        if (other == null) { return false; }
        if (other instanceof FossilRevisionState) {
            FossilRevisionState that = (FossilRevisionState) other;
            result = this.rev_id.equals(that.rev_id);
        }
        return result;
    }

    /**
     * Could NPE if rev_id == null.
     * 
     * @return hashCode
     */
    @Override
    public int hashCode() {
        return this.rev_id.hashCode();
    }
}
