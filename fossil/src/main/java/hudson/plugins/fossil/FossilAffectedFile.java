package hudson.plugins.fossil;

import hudson.scm.ChangeLogSet;
import hudson.scm.EditType;

/**
 * Represents a file change.
 * 
 * {@link ChangeLogSet.AffectedFile} for Fossil.
 */
public class FossilAffectedFile implements ChangeLogSet.AffectedFile {

    private FossilChangeLogEntry changeLog;
    private EditType editType;
    private String path;
    
    /**
     * Represents a file change in a ChangeLogSet
     * 
     * @param editType one of ADD DELETE EDIT
     * @param path full file path in the repository
     */
    public FossilAffectedFile(EditType editType, String path) {
        this.editType = editType;
        this.path = path;
    }
    /**
     * Set Fossil change log
     * 
     * @param changeLog 
     */
    public void setChangeSet(FossilChangeLogEntry changeLog) {
        this.changeLog = changeLog;
    }
    /**
     * @return change log for this file 
     */
    public FossilChangeLogEntry getChangeLog() {
        return this.changeLog;
    }

    /**
     * @return editType which is one of ADD, DELETE, EDIT
     */
    public EditType getEditType() {
        return this.editType;
    }

    /**
     * @return full file path of file in repository
     */
    public String getPath() {
        return this.path;
    }

}
