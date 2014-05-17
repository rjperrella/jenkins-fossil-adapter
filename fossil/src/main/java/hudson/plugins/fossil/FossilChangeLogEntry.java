
package hudson.plugins.fossil;

import static hudson.Util.fixEmpty;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import hudson.tasks.Mailer;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.List;

import org.kohsuke.stapler.export.Exported;

/**
 * Represents a "change log" which is a bunch of file changes, tags, and the author who did it.
 *
 * https://wiki.jenkins-ci.org/display/JENKINS/Change+log
 * 
 * <p>
 * The object should be treated like an immutable object.
 * </p>
 *
 * @author Trond Norbye (Bazaar version)
 * @author Ron Perrella
 */
public class FossilChangeLogEntry extends ChangeLogSet.Entry {

    private String author;
    private String authorEmail;
    private String revid;
    private List<String> tags = new ArrayList<String>();

    private String date;
    private String msg;

    private boolean isMerge = false;

    private List<FossilAffectedFile> affectedFiles = new ArrayList<FossilAffectedFile>();

    /**
     * Create an empty Fossil Change Log object
     */
    FossilChangeLogEntry()
    {
        author = "";
        authorEmail = "";
        revid = "";
        date = "";
        msg = "";
    }
    /**
     * Get the Checkin (aka Commit) message.
     * 
     * This is a mandatory part of the change log architecture.
     * 
     * @return the checkin message
     */
    @Exported
    public String getMsg() {
        return msg;
    }

    /**
     * Gets the user who made this change.
     * 
     * This is a mandatory part of the Change Log Architecture
     * 
     * @return the author of the checkin (aka commit)
     */
    @Exported
    public User getAuthor() {
        User user = User.get(author, false);

        if (user == null) {
            user = User.get(author, true);

            // set email address for user
            if (fixEmpty(authorEmail) != null) {
                try {
                    user.addProperty(new Mailer.UserProperty(authorEmail));
                } catch (IOException e) {
                    // ignore error
                }
            }
        }

        return user;
    }

    /**
     * @return the checkin (aka commit) of the change log
     */
    @Exported
    public String getCommitId() {
        return revid;
    }

    /**
     * @return a list of tags for this checkin (aka commit) for this change log
     */
    @Exported
    public List<String> getTags() {
        return tags;
    }

    /**
     * @return the date in string form for the checkin (aka commit) for this change log
     */
    @Exported
    public String getDate() {
        return date;
    }
    
    /**
     * @return the timestamp associated with the date for this change log.
     */
    
    @Exported
    public long getTimeStamp()
    {
        
        Calendar cal = new GregorianCalendar();
        
        try
        {
            DateFormat df = new SimpleDateFormat();
            cal.setTime( df.parse(date) );
            return getTimeStamp();
        }catch(ParseException e)
        {
            return (-1);
        }
    }

    /**
     * @return whether or not this change log is a merge
     */

    @Exported
    public boolean isMerge() {
        return this.isMerge;
    }

    /**
     * This is mandatory part of the Change Log Architecture.
     * 
     * @return a collection of affected file paths
     */
    @Override
    public Collection<String> getAffectedPaths() {
        return new AbstractList<String>() {
            public String get(int index) {
                return affectedFiles.get(index).getPath();
            }
            public int size() {
                return affectedFiles.size();
            }
        };
    }

    /**
     * @return a collection of affectedFiles for this change log
     */
    @Override
    public Collection<FossilAffectedFile> getAffectedFiles() {
        return affectedFiles;
    }

    /**
     * Associate a parent change log set for this change log.
     * 
     * @param parent 
     */
    @Override
    protected void setParent(ChangeLogSet parent) {
        super.setParent(parent);
    }

    /**
     * Set the commit message.
     * 
     * @param msg 
     */
    public void setMsg(String msg) {
        this.msg = msg;
    }

    /**
     * Set the author field
     * @param author the author responsible for this checkin (aka commit)
     */
    public void setAuthor(String author) {
        this.author = author;
    }
    /**
     * Set the author Email field (if available) May be empty string.
     * 
     * @param authorEmail 
     */
    public void setAuthorEmail(String authorEmail) {
        this.authorEmail = authorEmail;
    }
    /**
     * Set the Reviid (checkin aka commit) for this change log.
     * 
     * @param revid 
     */
    public void setRevid(String revid) {
        this.revid = revid;
    }

    /**
     * Set the list of Tags for this change log.
     * 
     * @param tags 
     */
    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    /**
     * Set the date that corresponds to the checkin (aka commit) for this change log.
     * 
     * @param date 
     */
    public void setDate(String date) {
        this.date = date;
    }
    /**
     * set whether or not this was a merge.
     * 
     * @param isMerge 
     */
    public void setMerge(boolean isMerge) {
        this.isMerge = isMerge;
    }
    /**
     * Add another affected file to the change log.
     * 
     * @param affectedFile the affected file for the change log.
     */
    public void addAffectedFile(FossilAffectedFile affectedFile) {
        affectedFile.setChangeSet(this);
        this.affectedFiles.add(affectedFile);
    }
}