/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.fossil;

import hudson.Extension;
import hudson.MarkupText;
import hudson.model.AbstractBuild;
import hudson.scm.ChangeLogAnnotator;
import hudson.scm.ChangeLogSet.Entry;
import hudson.scm.SCM;


/**
 *
 * @author perrella
 */
@Extension
public class FossilChangeLogAnnotator extends ChangeLogAnnotator {
    /**
     * Annotate the changelog text to point to changelog overview.
     * 
     * @param build
     * @param change
     * @param text 
     */
    public void annotate(AbstractBuild<?,?> build, Entry change, MarkupText text ){
       SCM scm  = build.getProject().getScm(); 
       
       if (scm instanceof FossilScm) {
           FossilScm fscm = (FossilScm) scm;
           
           String url = fscm.getServerUrl();
           
           if (!url.endsWith("/")){
               url = url + "/";
           }
           url = url + "info/" + change.getCommitId();
           text.addHyperlink(0, text.length(), url);
       }
       
    }
    
    
}
