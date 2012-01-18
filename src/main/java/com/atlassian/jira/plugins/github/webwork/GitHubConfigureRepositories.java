package com.atlassian.jira.plugins.github.webwork;


import com.atlassian.jira.security.xsrf.RequiresXsrfCheck;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.config.properties.PropertiesManager;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.web.action.JiraWebActionSupport;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.radeox.util.logging.SystemOutLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class GitHubConfigureRepositories extends JiraWebActionSupport {

    final PluginSettingsFactory pluginSettingsFactory;
    final Logger logger = LoggerFactory.getLogger(GitHubConfigureRepositories.class);

    JiraWebActionSupport jwas = new JiraWebActionSupport();

    public GitHubConfigureRepositories(PluginSettingsFactory pluginSettingsFactory){
        this.pluginSettingsFactory = pluginSettingsFactory;
    }

    protected void doValidation() {
        //logger.debug("GitHubConfigureRepositories - doValidation()");
        for (Enumeration e =  request.getParameterNames(); e.hasMoreElements() ;) {
            String n = (String)e.nextElement();
            String[] vals = request.getParameterValues(n);
            //validations = validations + "name " + n + ": " + vals[0];
        }

        // GitHub URL Validation
        if (!url.equals("")){
            logger.debug("URL for Evaluation: " + url + " - NA: " + nextAction);
            if (nextAction.equals("AddRepository") || nextAction.equals("DeleteReposiory")){
                // Valid URL and URL starts with github.com domain
                Pattern p = Pattern.compile("^(https|http)://github.com/[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]");
                Matcher m = p.matcher(url);
                if (!m.matches()){
                    validations = "Please supply a valid GitHub repository URL.";
                }
            }
        }else{
            if (nextAction.equals("AddRepository") || nextAction.equals("DeleteReposiory")){
                validations = "Please supply a valid GitHub repository URL.";
            }
        }

    }


    public String doDefault(){

        return "input";
    }

    @RequiresXsrfCheck
    protected String doExecute() throws Exception {
        logger.debug("NextAction: " + nextAction);

        // Remove trailing slashes from URL
        if (url.endsWith("/")){
            url = url.substring(0, url.length() - 1);
        }

        // Set all URLs to HTTPS
        if (url.startsWith("http:")){
            url = url.replaceFirst("http:","https:");
        }

        if (validations.equals("")){
            if (nextAction.equals("AddRepository")){

                if (repoVisibility.equals("private")){
                    logger.debug("Private Add Repository");
                    String clientID = "";
                    clientID = (String)pluginSettingsFactory.createGlobalSettings().get("githubRepositoryClientID");

                    String contextPath = request.getContextPath();
                    if(clientID == null){
                        //logger.debug("No Client ID");
                        validations = "You will need to setup a <a href='" + contextPath + "/secure/admin/ConfigureGlobalSettings!default.jspa'>GitHub OAuth Application</a> before you can add private repositories";
                    }else{
                        if(clientID.equals("")){
                            validations = "You will need to setup a <a href='" + contextPath + "/secure/admin/ConfigureGlobalSettings!default.jspa'>GitHub OAuth Application</a> before you can add private repositories";
                        }else{
                            addRepositoryURL();
                            pluginSettingsFactory.createGlobalSettings().put("githubPendingProjectKey", projectKey);
                            pluginSettingsFactory.createGlobalSettings().put("githubPendingRepositoryURL", url);

                            String redirectURI = "https://github.com/login/oauth/authorize?scope=repo&client_id=" + clientID;
                            redirectURL = redirectURI;

                            return "redirect";
                        }
                    }
                }else{
                    logger.debug("PUBLIC Add Repository");
                    addRepositoryURL();
                    nextAction = "ForceSync";

                }

                postCommitURL = "GitHubPostCommit.jspa?projectKey=" + projectKey;

                logger.debug(postCommitURL);

            }

            if (nextAction.equals("ShowPostCommitURL")){
                postCommitURL = "GitHubPostCommit.jspa?projectKey=" + projectKey;
            }

            if (nextAction.equals("DeleteRepository")){
                deleteRepositoryURL();
            }

            if (nextAction.equals("CurrentSyncStatus")){

                try{
                    currentSyncPage = (String)pluginSettingsFactory.createSettingsForKey(projectKey).get("currentsync" + url + projectKey);
                    nonJIRACommitTotal = (String)pluginSettingsFactory.createSettingsForKey(projectKey).get("NonJIRACommitTotal" + url);
                    JIRACommitTotal = (String)pluginSettingsFactory.createSettingsForKey(projectKey).get("JIRACommitTotal" + url);

                }catch (Exception e){
                    logger.debug("GitHubConfigureRepositories.doExecute().CurrentSyncStatus - Exception reading plugin values.");
                }


                logger.debug("GitHubConfigureRepositories.doExecute().CurrentSyncStatus - currentSyncPage" + currentSyncPage);

                return "syncstatus";
            }

            if (nextAction.equals("SyncRepository")){
                syncRepository();
                return "syncmessage";

            }
        }

        return INPUT;
    }

    private void resetCommitTotals(){
        logger.debug("GitHubConfigureRepositories.resetCommitTotals()");
        try{
            pluginSettingsFactory.createSettingsForKey(projectKey).put("currentsync" + url + projectKey, "0");
            pluginSettingsFactory.createSettingsForKey(projectKey).put("NonJIRACommitTotal" + url, "0");
            pluginSettingsFactory.createSettingsForKey(projectKey).put("JIRACommitTotal" + url, "0");
        }catch (Exception e){
            logger.debug("GitHubConfigureRepositories.resetCommitTotals() - exception caught");
        }
    }

    private void syncRepository(){

        logger.debug("GitHubConfigureRepositories.syncRepository() - Starting Repository Sync");

        GitHubCommits repositoryCommits = new GitHubCommits(pluginSettingsFactory);
        repositoryCommits.repositoryURL = url;
        repositoryCommits.projectKey = projectKey;

        // Reset Commit count
        resetCommitTotals();

        // Starts actual search of commits via GitAPI, "1" is the first
        // page of commits to be returned via the API
        messages = repositoryCommits.syncCommits(1);

    }

    // Manages the entry of multiple repository URLs in a single pluginSetting Key
    private void addRepositoryURL(){
        ArrayList<String> urlArray = new ArrayList<String>();

        // First Time Repository URL is saved
        if ((ArrayList<String>)pluginSettingsFactory.createSettingsForKey(projectKey).get("githubRepositoryURLArray") != null){
            urlArray = (ArrayList<String>)pluginSettingsFactory.createSettingsForKey(projectKey).get("githubRepositoryURLArray");
        }

        Boolean boolExists = false;

        for (int i=0; i < urlArray.size(); i++){
            if (url.toLowerCase().equals(urlArray.get(i).toLowerCase())){
                boolExists = true;
            }
        }

        if (!boolExists){
            urlArray.add(url);
            pluginSettingsFactory.createSettingsForKey(projectKey).put("githubRepositoryURLArray", urlArray);
            resetCommitTotals();
        }

    }

    // Removes a single Repository URL from a given Project
    private void deleteRepositoryURL(){
        ArrayList<String> urlArray = new ArrayList<String>();

        // Remove associated access key (if any) for private repos
        pluginSettingsFactory.createSettingsForKey(projectKey).remove("githubRepositoryAccessToken" + url);

        urlArray = (ArrayList<String>)pluginSettingsFactory.createSettingsForKey(projectKey).get("githubRepositoryURLArray");

        for (int i=0; i < urlArray.size(); i++){
            if (url.equals(urlArray.get(i))){
                urlArray.remove(i);

                GitHubCommits repositoryCommits = new GitHubCommits(pluginSettingsFactory);
                repositoryCommits.repositoryURL = url;
                repositoryCommits.projectKey = projectKey;

                repositoryCommits.removeRepositoryIssueIDs();

            }
        }

        pluginSettingsFactory.createSettingsForKey(projectKey).put("githubRepositoryURLArray", urlArray);

    }

    // JIRA Project Listing
    private ComponentManager cm = ComponentManager.getInstance();
    private List<Project> projects = cm.getProjectManager().getProjectObjects();

    public List getProjects(){
        return projects;
    }

    public String getProjectName(){
        return cm.getProjectManager().getProjectObjByKey(projectKey).getName();
    }

    public String escape(String unescapedHTML){
        return jwas.htmlEncode(unescapedHTML);
    }

    // Stored Repository + JIRA Projects
    public ArrayList<String> getProjectRepositories(String pKey){
        return (ArrayList<String>)pluginSettingsFactory.createSettingsForKey(pKey).get("githubRepositoryURLArray");
    }

    // Mode setting to 'single' indicates that this is administration of a single JIRA project
    // Bulk setting indicates multiple projects
    private String mode = "";
    public void setMode(String value){this.mode = value;}
    public String getMode(){return mode;}

    // GitHub Repository URL
    private String url = "";
    public void setUrl(String value){this.url = value;}
    public String getURL(){return url;}

    // GitHub Post Commit URL for a specific project and repository
    private String postCommitURL = "";
    public void setPostCommitURL(String value){this.postCommitURL = value;}
    public String getPostCommitURL(){return postCommitURL;}

    // GitHub Repository Visibility
    private String repoVisibility = "";
    public void setRepoVisibility(String value){this.repoVisibility = value;}
    public String getRepoVisibility(){return repoVisibility;}

    // Project Key
    private String projectKey = "";
    public void setProjectKey(String value){this.projectKey = value;}
    public String getProjectKey(){return projectKey;}

    // Form Directive
    private String nextAction = "";
    public void setNextAction(String value){this.nextAction = value;}
    public String getNextAction(){return this.nextAction;}

    // Validation Error Messages
    private String validations = "";
    public String getValidations(){return this.validations;}

    // Confirmation Messages
    private String messages = "";
    public String getMessages(){return this.messages;}

    // Redirect URL
    private String redirectURL = "";
    public String getRedirectURL(){return this.redirectURL;}

    // Current page of commits that is being processed
    private String currentSyncPage = "";
    public String getCurrentSyncPage(){return this.currentSyncPage;}


    private String nonJIRACommitTotal = "";
    public String getNonJIRACommitTotal(){return this.nonJIRACommitTotal;}

    // Current page of commits that is being processed
    private String JIRACommitTotal = "";
    public String getJIRACommitTotal(){return this.JIRACommitTotal;}

}
