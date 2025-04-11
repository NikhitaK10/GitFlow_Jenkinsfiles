#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*
import groovy.json.JsonSlurper

def execParms
def configFile

def call(Map parms) {

    parms.demoEnvironment   = parms.demoEnvironment.toLowerCase()
    configFile              = './config/gitflow.yml'    
    def settings            = [:]

    node {

        settings        = initializeSettings(configFile, parms)

        cloneRepo(settings)

        settings        = addIspwConfigFileContent(settings)

        def branchType = BRANCH_NAME.split('/')[0].toUpperCase()

        switch(branchType) {
            case "FEATURE":
                runFeature(settings)
                break
            case "RELEASE":
                runRelease(settings)
                break
            case "BUGFIX":
                runBugFix(settings)
                break
            default:
                runOther(settings)
        }
    }
}

// For a feature branch:
//      For the first build (BUILD_NUMBER == "1"),
//          - create the Sandbox Assignment
//          - Only run the SonarQube scan
//      For any subsequent build run the full pipeline
def runFeature(settings) {

    echo "[Info] - Running build for Feature branch ${BRANCH_NAME}."
    if(BUILD_NUMBER == "1") {

        echo "[Info] - First Build will create the Sandbox Assignment."
        def sandboxId = createSandbox(settings)
        
        loadDummy(settings, sandboxId)
        echo "[Info] - Sandbox Assignment ${sandboxId} has been created."

    } else {

        def assignmentId

        loadMainframeCode(settings)

        assignmentId = getAssignmentId(settings.ispw.automaticBuildFile)

        // Assignment ID == NULL means that no automtic build params file was found. This is the case when no mainframe components were changed.
        //  Only if mainframe components were changed, mainframe builds or tests need to be run, and code coverage results need to be retrieved
        if (assignmentId != null) {

            buildMainframeCode(settings.hci.connectionId, settings.ces.credentialsId, settings.ispw.runtimeConfig)

            runUnitTests(settings)

            runIntegrationTests(settings)

            junit(
                allowEmptyResults:  true, 
                keepLongStdio:      true, 
                testResults:        settings.ttt.results.jUnit.folder + '/*.xml'
            )

            getCodeCoverage(settings)
        }
    }
    
    runSonarScan(settings)            
}

// For a release branch:
//      For the first build (BUILD_NUMBER == "1"),
//          - load code to mainframe
//          - build the code
//          - start Release
//      For any subsequent build (bugfixes merged into release branch), only
//          - load code to mainframe
//          - build the code
def runRelease(settings) {

    def releaseAssignmentId

    settings = extendSettings(settings)

    deactivateSandboxFlag(settings)

    loadMainframeCode(settings.fromCommit, settings.toCommit, settings)

    releaseAssignmentId = getAssignmentId(settings.ispw.automaticBuildFile)

    // Assignment ID == NULL means that no automtic build params file was found. This is the case when no mainframe components were changed.
    //  Only if mainframe components were changed, a mainframe build needs to be run, and the release will be triggered
    if (releaseAssignmentId != null) {

        buildMainframeCode(settings.hci.connectionId, settings.ces.credentialsId, settings.ispw.runtimeConfig)

    }

    if(BUILD_NUMBER == "1"){

        startReleaseProcess(releaseAssignmentId, settings)
    }
}


def runBugFix(settings){
    return
}

// For any other branch (development or main)
//      - Ony run SonarQube scan
def runOther(settings) {

    runSonarScan(settings)
}

def initializeSettings(configFile, parms) {

    def settings = [:]

    stage("Initialization") {

        cleanWs()

        def tmpSettings             = readYaml(text: libraryResource(configFile))


        settings                    = tmpSettings.executionEnvironments[parms.demoEnvironment]
        settings                    = addFolderNames(settings)
        settings                    = addCoCoParms(settings)
 
        if(!(parms.featureLoadLib == null)) {
            settings.ttt.featureLoadLib = parms.featureLoadLib    
        }

        settings.demoEnvironment    = parms.demoEnvironment
        settings.hci.credentialsId  = parms.hostCredentialsId
        settings.ces.credentialsId  = parms.cesCredentialsId
        settings.git                = [:]
        settings.git.repoUrl        = parms.gitRepoUrl
        settings.git.credentialsId  = parms.gitCredentialsId
        settings.coco.repo          = parms.ccRepo
    }

    return settings
}

def addFolderNames(settings) {

    settings.ispw.configFile        = settings.ispw.configFile.folder           + '/' + settings.ispw.configFile.name
    settings.ttt.rootFolder         = settings.ispw.mfProject.rootFolder        + '/' + settings.ttt.folders.root
    settings.ttt.vtFolder           = settings.ttt.rootFolder                   + '/' + settings.ttt.folders.virtualizedTests
    settings.ttt.nvtFolder          = settings.ttt.rootFolder                   + '/' + settings.ttt.folders.nonVirtualizedTests
    settings.coco.sources           = settings.ispw.mfProject.rootFolder        +       settings.ispw.mfProject.sourcesFolder
    settings.sonar.cobolFolder      = settings.ispw.mfProject.rootFolder        +       settings.ispw.mfProject.sourcesFolder
    settings.sonar.copybookFolder   = settings.ispw.mfProject.rootFolder        +       settings.ispw.mfProject.sourcesFolder
    settings.sonar.resultsFolder    = settings.ttt.results.sonar.folder 
    settings.sonar.resultsFileVt    = settings.ttt.folders.virtualizedTests     + '.' + settings.ttt.results.sonar.fileNameBase
    settings.sonar.resultsFileNvt   = settings.ttt.folders.nonVirtualizedTests  + '.' + settings.ttt.results.sonar.fileNameBase
    settings.sonar.resultsFileList  = []        
    settings.sonar.codeCoverageFile = settings.coco.results.sonar.folder        + '/' + settings.coco.results.sonar.file
    settings.jUnit                  = [:]
    settings.jUnit.resultsFile      = settings.ttt.results.jUnit.folder         + '/' + settings.ttt.results.jUnit.file

    return settings
}

def addIspwConfigFileContent(settings)  {

    def tmpText                 = readFile(file: settings.ispw.configFile)

    // remove the first line (i.e. use the the substring following the first carriage return '\n')
    tmpText                         = tmpText.substring(tmpText.indexOf('\n') + 1)
    def ispwConfig                  = readYaml(text: tmpText)

    settings.ispwConfig         = ispwConfig
    settings.ispw.runtimeConfig = ispwConfig.ispwApplication.runtimeConfig
    settings.ispw.stream        = ispwConfig.ispwApplication.stream
    settings.ispw.application   = ispwConfig.ispwApplication.application
    settings.ispw.appPrefix     = ispwConfig.ispwApplication.assignmentPrefix
    settings.ispw.appQualifier  = settings.ispw.libraryQualifier + '.' + settings.ispw.application

    settings.sonar.projectName  = settings.ispw.stream + "_" + settings.ispw.application
    return settings
}

def addCoCoParms(settings) {

    def ccSystemId
    def CC_SYSTEM_ID_MAX_LEN    = 15
    def CC_TEST_ID_MAX_LEN      = 15

    if(BRANCH_NAME.length() > CC_SYSTEM_ID_MAX_LEN) {
        ccSystemId  = BRANCH_NAME.substring(BRANCH_NAME.length() - CC_SYSTEM_ID_MAX_LEN)
    }
    else {
        ccSystemId  = BRANCH_NAME
    }
    
    settings.coco.systemId  = ccSystemId
    settings.coco.testId    = ccTestId    = BUILD_NUMBER

    return settings
}

def extendSettings(settings) {

    def hostCreds           = extractCredentials(settings.hci.credentialsId) 
    settings.hci.user       = hostCreds[0]
    settings.hci.password   = hostCreds[1]

    def gitCreds            = extractCredentials(settings.git.credentialsId)
    settings.git.user       = gitCreds[0]
    settings.git.password   = gitCreds[1]

    def commitInfo          = determineCommitInfo()
    settings.fromCommit     = commitInfo['fromCommit']
    settings.toCommit       = commitInfo['toCommit']
    settings.currentTag     = commitInfo['currentTag']

    return settings
}

def deactivateSandboxFlag(settings) {

    settings.ispwConfig.ispwApplication.sandbox = 'N'
    echo "New ispwconfig.yml"
    echo settings.ispwConfig.toString()

    writeYaml(
        file:       settings.ispw.configFile,
        data:       settings.ispwConfig,
        overwrite:  true
    )
    
    return
}

def cloneRepo(settings) {

    stage ('Checkout') {

        if(BRANCH_NAME.contains("release")) {

            checkout(
                changelog:  false, 
                poll:       false, 
                scm:        [
                    $class:             'GitSCM', 
                    branches:           [[name: BRANCH_NAME]], 
                    extensions:         [], 
                    userRemoteConfigs:  [[
                        credentialsId:  settings.git.credentialsId, 
                        url:            settings.git.repoUrl
                    ]]
                ]
            )
        }
        else
        {
            
            checkout scm
        }
    }
}

def extractCredentials(credentialsId) {

    def credentialsInfo = []

    withCredentials(
        [
            usernamePassword(
                credentialsId:      credentialsId, 
                passwordVariable:   'tmpPw', 
                usernameVariable:   'tmpUser'
            )
        ]
    )
    {
        credentialsInfo[0]  = tmpUser
        credentialsInfo[1]  = tmpPw
    }

    return credentialsInfo
}

def extractToken(credentialsId) {

    def token

    withCredentials(
        [
            string(
                credentialsId:  credentialsId, 
                variable:       'tmpToken'
            )
        ]
    )
    {
        token = tmpToken
    } 
    return token
}

def determineCommitInfo() {

    def commitInfo  = [:]

    def currentTag  = bat(returnStdout: true, script: 'git describe --tags').split("\n")[2].trim()
    // echo "Determined Current Tag: " + currentTag
    commitInfo['currentTag'] = currentTag

    def previousTag = bat(returnStdout: true, script: 'git describe --abbrev=0 --tags ' + currentTag + '~').split("\n")[2].trim()
    // echo "Determined Previous Tag: " + previousTag
    commitInfo['previousTag'] = previousTag

    def fromCommit  = bat(returnStdout: true, script: 'git rev-list -1 ' + previousTag).split("\n")[2].trim()
    // echo "Determined From Commit: " + fromCommit
    commitInfo['fromCommit'] = fromCommit

    def toCommit = bat(returnStdout: true, script: 'git rev-parse --verify HEAD')split("\n")[2].trim()
    // echo "Determined To Commit: " + toCommit
    commitInfo['toCommit'] = toCommit

    return commitInfo
}

def createSandbox(settings) {

    def assignmentDescription   = "Push to ${BRANCH_NAME}".toUpperCase()
    def cesToken                = extractToken(settings.ces.credentialsId)
    def requestBody             = '''{
            "stream":               "''' + settings.ispw.stream         + '''",
            "subAppl":              "''' + settings.ispw.application    + '''",
            "application":          "''' + settings.ispw.application    + '''",
            "assignmentPrefix":     "''' + settings.ispw.appPrefix      + '''",
            "defaultPath":          "UNIT",
            "description":          "''' + assignmentDescription            + '''",
            "owner":                "''' + settings.hci.user                + '''",
            "sandboxJoinAtLevel":   "RLSE"
        }'''
  
    def httpResponse

    try {
        
        httpResponse = httpRequest(
            consoleLogResponseBody:     true, 
            customHeaders:              [
                [maskValue: false,  name: 'content-type',   value: 'application/json'], 
                [maskValue: true,   name: 'authorization',  value: cesToken], 
            ], 
            httpMode:                   'POST', 
            ignoreSslErrors:            true, 
            requestBody:                requestBody, 
            url:                        settings.ces.url + '/ispw/' + settings.ispw.runtimeConfig + '/assignments', 
            validResponseCodes:         '201', 
            wrapAsMultipart:            false
        )
    }
    catch(exception) {
        
        error "[Error] - Unexpected http response code. " + exception.toString() + ". See previous log messages to determine cause."
    }

    def jsonSlurper     = new JsonSlurper()
    def httpResp        = jsonSlurper.parseText(httpResponse.getContent())
    httpResponse        = null
    jsonSlurper         = null

    return httpResp.assignmentId
}

def loadDummy(settings, sandboxId) {

    def cesToken        = extractToken(settings.ces.credentialsId)
    requestBody         = '''{
            "stream":               "''' + settings.ispw.stream         + '''",
            "subAppl":              "''' + settings.ispw.application    + '''",
            "application":          "''' + settings.ispw.application    + '''",
            "moduleName":           "DUMMY",
            "moduleType":           "COB",
            "currentLevel":         "UNIT",
            "startingLevel":        "UNIT"
        }'''
        
    httpRequest(
        consoleLogResponseBody:     true, 
        customHeaders:              [
            [maskValue: false,  name: 'content-type',   value: 'application/json'], 
            [maskValue: true,   name: 'authorization',  value: cesToken], 
        ], 
        httpMode:                   'POST', 
        ignoreSslErrors:            true, 
        requestBody:                requestBody, 
        url:                        settings.ces.url + '/ispw/' + settings.ispw.runtimeConfig + '/assignments/' + sandboxId + '/tasks', 
        validResponseCodes:         '201', 
        wrapAsMultipart:            false
    )
}

def loadMainframeCode(Map settings) {

    echo "[Info] - Loading code from feature branch " + BRANCH_NAME + "."

    stage("Mainframe Load") {    

        def assignmentDescription = "Push to ${BRANCH_NAME}".toUpperCase()

        gitToIspwIntegration( 
            connectionId:       settings.hci.connectionId,
            credentialsId:      settings.hci.credentialsId,
            runtimeConfig:      settings.ispw.runtimeConfig,
            stream:             settings.ispw.stream,
            app:                settings.ispw.application, 
            
            branchMapping:      'feature/** => FEAT,custom,' + assignmentDescription,
            ispwConfigPath:     settings.ispw.configFile,
            gitCredentialsId:   settings.git.credentialsId,
            gitRepoUrl:         settings.git.repoUrl
        )
    }
}

def loadMainframeCode(String fromCommit, String toCommit, Map settings) {

    echo "[Info] - Loading code from release branch " + BRANCH_NAME + "."

    stage("Mainframe Load") {

        def output = bat(
            returnStdout: true,
            script: settings.jenkins.cliPath + '/IspwCLI.bat ' +  
                '-operation syncGitToIspw ' + 
                '-host "' + settings.hci.hostName + '" ' +
                '-port "' + settings.hci.hostPort + '" ' +
                '-id "' + settings.hci.user + '" ' +
                '-pass "' + settings.hci.password + '" ' +
                '-protocol None ' +
                '-code 1047 ' +
                '-timeout "0" ' +
                '-targetFolder ./ ' +
                '-data ./TopazCliWkspc ' +
                '-ispwServerConfig ' + settings.ispw.runtimeConfig + ' ' +
                '-ispwServerStream ' + settings.ispw.stream + ' ' +
                '-ispwServerApp ' + settings.ispw.application + ' ' +
                '-ispwCheckoutLevel RLSE ' +
                '-assignmentPrefix ' + settings.ispw.appPrefix + ' ' +
                '-ispwConfigPath ' + settings.ispw.configFile + ' ' +
                '-ispwContainerCreation per-branch ' +
                '-gitUsername "' + settings.git.user + '" ' +
                '-gitPassword "' + settings.git.password + '" ' +
                '-gitRepoUrl "' + settings.git.repoUrl + '" ' +
                '-gitBranch ' + BRANCH_NAME + ' ' +
                '-gitFromHash ' + settings.fromCommit + ' ' +
                '-gitLocalPath ./ ' +
                '-gitCommit ' + settings.toCommit
        )

        echo output
    }
}

def getAssignmentId(buildFile) {

    def buildFileContent

    try {
    
        buildFileContent = readJSON(file: buildFile)

        return buildFileContent.containerId
    }
    catch(Exception e) {

        echo "[Info] - No Automatic Build Params file was found.  Meaning, no mainframe sources have been changed.\n" +
        "[Info] - Mainframe Build and Test steps will be skipped. Sonar scan will be executed against code only."

        return null
    }
}

def buildMainframeCode(hostConnection, cesCredentialsId, runtimeConfig) {

    stage("Mainframe Build") {

        try{
            ispwOperation(
                connectionId:           hostConnection, //'38e854b0-f7d3-4a8f-bf31-2d8bfac3dbd4', 
                credentialsId:          cesCredentialsId,       
                consoleLogResponseBody: true, 
                ispwAction:             'BuildTask', 
                ispwRequestBody:        '''
                    runtimeConfiguration=''' + runtimeConfig + '''
                    buildautomatically = true
                '''
            )
        }
        catch(Exception e) {
            error "[Error] - Error occurred during Build of Mainframe Code: \n" +
                e
        }
    }
}

def runUnitTests(Map settings) {

    stage("Run Unit Tests") {

        echo "[Info] - Execute Unit Tests."

        def loadLibName

        if (!(settings.ttt.featureLoadLib == null)) {
            loadLibName = settings.ttt.featureLoadLib
        } else {
            loadLibName = settings.ispw.libraryQualifier + '.' + settings.ispw.application  + '.' + 'FEAT.LOAD'
        }

        totaltest(
            connectionId:                       settings.hci.connectionId,
            serverUrl:                          settings.ces.url, 
            serverCredentialsId:                settings.hci.credentialsId, 
            selectEnvironmentRadio:             '-hci',
            credentialsId:                      settings.hci.credentialsId, 
            //environmentId:                      settings.ttt.environmentIds.virtualized,
            localConfig:                        false, 
            folderPath:                         settings.ttt.vtFolder, 
            recursive:                          true, 
            selectProgramsOption:               true, 
            jsonFile:                           settings.ispw.changedProgramsFile,
            haltPipelineOnFailure:              false,                 
            stopIfTestFailsOrThresholdReached:  false,
            createJUnitReport:                  true, 
            createReport:                       true, 
            createResult:                       true, 
            createSonarReport:                  true,
            //contextVariables:                   '"load_lib=' + loadLibName + '"',
            collectCodeCoverage:                true,
            collectCCRepository:                settings.coco.repo,
            collectCCSystem:                    settings.coco.systemId,
            collectCCTestID:                    settings.coco.testId,
            clearCodeCoverage:                  false,
            logLevel:                           'INFO'
        )

    }
}

def runIntegrationTests(Map settings) {

    stage("Run Integration Tests") {

        echo "[Info] - Execute Module Integration Tests."

        if (!(settings.ttt.featureLoadLib == null)) {
            loadLibName = settings.ttt.featureLoadLib
        } else {
            loadLibName = settings.ispw.libraryQualifier + '.' + settings.ispw.application  + '.' + 'FEAT.LOAD'
        }

        // settings.ttt.environmentIds.nonVirtualized.each {

        //     def envType     = it.key
        //     def envId       = it.value

            totaltest(
                connectionId:                       settings.hci.connectionId,
                credentialsId:                      settings.hci.credentialsId,             
                serverUrl:                          settings.ces.url, 
                serverCredentialsId:                settings.hci.credentialsId, 
                selectEnvironmentRadio:             '-hci',
                //environmentId:                      envId, 
                localConfig:                        false,
                folderPath:                         settings.ttt.nvtFolder, 
                recursive:                          true, 
                selectProgramsOption:               true, 
                jsonFile:                           settings.ispw.changedProgramsFile,
                haltPipelineOnFailure:              false,                 
                stopIfTestFailsOrThresholdReached:  false,
                createJUnitReport:                  true, 
                createReport:                       true, 
                createResult:                       true, 
                createSonarReport:                  true,
                //contextVariables:                   '"load_lib=' + loadLibName + '"',
                // contextVariables:                   '"nvt_ispw_app=' + applicationQualifier + 
                //                                     ',nvt_ispw_level1=' + synchConfig.ttt.loadLibQualfiers[ispwTargetLevel].level1 + 
                //                                     ',nvt_ispw_level2=' + synchConfig.ttt.loadLibQualfiers[ispwTargetLevel].level2 + 
                //                                     ',nvt_ispw_level3=' + synchConfig.ttt.loadLibQualfiers[ispwTargetLevel].level3 + 
                //                                     ',nvt_ispw_level4=' + synchConfig.ttt.loadLibQualfiers[ispwTargetLevel].level4 + 
                //                                     '"',                
                collectCodeCoverage:                true,
                collectCCRepository:                settings.coco.repo,
                collectCCSystem:                    settings.coco.systemId,
                collectCCTestID:                    settings.coco.testId,
                clearCodeCoverage:                  false,
                logLevel:                           'INFO'
            )
        // }
    }
}

def getCodeCoverage(settings) {

    echo "[Info] - Getting Code Coverage data from Mainframe."

    stage("Get Code Coverage") {

        step(
            [
                $class:             'CodeCoverageBuilder', 
                connectionId:       'de2ad7c3-e924-4dc2-84d5-d0c3afd3e756', //settings.hci.connectionId, 
                credentialsId:      settings.hci.credentialsId,
                analysisProperties: """
                    cc.sources=${settings.coco.sources}
                    cc.repos=${settings.coco.repo}
                    cc.system=${settings.coco.systemId}
                    cc.test=${settings.coco.testId}
                """
                //                    cc.ddio.overrides=${ccDdioOverrides}
            ]
        )
    }
}

def runSonarScan(Map settings) {

    stage("SonarQube") {

        def sonarTestResults        = ''
        def sonarTestsParm          = ''
        def sonarTestReportsParm    = ''
        def sonarCodeCoverageParm   = ''
        def scannerHome             = tool settings.sonar.scanner            

        def sonarProjectName

        sonarTestReportsParm        = getReportsParm(settings)

        if(sonarTestReportsParm != '') {

            sonarTestsParm          = ' -Dsonar.tests="' + settings.ttt.rootFolder + '"'
        }

        sonarCodeCoverageParm = getCodeCoverageParm(settings)

        withSonarQubeEnv(settings.sonar.server) {

            bat '"' + scannerHome + '/bin/sonar-scanner"' + 
                ' -Dsonar.branch.name=' + BRANCH_NAME +
                ' -Dsonar.projectKey=' + settings.sonar.projectName + 
                ' -Dsonar.projectName=' + settings.sonar.projectName +
                ' -Dsonar.projectVersion=1.0' +
                ' -Dsonar.sources=' + settings.sonar.cobolFolder + 
                ' -Dsonar.cobol.copy.directories=' + settings.sonar.copybookFolder +
                ' -Dsonar.cobol.file.suffixes=' + settings.sonar.cobolSuffixes + 
                ' -Dsonar.cobol.copy.suffixes=' + settings.sonar.copySuffixes +
                sonarTestsParm +
                sonarTestReportsParm +
                sonarCodeCoverageParm +
                ' -Dsonar.ws.timeout=480' +
                ' -Dsonar.sourceEncoding=UTF-8'
        }
    }
}

def getReportsParm(Map settings) {

    def reportsParm = ''

    try {

        readFile(file: settings.sonar.resultsFolder + '/' + settings.sonar.resultsFileVt)
        reportsParm    = ' -Dsonar.testExecutionReportPaths="' + settings.sonar.resultsFolder + '/' + settings.sonar.resultsFileVt + '"'

        echo "[Info] - Found Virtualized Test Results File\n" +
            settings.sonar.resultsFolder + '/' + settings.sonar.resultsFileVt
    }
    catch(Exception e) {

        echo "[Info] - No Virtualized Test Results File was produced.\n" +
            e
    }

    try {

        readFile(file: settings.sonar.resultsFolder + '/' + settings.sonar.resultsFileVt)
        echo "[Info] - Found Non-Virtualized Test Results File\n" +
            settings.sonar.resultsFolder + '/' + settings.sonar.resultsFileNvt

        if(reportsParm == '') {
            
            reportsParm    = ' -Dsonar.testExecutionReportPaths="' + settings.sonar.resultsFolder + '/' + settings.sonar.resultsFileNvt + '"'

        }
        else {

            reportsParm    = reportsParm + ',"' + settings.sonar.resultsFolder + '/' + settings.sonar.resultsFileNvt + '"'
        }
    }
    catch(Exception e) {

        echo "[Info] - No Non-Virtualized Test Results File was produced.\n" +
            e
    }

    return reportsParm
}

def getCodeCoverageParm(settings) {

    def codeCoverageParm = ''

    try{
        readFile(file: settings.sonar.codeCoverageFile)
        codeCoverageParm   = ' -Dsonar.coverageReportPaths=' + settings.sonar.codeCoverageFile

        echo "[Info] - Found CodeCoverage Results File\n" +
            settings.sonar.codeCoverageFile
    }
    catch(Exception e){
        codeCoverageParm   = ''

        echo "[Info] - No COdeCoverage File was found.\n" +
            e
    }

    return codeCoverageParm
}

def startReleaseProcess(assignmentId, settings) {

    stage("Start Release") {
        //def ispwReleaseNumber   = determineIspwReleaseNumber(settings.currentTag)

        build(
            job: '../Demo_Workflow/Run_Release',
            wait: false,  
            parameters: [
                string(
                    name:   'ISPW_Application', 
                    value:  settings.ispw.application
                ), 
                string(
                    name:   'ISPW_Assignment', 
                    value:  assignmentId
                ), 
                string(
                    name:   'ISPW_Owner_Id', 
                    value:  settings.hci.user 
                ), 
                string(
                    name:   'Git_Release_Tag', 
                    value:  settings.currentTag
                ), 
                string(
                    name:   'ISPW_App_Prefix', 
                    value:  settings.ispw.appPrefix
                ), 
                string(
                    name:   'Host_Connection', 
                    value:  settings.hci.connectionId
                ), 
                string(
                    name:   'Jenkins_CES_Credentials', 
                    value: settings.ces.credentialsId
                ), 
                string(
                    name:   'ISPW_Runtime_Config', 
                    value:  settings.ispw.runtimeConfig
                ),
                string(
                    name:   'Git_Repo_Url', 
                    value:  settings.git.repoUrl
                ),
                string(
                    name:   'Git_Hub_Credentials', 
                    value:  settings.git.credentialsId
                )                
            ], 
            waitForStart: true
        )
    }
}

// def startXlr(assignmentId, settings) {

//     stage("Start Release") {
//         def ispwReleaseNumber   = determineIspwReleaseNumber(settings.currentTag)
//         def cesToken            = extractToken(settings.ces.credentialsId)

//         echo "[Info] - Starting XLR with:\n" +
//             '   CES_Token: ' + cesToken + "\n" +
//             '   ISPW_Runtime: ' + settings.ispw.runtimeConfig + "\n" +
//             '   ISPW_Application: ' + settings.ispw.application + "\n" +
//             '   ISPW_Owner: ' + settings.hci.user + "\n" +
//             '   ISPW_Assignment: ' + assignmentId + "\n" +
//             '   Jenkins_CES_Credentials: ' + settings.ces.credentialsId + "\n" +
//             '   Release Number: ' + ispwReleaseNumber

//         xlrCreateRelease(
//             releaseTitle:       "GitFlow - Release for ${settings.hci.user}", 
//             serverCredentials:  'admin', 
//             startRelease:       true, 
//             template:           'GitFlow/GitFlow_Release', 
//             variables: [
//                 [
//                     propertyName:   'CES_Token', 
//                     propertyValue:  cesToken
//                 ], 
//                 [
//                     propertyName:   'ISPW_Release_Number', 
//                     propertyValue:  ispwReleaseNumber
//                 ], 
//                 [
//                     propertyName:   'ISPW_Assignment', 
//                     propertyValue:  assignmentId
//                 ], 
//                 [
//                     propertyName:   'ISPW_Runtime', 
//                     propertyValue:  settings.ispw.runtimeConfig
//                 ], 
//                 [
//                     propertyName:   'ISPW_Application', 
//                     propertyValue:  settings.ispw.application
//                 ], 
//                 [
//                     propertyName:   'ISPW_Owner', 
//                     propertyValue:  settings.hci.user
//                 ],
//                 [
//                     propertyName:   'Git_Tag', 
//                     propertyValue:  settings.currentTag
//                 ],
//                 [
//                     propertyName:   'Git_Branch', 
//                     propertyValue:  BRANCH_NAME
//                 ],
//                 [
//                     propertyName: 'Jenkins_CES_Credentials', 
//                     propertyValue: settings.ces.credentialsId
//                 ],
//                 [
//                     propertyName: 'Jenkins_Git_Credentials', 
//                     propertyValue: settings.git.credentialsId
//                 ] 
//             ]
//         )    
//     }
// }
