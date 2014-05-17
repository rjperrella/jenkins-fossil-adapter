package hudson.plugins.fossil;

import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogParser;
import hudson.scm.ChangeLogSet;
import hudson.scm.EditType;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;
import org.xml.sax.SAXException;

/**
 * This is a parser for Fossil change logs.
 * 
 * A Fossil Change Log looks like this:

 * @author perrella
 */

/*
012345678901234567890123 (ruler line)
=== 2012-05-13 ===
08:07:02 [68ea0f8d97] storm integrated into main page. properly displays in
hosted mode. (user: perrella tags: trunk, STABLE)
EDITED pom.xml
EDITED src/main/java/com/stuff/storm/client/Main.java
EDITED src/main/resources/com/stuff/storm/Main.gwt.xml
ADDED src/main/webapp/WEB-INF/web.xml
=== 2012-05-12 ===
14:45:43 [d4614a2a34] Fixed bug 37e77677ea4d035ab317f314a397d1d89d52376c added
creator and updater to all persistent objects. (user: perrella tags:
trunk)
EDITED pom.xml
EDITED src/main/java/com/stuff/account/Account.java
EDITED src/main/java/com/stuff/stuff/Stuff.java
 */
class FossilChangeLogParser extends ChangeLogParser {

    /**
     * The parse method is used to parse a Fossil Change Log.
     * 
     * It is implemented as a state machine which parsers the change log.
     * 
     * @param build
     * @param changelogFile is a local File that contains the change log.
     * @return
     * @throws IOException
     * @throws SAXException 
     */
    public ChangeLogSet<? extends ChangeLogSet.Entry> parse(AbstractBuild build,
            File changelogFile)
            throws IOException,
            SAXException {

        BufferedReader inf = new BufferedReader(new FileReader(changelogFile));
        
        List<FossilChangeLogEntry> chg = buffered_parse(inf);
        inf.close();
        
        return new FossilChangeLogSet(build, chg);
    }
    
    /**
     * parse a single change log from a buffered reader.
     * 
     * parses a Fossil Timeline log.
     * 
     * This was separated out in order to facilitate unit testing.
     * 
     * @param in a buffered reader
     * @return a list of FossilChangeLogEntry (never null)
     * @throws IOException
     * @throws SAXException 
     */
    public List<FossilChangeLogEntry> buffered_parse(BufferedReader in)
            throws IOException,
            SAXException {

        List<FossilChangeLogEntry> entries = new LinkedList<FossilChangeLogEntry>();
        String s = "";
        String dt = "";
        String ti = "";
        String checkin = "";
        String msg = "";
        String path = "";
        FossilAffectedFile af = null;
        final String blanks8 = "        ";

        FossilChangeLogEntry entry = null;
        
        int linecount = 0;

        int state = -1;
        while ((s = in.readLine()) != null) {
            linecount ++;
            
            if (s.startsWith("===")) {
                if (entry != null) {
                    entries.add(entry);
                    entry = null;
                }
                state = 0;
            }

            switch (state) {
                case -1: // skip until we get === line.
                    break;
                case 0: // date line    === YYYY/MM/DD === "
                    if (s.length() >= 18 ) {
                        entry = new FossilChangeLogEntry();
                        dt = s.substring(4, 14);
                        entry.setDate(dt);
                        entry.setAuthor("");
                        entry.setAuthorEmail("");
                        entry.setMsg("");
                        entry.setRevid("");
                        entry.setTags(new ArrayList<String>());
                        entry.setParent(null);
                        entry.setMerge(false);

                        state = 1;
                        }
                    break;
                case 1: // CHECKIN line.
                    if ((s.length() >= 1) && Character.isDigit(s.charAt(0))) {
                        ti = s.substring(0, 8);
                        checkin = s.substring(10, 20);  // pull it out from between [ and ]
                        msg = s.substring(22);

                        entry.setMsg(msg);
                        entry.setRevid(checkin);
                        state = 2;
                    } else {
                        // bad change line.
                    }
                    break;
                case 2:  // either continuation line or file change or date + checkin
                    if (s.startsWith(blanks8)) {
                        msg = msg + " " + s.substring(9);
                        entry.setMsg(msg);
                    } else {
                        if (s.matches("^\\d\\d\\:\\d\\d\\:\\d\\d .*")) {
                            if(entry != null) {
                                entries.add(entry);
                                entry = new FossilChangeLogEntry();
                                entry.setDate(dt);      // use previous date.
                                entry.setAuthor("");
                                entry.setAuthorEmail("");
                                entry.setMsg("");
                                entry.setRevid("");
                                entry.setTags(new ArrayList<String>());
                                entry.setParent(null);
                                entry.setMerge(false);
                            }
                            
                            if (s.length() >= 1) {
                                ti = s.substring(0, 8);
                                checkin = s.substring(10, 20);  // pull it out from between [ and ]
                                msg = s.substring(22);

                                entry.setMsg(msg);
                                entry.setRevid(checkin);
                                state = 2; 
                                break;
                            }
                        }
                            
                        if (s.startsWith("   ADDED ")) {
                            af = new FossilAffectedFile(EditType.ADD, s.substring(9));
                            entry.addAffectedFile(af);
                        } else if (s.startsWith("   DELETED ")) {
                            af = new FossilAffectedFile(EditType.DELETE, s.substring(11));
                            entry.addAffectedFile(af);
                        } else if (s.startsWith("   EDITED ")) {
                            af = new FossilAffectedFile(EditType.EDIT, s.substring(10));
                            entry.addAffectedFile(af);
                        } else {
                            af = null;
                            state = 0;
                        }
                    }
                    break;
                default:
                    Logger logger = Logger.getLogger(FossilChangeLogParser.class.getName());
                    logger.warning("Unknown parser state: " + state);
                    throw new IOException("unknown parser state in FossilChangeLogParser.");
                    
            }
        }
        // pick-up the last checkin since you'll hit EOF before adding it.
        if (entry != null) {
            entries.add(entry);
            entry = null;
        }

        return entries;
    }
}
