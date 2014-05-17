
package hudson.plugins.fossil;

/**
 * Represents the version of the Fossil command line client being used.
 * Contains the output from the "fossil version" command.
 * 
 * @author perrella
 */
public class FossilClientInfo {
    private String version;
    private String checkin;
    private String date;
    
    /**
     * Create a Fossil Client Info
     * 
     * @param version the version of the Fossil client program
     * @param checkin the Fossil checkin hash for the client program
     * @param date the date of the Fossil client program
     */
    public FossilClientInfo(String version, String checkin, String date)
    {
        this.version = version;
        this.checkin = checkin;
        this.date = date;
    }

    /**
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * @param version the version to set
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * @return the checkin for the Fossil program
     */
    public String getCheckin() {
        return checkin;
    }

    /**
     * @param checkin the checkin to set
     */
    public void setCheckin(String checkin) {
        this.checkin = checkin;
    }

    /**
     * @return the date
     */
    public String getDate() {
        return date;
    }

    /**
     * @param date the date to set
     */
    public void setDate(String date) {
        this.date = date;
    }
}
