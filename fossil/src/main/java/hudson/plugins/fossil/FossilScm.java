package hudson.plugins.fossil;

import hudson.EnvVars;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.Launcher.ProcStarter;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.scm.SCM;

import hudson.model.Cause;
import hudson.model.Descriptor.FormException;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.RepositoryBrowser;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 *
 * A Jenkins plug-in to support the Fossil SCM system.
 * 
 * For info about Fossil, go to http://www.fossil-scm.org
 * 
 * The basis for this implementation was the Bazaar SCM Plug-in by Trond Norbye <i>et al</i>.
 * 
 * The basic idea is that a build is performed as follows:
 * <ol>
 * <li>Cloning a remote repository</li>
 * <li>Opening the repository (this populates files into the workarea)</li>
 * <li>Closing the repository (this releases control of the repository)</li>
 * <li>Performing the build</li>
 *</ol>
 * Subsequent builds <i>can</i> perform incremental builds as follows:
 * <ol>
 * <li>Confirm that there is a repository in the Jenkins work-area that corresponds to the remote repository of the build request</li>
 * <li>Pull changes from the remote repository</li>
 * <li>Update the workspace  (note: there may be merging required) </li>
 * <li>Perform the build</li>
 * </ol>
 * 
 * 
 * @author Ronald Perrella
 */

/* 
 * TODO: future - need to add a list of branches to build as well. (see Git plugin)
 * TODO: future - need a way to specify a tag to build as well. (instead of just latest.)
 * TODO: add validation to all Jelly forms.
 * TODO: refactoring: remove all Fossil command and put them into their own class.
 * TODO: add ability to build a branch rather than just the trunk.
 */
public class FossilScm extends SCM {

    /**
     * As with git SCM, store a config version so that we're able to keep up with
     * configuration changes over time.
     */
    private Long configVersion = 1L;
    private boolean cleanBuild;  // should we clear workspace before pulling code?
    private String localRepository;  // full path to localRepository file (whatever name user chooses).
    private boolean https;
    private String server;
    private String port;
    private String username;  // typically, just a read-only id for pulling files.
    private String password;
    private String serverpath; // remaining path
    private boolean useTagging = false;  // if true, tag builds with a set of tags.
    private RepositoryBrowser repositoryBrowser;
    private String branch = ""; // may be empty if trunk build is required.

    /**
     * Construct a FossilScm object which represents a handle to the Fossil SCM in your environment.
     * 
     * Note: The names of these fields matter because they will be populated with JSON from the config.jelly file.
     * 
     * @param https the flag that indicates use of https or http
     * @param server the URL for the Fossil server which has the repository
     * @param serverpath the remaining path to be appended to the server url.
     * @param port TCP/IP port number on which the Fossil server is listening. Default is 80.
     * @param repository the repository to work with in this build (basename)
     * @param clean a flag representing the desire to do a cleanBuild build
     * @param username username for the remote repository (anonymous may be permitted)
     * @param password password for the remote repository
     */
    @DataBoundConstructor
    public FossilScm(boolean https, String server, String serverpath, String port, String repository, boolean clean, String username, String password) {
        this.server = server;

        if (port == null || "".equals(port)) {
            this.port = "";
        }

        this.https = https;

        this.localRepository = repository;
        this.cleanBuild = clean;
        this.username = username;
        this.password = password;

        this.repositoryBrowser = new FossilRepositoryBrowser(getServerUrl());
    }

    /**
     * BOGUS constructor. For testing purposes only. 
     */
    public FossilScm() {
        this.server = "example.com";
        this.port = "80";
        this.serverpath = "";
        this.https = false;
        this.username = "jenkins";
        this.password = "jenkins";
        this.cleanBuild = false;
        this.localRepository = "repo";
        this.repositoryBrowser = new FossilRepositoryBrowser(getServerUrl());
    }

    /**
     * Obtains a fresh workspace of the module(s) into the specified directory of the specified slave machine.
     * 
     * Will be  using the <em>fossil</em> <em>update</em>, <em>clone</em>, or <em>pull</em> command to do so, depending on the user's request
     * as well as the state of the repository in the workspace.
     * 
     * This is the implementation of an inherited abstract function.
     * 
     * @param build 
     * @param launcher abstracts away the machine that files will be checked-out.
     * @param workspace a directory to check out the source code. May contain left-over from previous build.
     * @param listener where logs are sent
     * @param changelogFile file containing the change log
     * @return true if checkout was successful.
     * @throws IOException
     * @throws InterruptedException 
     */
    @Override
    public boolean checkout(AbstractBuild<?, ?> build,
            Launcher launcher,
            FilePath workspace,
            BuildListener listener,
            File changelogFile)
            throws IOException, InterruptedException {
        List<Cause> causes = new ArrayList();
        listener.started(causes);

        listener.getLogger().println("info: Starting checkout...");
        logSettings(listener);

        final String repo = getLocalRepository();
        if (null == repo || "".equals(repo)) {
            listener.fatalError("repository cannot be empty string.");
            return false;
        }

        /*
         * Determine if a localRepository is already present in the workspace. If so, it may be possible to perform
         * an "pull" + "update" rather than a "clone" (which is slower).
         * 
         * This command performs a (possibly) remote check of the existence of the repository file in the workspace on the (remote) slave server.
         */

        FilePath fp = new FilePath(workspace, getLocalRepository());
        boolean canUpdate = fp.exists();

        listener.getLogger().println("info: CanUpdate: " + (canUpdate ? "true" : "false"));

        boolean shouldUpdate = true;  // controlling switch.

        boolean result = update(canUpdate, shouldUpdate, cleanBuild, build, launcher, workspace, listener, changelogFile);

        // TODO: Find a reasonable way to tag a build.
        // This code pretends to go ahead and tag build.
        // but what tag do we use? (or apparently, we can use multiple tags.)
        // the reason question - is why bother - this would only tag the local repository.

        if (useTagging) {
            build.addAction(new FossilTagAction(build));
            listener.getLogger().println("info: tagged build.");
        }

        listener.getLogger().println("info: Ending checkout...");

        return result;
    }

    private void logSettings(BuildListener listener) {
        listener.getLogger().println("serverUrl     :" + getServerUrl());
        listener.getLogger().println("repository    :" + getLocalRepository());
        listener.getLogger().println("username      :" + getUsername());
        listener.getLogger().println("password      :" + (getPassword().equals("") ? "*Not Empty*" : "*Empty*"));
        listener.getLogger().println("type          :" + getType());

    }

    /**
     * update the workspace with code from the remote localRepository.
     * 
     * This method exists in order to allow for incremental updates of the workspace. This would reduce
     * network bandwidth as well as time to build. The disadvantage is potential for merges.
     * 
     * NOTE: Fossil has its own "wipeWorkspace" command, which removes files that are not in the repository. This could be used as
     * an alternative to wiping the directory.  Wiping the directory mandates a clone be performed, since the repository file is contained within
     * the workspace.
     * 
     * 
     * @param cleanBuild
     * @param build
     * @param launcher
     * @param workspace
     * @param listener
     * @param changelogFile
     * @return true if successful, false if failure.
     * @throws InterruptedException
     * @throws IOException 
     */
    private boolean update(boolean canUpdate, boolean allowUpdate, boolean wipeWorkspace, AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener, File changelogFile) throws InterruptedException, IOException {

        FossilRevisionState oldRevisionState = getRevisionState(build, launcher, listener, workspace, getLocalRepository());

        if (wipeWorkspace) {
            fossil_clean_workspace(listener, launcher, workspace);
        } else {
            listener.getLogger().println("info: Not cleaning workspace (as requested by user) ...");
        }

        if (canUpdate && allowUpdate && (!wipeWorkspace)) {
            if (!populate_workspace_from_pull(build, launcher, workspace, listener)) {
                return false;
            }
        } else {
            if (!populate_workspace_from_clone(build, launcher, workspace, listener)) {
                return false;
            }
        }

        FossilRevisionState newRevisionState = getRevisionState(build, launcher, listener, workspace, getLocalRepository());

        if (oldRevisionState != null && newRevisionState != null) {
            getLogBetweenRevisionStates(launcher, workspace, oldRevisionState, newRevisionState, changelogFile); // NOTE: updates the changeLogFile
        }

        return true;
    }

    private boolean fossil_clean_workspace(BuildListener listener, Launcher launcher, FilePath workspace)
            throws InterruptedException {
        listener.getLogger().println("info: Cleaning workspace...");
        if (false) {
            try {
                listener.getLogger().println("Removing repository from workspace...");
                fossil_delete_repository(launcher, listener, workspace);
                listener.getLogger().println("Cleaning workspace...");
                workspace.deleteRecursive();
            } catch (IOException e) {
                e.printStackTrace(listener.fatalError("Failed to cleanBuild the workspace"));
                return false;
            }


        } else {
            listener.getLogger().println("info: Cleaning workspace...");
        }
        return true;
    }

    /**
     * Populate the workspace with content from a Fossil localRepository.
     * 
     * There is an option provided for a pull+update instead of a clone.
     * 
     * @param build 
     * @param launcher used to execute the Fossil command.
     * @param workspace target for the command
     * @param listener
     * @return
     * @throws InterruptedException 
     */
    private boolean populate_workspace_from_pull(AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener)
            throws InterruptedException, IOException {

        // TODO: Not enabling this in my primary workmachine.  Let's do this in a VM!

        return true
                && fossil_open(build, launcher, workspace, listener) // make sure it is open
                && fossil_pull(build, launcher, workspace, listener)
                && fossil_update(build, launcher, workspace, listener) // update files with pulled changes in repo.
                && fossil_close(build, launcher, workspace, listener);
    }

    /**
     * Populate the workspace with content from a Fossil localRepository.
     * 
     * When cloning, we remove the existing repo because the fossil clone will complain otherwise.
     * 
     * @param build 
     * @param launcher used to execute the Fossil command.
     * @param workspace target for the command
     * @param listener
     * @return
     * @throws InterruptedException 
     */
    private boolean populate_workspace_from_clone(AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener)
            throws InterruptedException, IOException {
        return true
                && fossil_delete_repository(launcher, listener, workspace)
                && fossil_clone(build, launcher, workspace, listener)
                && fossil_settings("autosync", "off", build, launcher, workspace, listener)
                && fossil_open(build, launcher, workspace, listener)
                && fossil_close(build, launcher, workspace, listener);
    }

    /**
     * Execute a "open" command using Fossil.
     * 
     * @param build
     * @param launcher
     * @param workspace
     * @param listener
     * @return true if successful
     * @throws InterruptedException 
     */
    private boolean fossil_open(AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener) throws InterruptedException, IOException {
        ArgumentListBuilder args = new ArgumentListBuilder();

        FilePath repopath = new FilePath(new File(workspace.getRemote(), getLocalRepository()));

        if (!repopath.exists()) {
            listener.fatalError("fossil cannot open a missing repository (" + repopath.getRemote() + ")");
            return false;
        }



        args.add(getDescriptor().getFossilExecutable(), "open");
        args.add(getLocalRepository());

        try {
            // remember not to log the username and password...
            if (launcher.launch().cmds(args).envs(build.getEnvironment(listener)).stdout(listener.getLogger()).pwd(workspace).join() != 0) {
                listener.fatalError("Failed to open repository '" + getLocalRepository());
                return false;
            }
        } catch (IOException e) {
            listener.fatalError("Failed to open repository '" + getLocalRepository());
            return false;
        }
        return true;
    }

    /**
     * Delete the repository on the remote slave, assuming the same workspace path and repository name.
     * 
     * Note that Fossil keeps track of repositories on a machine (in a special file) which is how the "fossil all" commands work.
     * Having this method allows us to remove the repository cleanly prior to cleaning the files themselves from the workspace.
     *
     * @param build
     * @param launcher
     * @param workspace
     * @param listener
     * @return
     * @throws InterruptedException
     * @throws IOException 
     */
    private boolean fossil_delete_repository(Launcher launcher, BuildListener listener, FilePath workspace)
            throws IOException, InterruptedException {
        FilePath repopath = new FilePath(workspace, getLocalRepository());

        repopath.delete();
        return true;
    }

    /**
     * Execute a Fossil update, which updates the workspace with files from the local repository.
     * 
     * @param build
     * @param launcher
     * @param workspace
     * @param listener
     * @param flag
     * @return
     * @throws InterruptedException 
     */
    private boolean fossil_update(AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener) throws InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        String command = "update";

        args.add(getDescriptor().getFossilExecutable(), command.toLowerCase());
        args.add(getBuildTag());

        try {
            if (launcher.launch().cmds(args).envs(build.getEnvironment(listener)).stdout(listener.getLogger()).pwd(workspace).join() != 0) {
                listener.fatalError("Failed to " + args.toStringWithQuote() + " workspace = '" + workspace + "'");
                return false;
            }
        } catch (IOException e) {
            listener.fatalError("Failed to " + args.toStringWithQuote() + " workspace = '" + workspace + "'");
            return false;
        }
        //        listener.getLogger().println("info: successfully ran single command:" + command);
        return true;
    }

    /**
     * Execute a "pull" command using Fossil.
     * 
     * @param build
     * @param launcher
     * @param workspace
     * @param listener
     * @return true if successful
     * @throws InterruptedException 
     */
    private boolean fossil_pull(AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener) throws InterruptedException, IOException {

        FilePath repopath = new FilePath(new File(workspace.getRemote(), getLocalRepository()));

        if (!repopath.exists()) {
            listener.error("fossil cannot open a missing repository (" + repopath.getRemote() + ")");
            return false;
        }

        ArgumentListBuilder args = new ArgumentListBuilder();

        args.add(getDescriptor().getFossilExecutable(), "pull");
        args.add(getAuthenticatedServerUrl());
        args.add("--repository", getLocalRepository());
        args.add("--once"); // dont remember the URL (fossil normally does.)

        try {
            // remember not to log the username and password...
            if (launcher.launch().cmds(args).envs(build.getEnvironment(listener)).stdout(listener.getLogger()).pwd(workspace).join() != 0) {
                listener.fatalError("Failed to pull from server '" + getServerUrl() + "' into repository '" + getLocalRepository() + "'");
                return false;
            }
        } catch (IOException e) {
            listener.fatalError("IOException: Failed to pull from server '" + getServerUrl() + "' into repository '" + getLocalRepository() + "'");
            return false;
        }
        return true;
    }

    /**
     * Execute a "clone" command (no arguments) using Fossil.
     * After a clone, you have to open the localRepository to see populate the workspace.
    
     * @param build
     * @param launcher
     * @param workspace
     * @param listener
     * @return true if successful
     * @throws InterruptedException 
     */
    private boolean fossil_clone(AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener) throws InterruptedException, IOException {
        ArgumentListBuilder args = new ArgumentListBuilder();

        FilePath repopath = new FilePath(new File(workspace.getRemote(), getLocalRepository()));

        if (repopath.exists()) {
            listener.fatalError("fossil cannot clone over an existing repository (" + repopath.getRemote() + ")");
            return false;
        }

        args.add(getDescriptor().getFossilExecutable(), "clone");
        args.add(getAuthenticatedServerUrl());
        args.add(getLocalRepository());

        try {
            if (launcher.launch().cmds(args).envs(build.getEnvironment(listener)).stdout(listener.getLogger()).pwd(workspace).join() != 0) {
                listener.fatalError("Failed to clone from server '" + getServerUrl() + "' into repository '" + getLocalRepository() + "'");
                return false;
            }
        } catch (IOException e) {
            listener.fatalError("Failed to clone from server '" + getServerUrl() + "' into repository '" + getLocalRepository() + "'");
            return false;
        }
        return true;
    }

    /**
     * Set a setting on a Fossil repository.
     * 
     * Used predominantly to set autosync off but is general purpose.
     * 
     * Uses the default repository.
     * 
     * @param setting one of the allowable settings for the fossil repository
     * @param value typically "on" or "off" but also may be a list. Depends on the setting.
     * @param build
     * @param launcher
     * @param workspace
     * @param listener
     * @return
     * @throws InterruptedException 
     */
    private boolean fossil_settings(String setting, String value, AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener)
            throws InterruptedException, IOException {

        ArgumentListBuilder args = new ArgumentListBuilder();
        String command = "settings";

        args.add(getDescriptor().getFossilExecutable(), command.toLowerCase());
        args.add(setting);
        args.add(value);

        try {

            if (launcher.launch().cmds(args).envs(build.getEnvironment(listener)).stdout(listener.getLogger()).pwd(workspace).join() != 0) {
                listener.error("Failed to " + args.toStringWithQuote() + " workspace = '" + workspace + "'");
                return false;
            }
        } catch (IOException e) {
            listener.error("Failed to " + args.toStringWithQuote());
            return false;
        }
        listener.getLogger().println("successfully ran single command:" + command);
        return true;
    }

    /**
     * Execute a single command (no arguments) using Fossil.
     * 
     * Used predominantly to open and close the localRepository.
     * 
     * @param build
     * @param launcher
     * @param workspace
     * @param listener
     * @return
     * @throws InterruptedException 
     */
    private boolean fossil_close(AbstractBuild<?, ?> build, Launcher launcher, FilePath workspace, BuildListener listener) throws InterruptedException, IOException {
        FilePath repopath = new FilePath(new File(workspace.getRemote(), getLocalRepository()));

        if (!repopath.exists()) {
            listener.error("fossil cannot open a missing repository (" + repopath.getRemote() + ")");
            return false;
        }

        ArgumentListBuilder args = new ArgumentListBuilder();
        String command = "close";

        args.add(getDescriptor().getFossilExecutable(), command.toLowerCase());
        args.add(getLocalRepository()); // local localRepository name
        args.add("--force");

        try {

            if (launcher.launch().cmds(args).envs(build.getEnvironment(listener)).stdout(listener.getLogger()).pwd(workspace).join() != 0) {
                listener.error("Failed to " + args.toStringWithQuote() + " workspace = '" + workspace + "'");
                return false;
            }
        } catch (IOException e) {
            listener.error("Failed to " + args.toStringWithQuote());
            return false;
        }
        listener.getLogger().println("successfully ran single command:" + command);
        return true;
    }

    /**
     * This method determines what is the revision state of a local repository (NOT "THE" localReository but any local repo file.)
     *  
     * @param launcher
     * @param listener
     * @param root is the remote top level directory of the remote repository.
     * @return a non-null FossilRevisionState object representing the state of the given repository in the given workspace.
     * @throws InterruptedException 
     */
    private FossilRevisionState getRevisionState(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, FilePath workspace, String repo)
            throws InterruptedException {

        logger.info("ENTER:getRevisionState()");

        if ("".equals(repo)) {
            logger.info("EXIT:getRevisionState() - empty repo");
            throw new InterruptedException("Cannot find repo therefore cannot obtain revision state.");
        }
        FossilRevisionState rev = null;
        try {
            if (launcher == null) {
                /* Running for a VM or whathaveyou: make a launcher on master
                 * todo grab a launcher on 'any slave'
                 */
                launcher = new LocalLauncher(listener);
            }
            PrintStream output = listener.getLogger();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            ArgumentListBuilder args;

            args = new ArgumentListBuilder();

            FilePath repopath = new FilePath(new File(getLocalRepository()));

            if (!repopath.exists()) {
                logger.warning("EXIT:getRevisionState() - no repo at location.");
                return null;
            }


            args.add(getDescriptor().getFossilExecutable(), "open", repo, "--keep");   // fast way to open a repo.

            ProcStarter starter = launcher.launch().cmds(args).stdout(stdout).stderr(stderr).pwd(workspace);

            // The launcher should already have the right vars!
            // starter = starter.envs(EnvVars.masterEnvVars);

            final int ret = starter.join();
            final String info_output = args.toStringWithQuote() + " returned " + ret + ". Command output: \"" + stdout.toString() + "\" stderr: \"" + stderr.toString() + "\"";
            if (ret != 0) {
                logger.severe(info_output);
            } else {
                logger.info("INFO OUTPUT:" + info_output);
                Map<String, String> ht = fossil_info_parser(info_output);
                String checkin = ht.get("checkout:");
                if (checkin != null) {
                    rev = new FossilRevisionState(checkin);
                } else {
                    logger.log(Level.WARNING, "Unable to determine hash for repository '{0}'",
                            repo);
                }
            }

            output.printf("info result: %s\n", info_output);

            fossil_close(build, launcher, workspace, listener);

        } catch (IOException e) {
            StringWriter w = new StringWriter();
            e.printStackTrace(new PrintWriter(w));
            logger.log(Level.WARNING, "Failed to poll repository: ", e);
        }

        if (rev == null) {
            logger.log(Level.WARNING, "Failed to get revision state for: {0}", repo);
        }

        logger.info("EXIT:getRevisionState()");
        return rev;
    }

    /**
     * It turns out, Fossil is easy to poll since the server exposes a timeline RSS feed.
     * 
     * (pollChanges is deprecated by the way.)
     * 
     * @see pollChanges
     * @see poll
     * @return whether or not Fossil support polling for changes
     */
    @Override
    public boolean supportsPolling() {
        return true;
    }

    /**
     * Calculates an object that represents the state of the workspace of the given build.
     * 
     * Since we are using the optimization that allows for the checkout to compute this,
     * this method does not have to implement anything and should therefore return null.
     * 
     * This method is called after source code is checked-out  {@link FossilScm#checkout(AbstractBuild, Launcher, FilePath, BuildListener, File)}
     * 
     * @param build
     * @param launcher
     * @param listener
     * @return null
     * @throws IOException 
     * @throws InterruptedException 
     */
    @Override
    public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> build,
            Launcher launcher,
            TaskListener listener)
           throws IOException, InterruptedException {

        return null;
    }

    /**
     * This method returns the current remote revision state.
     * 
     * The technique being used is to parse the RSS feed that is provided by the
     * fossil server.
     * 
     * @return current remote revision state
     * @throws IOException
     * @throws InterruptedException 
     */
    private SCMRevisionState getCurrentRevisionState() throws IOException, InterruptedException {

        StringBuffer rss = new StringBuffer(100000);  // arbitrarily large initial buffer. Should a StringBuilder be used instead?

        URL url = new URL(getAuthenticatedServerUrl() + "/timeline.rss?y=ci&n=0");

        BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
        String inputLine;

        while ((inputLine = in.readLine()) != null) {
            rss.append(inputLine);
        }
        in.close();

        return FossilRSSParser.parse(rss.toString());
    }

    /**
     * Determine if the current remote revision has changed from what is in the 'baseline' revision.
     * 
     * @param project
     * @param launcher
     * @param workspace
     * @param listener
     * @param baseline to compare to
     * @throws IOException
     * @throws InterruptedException 
     * @returns A PollingResult object
     */
    @Override
    protected PollingResult compareRemoteRevisionWith(AbstractProject<?, ?> project,
            Launcher launcher, // may be null if a workspace is not required to poll.
            FilePath workspace, // may be null if a workspace is not required to poll.
            TaskListener listener,
            SCMRevisionState baseline) // returned from a prior compareRemoteRevisionWith()
            throws IOException,
            InterruptedException {

        if (workspace == null) {
            return PollingResult.NO_CHANGES;
        }

        if (launcher == null) {
            return PollingResult.NO_CHANGES;
        }

        PrintStream output = listener.getLogger();
        output.printf("info: Getting current remote revision...");

        SCMRevisionState current = getCurrentRevisionState();


        if (baseline.getDisplayName().equals(current.getDisplayName())) {
            output.printf("info: baseline:" + baseline.getDisplayName() + " == " + current.getDisplayName());
            return PollingResult.NO_CHANGES;
        }
        output.printf("info: baseline:" + baseline.getDisplayName() + " != " + current.getDisplayName());


        return PollingResult.SIGNIFICANT;
    }

    /**
     * The returned object will be used to parse the changelog.xml file.
     * 
     * @return a parser that can parse the changelog.xml file.
     */
    @Override
    public ChangeLogParser createChangeLogParser() {
        return new FossilChangeLogParser();
    }

    // "fossil info" or "fossil open --keep" output looks like:
    /* 
    project-name: Blabla
    localRepository:   C:/src/blabla/blabla
    local-root:   C:/src/myroot/
    user-home:    C:/Users/username/AppData/Local
    project-code: 640e13fdd114a5894d9fa42576432cf51379b6be
    checkout:     886b406bcf4276879cc9d1c9869772991aeaf21e 2012-06-02 22:42:54 UTC
    parent:       2dd1b06dcc27781581f2bd2cbe269458ccf0b4ee 2012-06-02 22:18:35 UTC
    tags:         trunk
    comment:      made a comment  here. (user: user2)
    more stuff.
     */
    /**
     * This method takes the information that comes out of the Fossil client program
     * and parses it into a Map of strings associated to strings.
     * 
     * @param info the string of information that comes from the Fossil program.
     * @return 
     */
    // TODO: someday replace with a statemachine - would be more tolerant to changes.
    public Map<String, String> fossil_info_parser(String info) {


        // Gotta extract the "checkout" line as revision.
        // need first to extract lines, then strings.  Remember - filenames *could* have spaces.
        // (hopefully no newlines.)

        Map<String, String> tab = new HashMap<String, String>();

        String[] lines = info.split("\\n");
        int linenum = 0;

        if (lines.length < 9) {
            return tab;
        }

        String[] field = lines[linenum].trim().split("\\s++");

        if (field[0].equals("project-name:")) {
            tab.put("project-name", field[1]);
        }

        field = lines[++linenum].trim().split("\\s++");
        if (field[0].equals("repository:")) {
            tab.put("repository", field[1]);
        }

        field = lines[++linenum].trim().split("\\s++");
        if (field[0].equals("local-root:")) {
            tab.put("local-root", field[1]);
        }

        field = lines[++linenum].trim().split("\\s++");
        if (field[0].equals("user-home:")) {
            tab.put("user-home", field[1]);
        }

        field = lines[++linenum].trim().split("\\s++");
        if (field[0].equals("project-code:")) {
            tab.put("project-code", field[1]);
        }

        field = lines[++linenum].trim().split("\\s++");
        if (field[0].equals("checkout:")) {
            tab.put("checkout", field[1]);
            tab.put("checkout-date", lines[linenum].substring(55).trim());
        }

        field = lines[++linenum].trim().split("\\s++");
        if (field[0].equals("parent:")) {
            tab.put("parent", field[1]);
            tab.put("parent-date", lines[linenum].substring(55).trim());
        }

        // TODO: I've only ever seen one line of tags. What happens for more than one line worth?
        field = lines[++linenum].trim().split("\\s++");
        if (field[0].equals("tags:")) {
            tab.put("tags", lines[linenum].substring(14).trim());
        }

        // Gather up all comment lines. The comment line is the last line in the output.

        if (lines[++linenum].startsWith("comment:")) {
            String s = "";
            while (linenum < lines.length) {
                s = s + lines[linenum].substring(14).trim() + "\n";
                linenum++;
            }
            tab.put("comment", s);
        } else {
            tab.put("comment", "no comment block found. Found:'" + lines[linenum] + "'");
        }

        return tab;
    }

    /**
     * Produce the log of changes from oldRevision to newRevision and store it into the passed-in changeLog file.
     * 
     * Fossil has the "timeline" command which will give you a summary of changes going back in time  given a last revision but not between
     * two revisions.  
     * 
     * Note that Fossil only displays the first 10 characters of the revision hash as part of the timeline.
     * 
     * Also, we will ignore non-file changes (Fossil also deltas wiki and tickets).
     * 
     * Warning: current implementation is probably on the order of twice as slow as it could be. This is due to double request for timeline.
     * This was deemed acceptable since build time will generally far outweigh timeline request.
     * 
     * @param launcher
     * @param workspace
     * @param oldRevisionState
     * @param newRevisionState
     * @param changeLog
     * @throws InterruptedException 
     */
    private void getLogBetweenRevisionStates(Launcher launcher, FilePath workspace, FossilRevisionState oldRevisionState, FossilRevisionState newRevisionState, File changeLog) throws InterruptedException {
        try {
            int ret;
            final String many_revisions = "2000000";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            ArgumentListBuilder args = new ArgumentListBuilder();

            args.add(getDescriptor().getFossilExecutable(), "timeline", "before", newRevisionState.getRevId(), "-n", many_revisions, "-t", "ci");

            if ((ret = launcher.launch().cmds(args).envs(EnvVars.masterEnvVars).stdout(baos).pwd(workspace).join()) != 0) {
                logger.log(Level.WARNING, args.toStringWithQuote() + "returned {0}", ret);
            } else {
                // Problem: Need to scan through the changeLog and remove all entries "BEFORE" oldRevisionState.
                // one way would be to search through the "baos" looking for the oldRevisionState revid
                // and truncate from that point forward.  Now, we need to only search for it in the proper place  in the string
                // in case that sequence appears somewhere else in a command or something.
                //
                // Another way is to repeat the timeline for the changeLog "BEFORE" the oldRevisionState and calculate
                // the size of that changeLog.  Then, use the size to truncate that many bytes off of the newRevisionState timeline.
                // The tradeoff is avoiding to search the bytearray at a cost of reproducing the changelog which is an expensive
                // operation. In addition, we have to keep two changelogs in memory.
                //
                // On the other hand, this is easy to implement and has low overall impact so that's what I did.
                //
                // The impact is roughly double the time and double the memory consumed.

                ByteArrayOutputStream baos_old = new ByteArrayOutputStream();

                ArgumentListBuilder args_old = new ArgumentListBuilder();

                args_old.add(getDescriptor().getFossilExecutable(), "timeline", "before", oldRevisionState.getRevId(), "-n", many_revisions, "-t", "ci");

                if ((ret = launcher.launch().cmds(args_old).envs(EnvVars.masterEnvVars).stdout(baos_old).pwd(workspace).join()) != 0) {
                    logger.log(Level.WARNING, args_old.toStringWithQuote() + "returned {0}", ret);
                } else {
                    FileOutputStream fos = new FileOutputStream(changeLog);
                    fos.write(baos.toByteArray(), 0, baos.size() - baos_old.size());  // truncate to relevant part of timeline.
                    fos.close();
                }
            }
        } catch (IOException e) {
            StringWriter w = new StringWriter();
            e.printStackTrace(new PrintWriter(w));
            logger.log(Level.WARNING, "Failed to poll repository: ", e);
        }
    }
    /**
     * 
     * @return a repository browser
     */
    public RepositoryBrowser getRepositoryBrowser() {
        return repositoryBrowser;
    }


    /**
     * Create a correct URL string with authentication built-in, using the server URL as a starting point.
     * 
     * @return a Fossil localRepository URL string with embedded username and password
     */
    public String getAuthenticatedServerUrl() {
        String serverUrl = (https ? "https" : "http") + "://" + username + ":" + password + "@" + server + ":" + port + "/" + serverpath;

        return serverUrl;
    }

    /**
     * @return the url the refers to the server (using Fossil's standard URL notation.)
     */
    public String getServerUrl() {
        String serverUrl = (https ? "https" : "http") + "://" + server + ((port == null || "".equals(port)) ? "" : ":" + port) + "/" + serverpath;
        return serverUrl;
    }

    /**
     * @return the local Fossil repository that sits in the workspace.
     */
    public String getLocalRepository() {
        return this.localRepository;
    }

    /**
     * @return true if the plugin is configured to cleanBuild the workspace prior to extracting code.
     */
    public boolean isClean() {
        return cleanBuild;
    }

    /**
     * @return the Descriptor for this Jenkins Plugin.
     */
    @Override
    public FossilDescriptorImpl getDescriptor() {
        return FossilDescriptorImpl.DESCRIPTOR;
    }

    /**
     * @return the username that was configured for remote server access
     */
    public String getUsername() {
        return username;
    }

    /**
     * Set username for Fossil server URL
     * 
     * @param s
     */
    public void setUsername(String s) {
        this.username = s;
    }

    /**
     * @return which named checkin to update.
     */
    public String getBuildTag() {
        return "latest";
    }

    /**
     * @return the password that was configured for remote server access
     */
    public String getPassword() {
        return password;
    }

    /**
     * Set the password for the Fossil repo URL
     * 
     * @param s 
     */
    public void setPassword(String s) {
        this.password = s;
    }

    /**
     * This class represents the Fossil SCM Description implementation
     * 
     * Descriptors are used to allow for extension.  There is only one (static final).
     * 
     */
    @Extension
    public static final class FossilDescriptorImpl extends SCMDescriptor<FossilScm> {

        /**
         * This is a DESCRIPTOR singleton.
         * 
         */
        public static final FossilDescriptorImpl DESCRIPTOR = new FossilDescriptorImpl();
        /**
         * The fields of the descriptor are the global configuration options, just as the Scm class contains
         * the configuration options for a job (@see https://wiki.jenkins-ci.org/display/JENKINS/SCM+plugin+architecture )
         */
        private String fossilExecutable = "fossil";
        private transient String version = "1";

        /**
         * Empty Constructor
         */
        public FossilDescriptorImpl() {
            super(FossilScm.class, null); // was FossilRepositoryBrowser.class);
            load();
        }

        /**
         * @return the display name for this plugin.
         */
        @Override
        public String getDisplayName() {
            return "Fossil";
        }

        /**
         * @return the fossil executable program basename (no path)
         */
        public String getFossilExecutable() {
            if (fossilExecutable == null || "".equals(fossilExecutable)) {
                return "fossil";
            } else {
                return fossilExecutable;
            }
        }

        /**
         * Create a new instance of this plugin from a form request.
         * The form is created automatically from the Jelly files.
         * 
         * @param req
         * @param formData
         * @return
         * @throws hudson.model.Descriptor.FormException 
         */
        @Override
        public SCM newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            FossilScm scm = req.bindJSON(FossilScm.class, formData);

            return scm;
        }

        /**
         * Configure an existing object with new fields.
         * 
         * @param req
         * @param json
         * @return
         * @throws hudson.model.Descriptor.FormException 
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            fossilExecutable = Util.fixEmpty(req.getParameter("fossil.fossilExecutable").trim());

            save();
            return true;
        }

        /**
         * Check to see if the value is an executable program.
         * This function is normally invoked from the Jelly form to validate on the fly (via AJAX).
         * 
         * @param value
         * @return 
         */
        public FormValidation doExecutableCheck(@QueryParameter String value) {
            return FormValidation.validateExecutable(value);
        }

        /**
         * validate a repository name 
         * 
         * @param value is a repository name
         * @return form validation 
         */
        public FormValidation doCheckRepository(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        /**
         * validate a user name 
         * 
         * @param value is a username in a Fossil repository
         * @return form validation
         */
        public FormValidation doCheckUsername(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        /**
         * validate a password (Fossil requires passwords be non-empty.)
         * 
         * @param value is a password for a username in a Fossil repository
         * @return form validation
         */
        public FormValidation doCheckPassword(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }
    }
    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(FossilScm.class.getName());
}
