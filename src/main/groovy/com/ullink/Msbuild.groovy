package com.ullink

import org.gradle.api.internal.ConventionTask
import org.gradle.api.tasks.StopActionException
import org.gradle.api.tasks.TaskAction

class Msbuild extends ConventionTask {
    static final String MSBUILD_TOOLS_PATH = 'MSBuildToolsPath'
    static final String MSBUILD_EXE = 'msbuild.exe'
    static final String MSBUILD_PREFIX         = "SOFTWARE\\Microsoft\\MSBuild\\ToolsVersions\\"
    static final String MSBUILD_WOW6432_PREFIX = "SOFTWARE\\Wow6432Node\\Microsoft\\MSBuild\\ToolsVersions\\"
    
    String version
    String msbuildDir
    def solutionFile
    def projectFile
    String loggerAssembly
    Boolean optimize
    Boolean debugSymbols
    String debugType
    String platform
    def destinationDir
    def intermediateDir
    Boolean generateDoc
    String projectName
    String configuration
    List<String> defineConstants
    ProjectFileParser parser
    List<String> targets
    String verbosity
    Map<String,String> parameters = [:]
	Map<String, ProjectFileParser> projects = [:]
    
    Msbuild() {
		description = 'Executes MSBuild on the specified project/solution'
        (version == null ? ["4.0", "3.5","2.0"] : [version]).find { x ->
            trySetMsbuild(MSBUILD_WOW6432_PREFIX + x) || trySetMsbuild(MSBUILD_WOW6432_PREFIX + x)
        }

        if (msbuildDir == null) {
            throw new StopActionException("Msbuild.exe not found")
        }
        conventionMapping.map "solutionFile", { project.file(project.name + ".sln").exists() ? project.name + ".sln" : null }
        conventionMapping.map "projectFile", { project.file(project.name + ".csproj").exists() ? project.name + ".csproj" : null }
        conventionMapping.map "projectName", { project.name }
        inputs.files {
            if (isSolution()) {
                project.file(getSolutionFile())
            } else if (isProject()) {
                project.file(getProjectFile())
            }
        }
        inputs.files {
            if (resolveProject()) {
                parser.gatherInputs()
            }
        }
		outputs.upToDateWhen {
			resolveProject()
		}
        outputs.dir {
            if (resolveProject()) {
                parser.getOutputDirs()
            }
        }
    }
	
	boolean isSolution() {
		projectFile == null && getSolutionFile() != null
	}
	
	boolean isProject() {
		solutionFile == null && getProjectFile() != null
	}
	
	ProjectFileParser getMainProject() {
        if (resolveProject()) {
            parser
        }
	}
    
    boolean trySetMsbuild(String key) {
        def v = Registry.getValue(Registry.HKEY_LOCAL_MACHINE, key, MSBUILD_TOOLS_PATH)
        if (v != null && new File(v).isDirectory()) {
            msbuildDir = v
            return true
        }
        false
    }
    
    boolean resolveProject() {
        if (parser == null) {
			if (isSolution()) {
			    def solParser = new SolutionFileParser(msbuild: this, solutionFile: getSolutionFile(), properties: getInitProperties())
				solParser.readSolutionFile()
	            parser = solParser.initProjectParser
			} else if (isProject()) {
	            parser = new ProjectFileParser(msbuild: this, projectFile: getProjectFile(), initProperties: { getInitProperties() })
	            parser.readProjectFile()
			}
			if (parser != null && logger.debugEnabled) {
                logger.debug "Resolved Msbuild properties:"
                parser.properties.sort({ a,b->a.key <=> b.key }).each({ logger.debug it.toString() })
                logger.debug "Resolved Msbuild items:"
                parser.items.sort({ a,b->a.key <=> b.key }).each({ logger.debug it.toString() })
            }
        }
        parser != null
    }
    
    void setTarget(String s) {
        targets = [s]
    }
    
    @TaskAction
    def build() {
        
        def commandLineArgs = [ new File(msbuildDir, MSBUILD_EXE) ]

        commandLineArgs += '/nologo'

        if (getSolutionFile()) {
            commandLineArgs += project.file(getSolutionFile())
        } else if (getProjectFile()) {
            commandLineArgs += project.file(getProjectFile())
        }
        
        if (loggerAssembly) {
            commandLineArgs += '/l:'+loggerAssembly
        }
        if (targets && !targets.isEmpty()) {
            commandLineArgs += '/t:'+targets.join(';')
        }
        String verb = verbosity
        if (!verb) {
            if (logger.debugEnabled) {
                verb = 'detailed'
            } else if (logger.infoEnabled) {
                verb = 'normal'
            } else {
                verb = 'minimal' // 'quiet'
            }
        }
        if (verb) {
            commandLineArgs += '/v:'+verb
        }

        def cmdParameters = getInitProperties()
        
        cmdParameters.each {
            if (it.value) {
                commandLineArgs += '/p:' + it.key + '='+ it.value
            }
        }
        
        project.exec {
            commandLine = commandLineArgs
        }
    }

    Map getInitProperties() {
        def cmdParameters = new HashMap<String, String>()
        if (parameters != null) {
            cmdParameters.putAll(parameters)
        }
        cmdParameters.Project = getProjectName()
        cmdParameters.GenerateDocumentation = generateDoc
        cmdParameters.DebugType = debugType
        cmdParameters.Optimize = optimize
        cmdParameters.DebugSymbols = debugSymbols
        cmdParameters.OutputPath = destinationDir == null ? null : project.file(destinationDir)
        cmdParameters.IntermediateOutputPath = intermediateDir == null ? null : project.file(intermediateDir)
        cmdParameters.Configuration = configuration
        cmdParameters.Platform = platform
        if (defineConstants != null && !defineConstants.isEmpty()) {
            cmdParameters.DefineConstants = defineConstants.join(';')
        }
        return cmdParameters
    }
}
