
package hudson.plugins.fossil;

import hudson.scm.RepositoryBrowser;
import java.io.IOException;
import java.net.URL;

/**
 * This repository browser needs to be set during configuration.
 * {@link FossilRepositoryBrowser} is persisted with {@link FossilScm}
 * @author perrella
 */

// TODO: untested repo browser.

public class FossilRepositoryBrowser extends RepositoryBrowser<FossilChangeLogEntry> {
    private String serverUrl;
    
    FossilRepositoryBrowser(String serverUrl)
    {
        this.serverUrl = serverUrl;
    }
    /**
     * get the link to a changeset.
     * 
     * @param e
     * @return URL
     * @throws IOException 
     */
    public URL getChangeSetLink(FossilChangeLogEntry e) throws IOException
    {
        return new URL(serverUrl + "/info/" + e.getCommitId());
    }
}
