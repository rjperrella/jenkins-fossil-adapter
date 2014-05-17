package hudson.plugins.fossil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.List;
//import junit.framework.Assert;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.SAXException;
import hudson.scm.EditType;
import hudson.scm.SCMDescriptor;

/**
 * This is the test suite for the Fossil SCM Jenkins Plug-in.
 * 
 * @author perrella
 */
public class FossilScmTestSuite {

    public FossilScmTestSuite() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }
    public FossilScm makeEmptyScm()
    {
        return new FossilScm(false,"example", "8080","", "norepo", false, "", "");
    }

    @Test
    public void itShouldParseEmptyFossilInfo() {
        FossilScm scm = makeEmptyScm();

        String testdata = "";

        scm.fossil_info_parser(testdata);
    }

   @Test
   public void itShouldCheckAuthenticatedUrlsWithoutAuth() {
                FossilScm scm = makeEmptyScm();
        
        
        assertEquals("http://example.com", scm.getAuthenticatedServerUrl());
   }
   
   @Test
   public void itShouldCheckAuthenticatedUrlsWithAuth() {
        FossilScm scm = makeEmptyScm();
        
        assertEquals(scm.getAuthenticatedServerUrl(),"http://user:password@example.com");
   }

   @Test
   public void itShouldCheckAuthenticatedUrlsWithAuthAndHttps() {
        FossilScm scm = makeEmptyScm();
        
        assertEquals(scm.getAuthenticatedServerUrl(),"https://user:password@example.com");
   }
   
    @Test
    public void itShouldParseGoodFossilInfo() {
        FossilScm scm = makeEmptyScm();

        String testdata = ""
                + "project-name: Blabla\n"
                + "repository:   C:/src/blabla/blabla\n"
                + "local-root:   C:/src/myroot/\n"
                + "user-home:    C:/Users/username/AppData/Local\n"
                + "project-code: 640e13fdd114a5894d9fa42576432cf51379b6bf\n"
                + "checkout:     886b406bcf4276879cc9d1c9869772991aeaf21e 2012-06-02 22:42:54 UTC\n"
                + "parent:       2dd1b06dcc27781581f2bd2cbe269458ccf0b4ef 2012-06-02 22:18:35 UTC\n"
                + "tags:         trunk\n"
                + "comment:      made a comment  here. \n"
                + "              more stuff.(user: user2)\n"
                + "";

        Map<String, String> tab = scm.fossil_info_parser(testdata);

        assert tab.containsKey("project-name");
        assertEquals("Blabla", tab.get("project-name"));
        assert tab.containsKey("repository");
        assertEquals("C:/src/blabla/blabla", tab.get("repository"));
        assert tab.containsKey("local-root");
        assertEquals("C:/src/myroot/", tab.get("local-root"));
        assert tab.containsKey("user-home");
        assertEquals("C:/Users/username/AppData/Local", tab.get("user-home"));
        assert tab.containsKey("project-code");
        assertEquals("640e13fdd114a5894d9fa42576432cf51379b6bf", tab.get("project-code"));
        assert tab.containsKey("checkout");
        assertEquals("886b406bcf4276879cc9d1c9869772991aeaf21e", tab.get("checkout"));
        assert tab.containsKey("checkout-date");
        assertEquals("2012-06-02 22:42:54 UTC", tab.get("checkout-date"));
        assert tab.containsKey("parent");
        assertEquals("2dd1b06dcc27781581f2bd2cbe269458ccf0b4ef", tab.get("parent"));
        assert tab.containsKey("parent-date");
        assertEquals("2012-06-02 22:18:35 UTC", tab.get("parent-date"));
        assert tab.containsKey("tags");
        assertEquals("trunk", tab.get("tags"));
        assert tab.containsKey("comment");
        assertEquals("made a comment  here.\nmore stuff.(user: user2)\n", tab.get("comment"));
    }

    @Test
    public void itShouldParseLogs() throws IOException, SAXException {

        FossilScm scm = makeEmptyScm();
        String test = ""
                + "=== 2012-06-10 ===\n"
                + "20:34:57 [31eb532808] *CURRENT* first jenkins fossil checkin. probably\n"
                + "         included too much stuff in this first one. (user: perrella tags:\n"
                + "         trunk)\n"
                + "   ADDED fossil/LICENSE.txt\n"
                + "   EDITED fossil/pom.xml\n"
                + "   DELETED fossil/pom2.xml\n"
                + "20:33:39 [fce96208b5] initial empty check-in (user: perrella tags: trunk)\n"
                + "";
        BufferedReader br = new BufferedReader(new StringReader(test));

        FossilChangeLogParser parser = new FossilChangeLogParser();

        Object obj = parser.buffered_parse(br);
        List<FossilChangeLogEntry> chgset = (List<FossilChangeLogEntry>) obj;

        assertEquals(2, chgset.size());

        // check the 1st change.

        Object obj2 = chgset.get(0);

        assert obj2 instanceof FossilChangeLogEntry;

        FossilChangeLogEntry chg = (FossilChangeLogEntry) obj2;

        assertEquals(chg.getDate(), "2012-06-10");
        assertEquals("hex commit id", "31eb532808", chg.getCommitId());

        assertEquals("*CURRENT* first jenkins fossil checkin. probably" + " "
                + "included too much stuff in this first one. (user: perrella tags:" + " "
                + "trunk)", chg.getMsg());

        List<FossilAffectedFile> afl = (List<FossilAffectedFile>) chg.getAffectedFiles();

        assertEquals(3, afl.size());

        FossilAffectedFile af = afl.get(0);

        assertEquals(af.getPath(), "fossil/LICENSE.txt");
        assertEquals(EditType.ADD, af.getEditType());

        af = afl.get(1);

        assertEquals(af.getPath(), "fossil/pom.xml");
        assertEquals(EditType.EDIT, af.getEditType());

        af = afl.get(2);

        assertEquals(af.getPath(), "fossil/pom2.xml");
        assertEquals(EditType.DELETE, af.getEditType());

        // check the 2nd change.

        chg = chgset.get(1);
        assertEquals(chg.getMsg(), "initial empty check-in (user: perrella tags: trunk)");

        assertEquals("fce96208b5", chg.getCommitId());
        afl = (List<FossilAffectedFile>) chg.getAffectedFiles();

        assertEquals("No files in list", 0, afl.size());
        assertEquals(0, afl.size());
        
    }

    @Test
    public void itShouldParseRSS() {
        String rss = "<?xml version=\"1.0\"?>"
                + "<rss xmlns:dc=\"http://purl.org/dc/elements/1.1/\" version=\"2.0\">"
                + "  <channel>"
                + "    <title>fossil-jenkins-plugin</title>"
                + "    <link>http://127.0.0.1:8080</link>"
                + "    <description>fossil-jenkins-plugin</description>"
                + "    <pubDate>Sun, 1 Jul 2012 19:53:20 GMT</pubDate>"
                + "    <generator>Fossil version [5dd5d39e7c] 2012-03-19 12:45:47</generator>"
                + "    <item>"
                + "      <title>(no comment)</title>"
                + "      <link>http://127.0.0.1:8080/info/bef42e8c2fcc51254daf5fe87b2c562c72abc103</link>"
                + "      <description>(no comment)</description>"
                + "      <pubDate>Fri, 29 Jun 2012 11:59:07 GMT</pubDate>"
                + "      <dc:creator>perrella</dc:creator>"
                + "      <guid>http://127.0.0.1:8080/info/bef42e8c2fcc51254daf5fe87b2c562c72abc103</guid>"
                + "    </item>"
                + "  </channel>"
                + "</rss>";

        FossilRevisionState rev = FossilRSSParser.parse(rss);

        assertEquals("bef42e8c2fcc51254daf5fe87b2c562c72abc103", rev.getRevId());
    }
    @Test
    public void itShouldParseRSSWithNoCommit() {
        String rss = "<?xml version=\"1.0\"?>"
                + "<rss xmlns:dc=\"http://purl.org/dc/elements/1.1/\" version=\"2.0\">"
                + "  <channel>"
                + "    <title>fossil-jenkins-plugin</title>"
                + "    <link>http://127.0.0.1:8080</link>"
                + "    <description>fossil-jenkins-plugin</description>"
                + "    <pubDate>Sun, 1 Jul 2012 19:53:20 GMT</pubDate>"
                + "    <generator>Fossil version [5dd5d39e7c] 2012-03-19 12:45:47</generator>"
                + "  </channel>"
                + "</rss>";

        FossilRevisionState rev = FossilRSSParser.parse(rss);

        assertEquals("", rev.getRevId());
    }
   @Test
    public void itShouldParseEmptyRSS() {
        String rss = "";

        FossilRevisionState rev = FossilRSSParser.parse(rss);

        assertEquals("", rev.getRevId());
    }
   
}
