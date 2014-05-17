package hudson.plugins.fossil;

/**
 * The Fossil server produces a simple RSS feed.
 * Just need to parse it (see examples in TestSuite.)
 * 
 * @see FossilScmTestSuite
 * @author perrella
 */
public class FossilRSSParser {

    private FossilRSSParser() {
    }

    /**
     * Parse the RSS string that represents the state of the repository.
     * 
     * TODO: use actual XML parser, not this temporary string processing.
     * 
     * @param rss
     * @return FossilRevisionState that represents the state of the repository
     */
    public static FossilRevisionState parse(String rss) {
        String checkin = "";
        int start = rss.indexOf("<guid>");

        if (start >= 0) {
            int end = rss.indexOf("</guid>");

            String url = rss.substring(start, end).trim();

            checkin = url.substring(url.lastIndexOf('/') + 1);
        } else {
            checkin = "";
        }
        FossilRevisionState rev = new FossilRevisionState(checkin);

        return rev;

    }
}
