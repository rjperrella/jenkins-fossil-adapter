package hudson.plugins.fossil;

// package hudson.plugins.Fossil;

import hudson.Extension;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.model.TaskThread;
import hudson.model.AbstractBuild;
import hudson.model.listeners.SCMListener;
import hudson.scm.ChangeLogSet;
import hudson.scm.AbstractScmTagAction;
import hudson.util.ArgumentListBuilder;
import hudson.util.MultipartFormDataParser;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletException;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * This class presents methods to add and remove tags from a given "CHECK-IN".
 * 
 * This was based on the code for the "git" Jenkins plugin.
 * 
 * It is not finished.
 * 
 * @author perrella
 */

public class FossilTagAction extends AbstractScmTagAction implements Describable<FossilTagAction> {

    private final List<FossilCheckinWithTags> revisions = new ArrayList<FossilCheckinWithTags>();

    /**
     * construct a Fossil Tag Action object.
     * @param build 
     */
    protected FossilTagAction(AbstractBuild<?,?> build) {
        super(build);
        new FossilTagListener().register();
    }

    /**
     * This should return a file that could be displayed in a web browser.
     * 
     * @return the icon file name
     */
    public String getIconFileName() {
        if(!isTagged() && !getACL().hasPermission(getPermission()))
            return null;
        return "save.gif";
    }

    /**
     * @return the display name for this object. 
     */
    public String getDisplayName() {
        return "Tags";
    }

    /**
     * 
     * @return true if this set of revisions has a revision that is tagged.
     */
    @Override
    public boolean isTagged() {
        if (! hasRevisions()) {
            return false;
        }
        for (FossilCheckinWithTags revision : this.revisions) {
            if (revision.isTagged()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 
     * @return a list of revisions
     */
    public List<FossilCheckinWithTags> getRevisions() {
        return this.revisions;
    }

    /**
     * 
     * @return true if there are Fossil revisions in this object
     */
    public boolean hasRevisions() {
        return this.revisions != null && ! this.revisions.isEmpty();
    }

    /**
     * handles the add request for multiple Fossil Tags
     * 
     * @param req the form request
     * @param rsp the form response
     * @throws IOException
     * @throws ServletException 
     */
    public synchronized void doSubmit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        getACL().checkPermission(getPermission());

        MultipartFormDataParser parser = new MultipartFormDataParser(req);

        Map<FossilCheckinWithTags, String> newTags = new HashMap<FossilCheckinWithTags, String>();

        int i=-1;
        for (FossilCheckinWithTags e : this.revisions) {
            ++i;
            if (parser.get("tag" + i) != null && ! parser.get("name" + i).isEmpty()) {
                newTags.put(e, parser.get("name" + i));
            }
        }

        new TagWorkerThread(newTags, parser.get("force") != null).start();

        rsp.sendRedirect(".");
    }

    /**
     * Handles the deletion request for a Fossil tag.
     * 
     * @param req the form request
     * @param rsp the form response
     * @throws IOException
     * @throws ServletException 
     */
    public synchronized void doDelete(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        getACL().checkPermission(getPermission());

        if (req.getParameter("tag") != null) {
            FossilCheckinWithTags revision = null;
            String tag = null;

            for (FossilCheckinWithTags e : this.revisions) {
                if (e.getRevId().equals(req.getParameter("revid"))) {
                    revision = e;
                    tag = req.getParameter("tag");
                }
            }

            new TagDeletionWorkerThread(revision, tag).start();
        }

        rsp.sendRedirect(".");
    }

    /**
     * Class representing a Fossil checkin and associated tags.
     */
    public static final class FossilCheckinWithTags implements Serializable {
        private String revId;
        private List<String> tags = new ArrayList<String>();

        /**
         * Create a FossilCheckinWithTags
         * 
         * @param revId a fossil checkin (aka commit)
         * @param tags a list of string tags
         */
        public FossilCheckinWithTags(String revId, List<String> tags) {
            this.revId = revId;
            this.tags = tags;
        }

        /**
         * 
         * @return the fossil checkin (aka commit)
         */
        public String getRevId() {
            return revId;
        }

        /**
         * 
         * @return the associated list of tags (strings)
         */
        public List<String> getTags() {
            if (this.tags == null) {
                this.tags = new ArrayList<String>();
            }
            return this.tags;
        }

        /**
         * Add another tag
         * @param tag 
         */
        public void addTag(String tag) {
            this.getTags().add(tag);
        }

        /**
         * Remove the matching tag
         * @param tag 
         */
        public void removeTag(String tag) {
            this.getTags().remove(tag);
        }

        /**
         * 
         * @return true if this checkin has any tags
         */
        public boolean isTagged() {
            return ! this.getTags().isEmpty();
        }

        /**
         * 
         * @return string representation of this object.
         */
        @Override
        public String toString() {
            return "revid: " + this.revId;
        }
    }

    /**
     * Class used for listening to onChangeLogParsed events.
     */
    private class FossilTagListener extends SCMListener {
        /**
         * Event handler for fossil change log parsing events.
         * 
         * @param build the jenkins build
         * @param listener the event listener
         * @param changelogset the change log set that was parsed.
         * @throws Exception 
         */
        @Override
        public void onChangeLogParsed(AbstractBuild<?,?> build, BuildListener listener, ChangeLogSet<?> changelogset) throws Exception {
            for (Object changelogEntry : changelogset) {
                if (changelogEntry instanceof FossilChangeLogEntry) {
                    FossilChangeLogEntry changeset = (FossilChangeLogEntry) changelogEntry;
                    revisions.add(new FossilCheckinWithTags(changeset.getCommitId(), changeset.getTags()));
                }
            }
        }
    }

    /**
     * The thread that performs tagging operation asynchronously.
     */
    
    private final class TagWorkerThread extends TaskThread {
        private final Map<FossilCheckinWithTags, String> tagSet;
        private final boolean force;

        public TagWorkerThread(Map<FossilCheckinWithTags, String> tagSet, boolean force) {
            super(FossilTagAction.this, ListenerAndText.forMemory());
            this.tagSet = tagSet;
            this.force = force;
        }

        /**
         * This method is used to tag a bunch of revisions.
         * 
         * @param listener 
         */
        @Override
        protected void perform(TaskListener listener) {
            try {
                PrintStream logger = listener.getLogger();
                Launcher launcher = new LocalLauncher(listener);
                FossilScm FossilSCM = (FossilScm) getBuild().getProject().getScm();

                for (Entry<FossilCheckinWithTags, String> e : tagSet.entrySet()) {
                    logger.println("Tagging " + e.getKey() + " to " + e.getValue());

                    ArgumentListBuilder args = new ArgumentListBuilder();
                    args.add(FossilSCM.getDescriptor().getFossilExecutable(), "tag", "add");
                    args.add(e.getValue());  // this is the tag name
                    args.add(e.getKey().getRevId()); // this is the "check-in" (in Fossil terms).
                    //  TODO:  Currently, pulling branches is not supported.
                    /**
                    if (this.force) {
                        args.add("--force");
                    }
                     * */

                    if (launcher.launch().cmds(args).envs(build.getEnvironment(listener)).stdout(listener.getLogger()).join() != 0) {
                        listener.error("Failed to tag");
                    } else {
                        e.getKey().addTag(e.getValue()); /// what does this do?
                    }
                }
                getBuild().save();
           } catch (Throwable e) {
               e.printStackTrace(listener.fatalError(e.getMessage()));
           }
        }
    }

    /**
     * The thread that performs tag deleting operation asynchronously.
     * 
     * Only one tag can be deleted for a "CHECK-IN".
     * 
     */
    private final class TagDeletionWorkerThread extends TaskThread {
        private final FossilCheckinWithTags revision;
        private final String tag;

        public TagDeletionWorkerThread(FossilCheckinWithTags revision , String tag) {
            super(FossilTagAction.this, ListenerAndText.forMemory());
            this.revision = revision;
            this.tag = tag;
        }

        @Override
        protected void perform(TaskListener listener) {
            try {
                PrintStream logger = listener.getLogger();
                Launcher launcher = new LocalLauncher(listener);
                FossilScm FossilSCM = (FossilScm) getBuild().getProject().getScm();

                logger.println("Removing tag " + tag);

                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add(FossilSCM.getDescriptor().getFossilExecutable(), "tag","cancel");
                args.add(tag);
                args.add(revision.getRevId()); // this is the "check-in" (in Fossil terms).
                args.add("--repository", FossilSCM.getLocalRepository());

                if (launcher.launch().cmds(args).envs(build.getEnvironment(listener)).stdout(listener.getLogger()).join() != 0) {
                    listener.error("Failed to delete tag");
                } else {
                    revision.removeTag(tag);
                }
                getBuild().save();
           } catch (Throwable e) {
               e.printStackTrace(listener.fatalError(e.getMessage()));
           }
        }
    }


    /**
     * Point to an extension descriptor for Fossil.
     */

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    /**
     * 
     * @return the Descriptor for this object
     */
    public Descriptor<FossilTagAction> getDescriptor() {
        return DESCRIPTOR;
    }
    /**
     * A descriptor implementation describes the Fossil Tag Action class.
     */
    public static class DescriptorImpl extends Descriptor<FossilTagAction> {
        /**
         * construct a descriptor for this class.
         */
        protected DescriptorImpl() {
            super(FossilTagAction.class);
        }

        /**
         * @return name of this kind of object
         */
        
        @Override
        public String getDisplayName() {
            return "Descriptor<FossilTagAction>";
        }
    }

}