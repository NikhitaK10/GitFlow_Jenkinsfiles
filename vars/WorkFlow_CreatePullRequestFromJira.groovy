node{
    jiraUrl             = 'http://192.168.96.146:9090'
    gitHub              = 'https://github.com'
    gitRepoOwner        = 'CPWRGIT'
    gitCredentials      = 'CPWRGIT_GitHub_New'
    gitCredentialsBasic = 'CPWRGIT_Token_Basic' 
    gitSourceBranch     = 'development'

    def gitRepo
    def gitBranchName
    def gitAuthToken

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

    stage("Create Pull Request") {
        def requestBody = '''
            {
                "title": 	"Merge feature/''' + gitBranchName + ''' into ''' + gitSourceBranch + '''",
                "head":		"feature/''' + gitBranchName + '''",
                "base":		"''' + gitSourceBranch + '''",
                "body":		"Review feature/''' + gitBranchName + '''."
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
            url:                        'https://api.github.com/repos/CPWRGIT/' + gitRepo + '/pulls', 
            validResponseCodes:         '201', 
            wrapAsMultipart:            false
        )
    }
}