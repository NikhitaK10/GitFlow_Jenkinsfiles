node{
    jiraUrl         = 'http://192.168.96.146:9090'
    gitHub          = 'https://github.com'
    gitRepoOwner    = 'CPWRGIT'
    gitCredentials  = 'CPWRGIT_GitHub_New' 
    gitCredentialsBasic = 'CPWRGIT_Token_Basic'     
    gitSourceBranch = 'development'

    def gitBranchName
    def gitReleaseName
    def gitTagName
    def gitReleaseDescription
    def gitAuthToken

    stage("Get JIRA Info") {
        def response = httpRequest(
            acceptType: 'APPLICATION_JSON', 
            authentication: 'MKSJIEA', 
            consoleLogResponseBody: true, 
            responseHandle: 'NONE', 
            url: jiraUrl + '/rest/api/2/version/' + jiraVersionId, 
            wrapAsMultipart: false
        )

        respContent = readJSON(text: response.content)
        
        gitBranchName           = 'release/' + respContent.name
        gitReleaseName          = respContent.name
        gitTagName              = respContent.name
        gitReleaseDescription   = respContent.description 
    }

    stage("Create Release") {

        def requestBody = '''
            {
                "tag_name":"''' + gitTagName + '''",
                "target_commitish":"''' + gitSourceBranch + '''",
                "name":"''' + gitReleaseName + '''",
                "body":"''' + gitReleaseDescription + '''",
                "draft":false,
                "prerelease":false,
                "generate_release_notes":false
            }
        '''

        withCredentials(
            [
                string(
                    credentialsId: gitCredentialsBasic, 
                    variable: 'tmpToken'
                )
            ]
        ) {
            gitAuthToken = tmpToken
        }

        def response = httpRequest(
            consoleLogResponseBody:     true, 
            customHeaders:              [
                [maskValue: false,  name: 'content-type',   value: 'application/json'], 
                [maskValue: true,   name: 'authorization',  value: gitAuthToken], 
                [maskValue: false,  name: 'accept',         value: 'application/vnd.github+json'], 
                [maskValue: false,  name: 'user-agent',     value: 'cpwrgit']
            ], 
            httpMode:                   'POST', 
            ignoreSslErrors:            true, 
            requestBody:                requestBody, 
            url:                        'https://api.github.com/repos/CPWRGIT/' + gitRepo + '/releases', 
            validResponseCodes:         '201', 
            wrapAsMultipart:            false
        )
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
    
    stage("Create Release Branch") {
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
    
            bat(returnStdout: true, script: 'git branch ' + gitBranchName)
            bat(returnStdout: true, script: "git push https://" + gitUserId + ":" + gitToken + "@github.com/" + gitRepoOwner + "/" + gitRepo + ".git refs/heads/" + gitBranchName + ":refs/heads/" + gitBranchName + " -f")
                                            
        }
    }
}