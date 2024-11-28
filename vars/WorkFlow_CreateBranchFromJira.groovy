node{
    jiraUrl         = 'http://192.168.96.146:9090'
    gitHub          = 'https://github.com'
    gitRepoOwner    = 'CPWRGIT'
    gitCredentials  = 'CPWRGIT_GitHub_New' 

    def gitRepo
    def gitBranchName

    stage("Get JIRA Info") {
        def response = httpRequest(
            acceptType: 'APPLICATION_JSON', 
            authentication: 'MKSJIEA', 
            consoleLogResponseBody: true, 
            responseHandle: 'NONE', 
            url: jiraUrl + '/rest/api/2/issue/' + jiraIssueId, 
            wrapAsMultipart: false
        )
        
        def respContent = readJSON(text: response.content)
        
        gitRepo         = respContent.fields.customfield_10202.value
        gitBranchName   = respContent.fields.customfield_10203
    }

    stage("Clone Repo") {

        dir("./"){
            deleteDir()
        }

        checkout(
            changelog:  false, 
            poll:       false, 
            scm:        [
                $class:             'GitSCM', 
                branches:           [[name: 'development']], 
                extensions:         [], 
                userRemoteConfigs:  [[
                    credentialsId:  gitCredentials, 
                    url:            gitHub + '/' + gitRepoOwner + '/' + gitRepo
                ]]
            ]
        )
    }
    
    stage("Create new Branch") {
        def gitToken
        def gitUserId
        
        withCredentials(
            [
                usernamePassword(
                    credentialsId:      gitCredentials, 
                    passwordVariable:   'gitPwTmp', 
                    usernameVariable:   'gitUserTmp'
                )
            ]
        )
        {
            gitToken    = gitPwTmp
            gitUserId   = gitUserTmp
        }

        echo "Creating branch: " + gitBranchName
        
        dir("./")
        {

            bat(returnStdout: true, script: 'git config user.email "cpwrgit@compuware.com"')
            bat(returnStdout: true, script: 'git config user.name "CPWRGIT"')
    
            bat(returnStdout: true, script: 'git branch feature/' + gitBranchName)
            bat(returnStdout: true, script: "git push https://" + gitUserId + ":" + gitToken + "@github.com/" + gitRepoOwner + "/" + gitRepo + ".git refs/heads/feature/" + gitBranchName + ":refs/heads/feature/" + gitBranchName + " -f")
                                            
        }
    }
}