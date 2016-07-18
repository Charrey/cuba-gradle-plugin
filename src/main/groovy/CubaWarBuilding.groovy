/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import org.apache.commons.lang.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction

class CubaWarBuilding extends DefaultTask {
    Project coreProject;
    Project webProject;
    Project portalProject;

    def String appHome
    def String appName
    def boolean singleWar = true
    @Deprecated
    def Object webXml
    def String webXmlPath

    def boolean projectAll
    def List<String> webContentExclude = []
    Closure doAfter
    def  Map<String, Object> appProperties

    def coreJarNames
    def webJarNames
    def portalJarNames

    def String coreTmpWarDir
    def String webTmpWarDir
    def String portalTmpWarDir

    def boolean includeJdbcDriver = false
    def boolean includeContextXml = false
    @Deprecated
    def String coreContextXml
    def String coreContextXmlPath

    def Boolean hsqlInProcess = false

    def String distrDir = "${project.buildDir}/distributions/war"

    CubaWarBuilding() {
        project.afterEvaluate {
            def childProjects = project.getChildProjects()

            if (!coreProject) {
                project.logger.info("[CubaWarBuilding] core project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-core")) {
                        coreProject = entry.getValue()
                        project.logger.info("[CubaWarBuilding] $coreProject is set as core project")
                        break;
                    }
                }
            }

            if (!webProject) {
                project.logger.info("[CubaWarBuilding] web project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-web")) {
                        webProject = entry.getValue()
                        project.logger.info("[CubaWarBuilding] $webProject is set as web project")
                        break;
                    }
                }
            }

            if (!portalProject && !singleWar) {
                project.logger.info("[CubaWarBuilding] portal project is not set, trying to find it automatically")
                for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                    if (entry.getKey().endsWith("-portal")) {
                        portalProject = entry.getValue()
                        project.logger.info("[CubaWarBuilding] $portalProject is set as portal project")
                        break;
                    }
                }
            }

            // look for web toolkit module
            for (Map.Entry<String, Project> entry : childProjects.entrySet()) {
                if (entry.getKey().endsWith("-web-toolkit")) {
                    def webToolkit = entry.getValue()
                    def assembleWebToolkit = webToolkit.getTasksByName("assemble", false).iterator().next()
                    this.dependsOn(assembleWebToolkit)
                    break;
                }
            }
        }
    }

    void setCoreProject(Project coreProject) {
        this.coreProject = coreProject
        def assembleCore = coreProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assembleCore)
    }

    void setWebProject(Project webProject) {
        this.webProject = webProject
        def assembleWeb = webProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assembleWeb)
    }

    void setPortalProject(Project portalProject) {
        this.portalProject = portalProject
        def assembleWeb = portalProject.getTasksByName('assemble', false).iterator().next()
        this.dependsOn(assembleWeb)
    }

    String warDir(Project project) {
        if (project == coreProject) return coreTmpWarDir
        else if (project == webProject) return webTmpWarDir
        else if (project == portalProject) return portalTmpWarDir
        else return null
    }

    @TaskAction
    def build() {
        init()

        copyLibs(coreProject)
        copyLibs(webProject)
        if (portalProject) copyLibs(portalProject)

        def coreProperties = collectProperties(coreProject)
        def webProperties = collectProperties(webProject)
        def portalProperties = portalProject ? collectProperties(portalProject) : []

        copyDbScripts(coreProject)

        copyWebContent(coreProject)
        copyWebContent(webProject)
        if (portalProject) copyWebContent(portalProject)

        if (includeContextXml) {
            copyWebContext(coreProject)
            if (!singleWar) {
                copyWebContext(webProject)
                if (portalProject) copyWebContext(portalProject)
            }
        }

        copySpecificWebContent(webProject)

        processDoAfter()

        touchWebXml(coreProject)
        touchWebXml(webProject)
        if (portalProject) {
            touchWebXml(portalProject)
        }

        if (singleWar) {
            def summaryProperties = coreProperties + webProperties
            writeLocalAppProperties(webProject, summaryProperties)
            writeDependencies(coreProject, 'core', coreJarNames)
            writeDependencies(webProject, 'web', webJarNames)

            packWarFile(webProject, webProject.file("${warDir(webProject)}/${appName}.war"))
            project.copy {
                from webProject.file("${warDir(webProject)}/${appName}.war")
                into distrDir
            }
        } else {
            writeLocalAppProperties(coreProject, coreProperties)
            writeLocalAppProperties(webProject, webProperties)
            if (portalProject) writeLocalAppProperties(portalProject, portalProperties)

            packWarFile(webProject, webProject.file("${warDir(webProject)}/${appName}.war"))
            packWarFile(coreProject, coreProject.file("${warDir(coreProject)}/${appName}-core.war"))
            if (portalProject) packWarFile(portalProject, portalProject.file("${warDir(portalProject)}/${appName}-portal.war"))

            webProject.copy {
                from webProject.file("${warDir(webProject)}/${appName}.war")
                from coreProject.file("${warDir(coreProject)}/${appName}-core.war")
                into distrDir
            }

            if (portalProject) {
                portalProject.copy {
                    from portalProject.file("${warDir(portalProject)}/${appName}-portal.war")
                    into distrDir
                }
            }
        }

        project.delete("${project.buildDir}/tmp")
    }

    private void init() {
        webXmlPath = webXmlPath ? "$project.rootDir/$webXmlPath" : webXml
        coreContextXmlPath = coreContextXmlPath ? "$project.rootDir/$coreContextXmlPath" : coreContextXml

        if (!singleWar && webXmlPath) {
            throw new GradleException("[CubaWarBuilding] 'webXmlPath' property should be used only in single WAR building. " +
                    "Please set 'singleWar = true' or remove 'webXmlPath' property.")
        }

        if (singleWar) {
            if (!webXmlPath)
                throw new GradleException("[CubaWarBuilding] To build single WAR, you should set the 'webXmlPath' property")

            def webXmlFile = new File(webXmlPath)
            if (!webXmlFile.exists()) {
                throw new GradleException("[CubaWarBuilding] File '$webXmlPath' does not exist")
            }

            if (portalProject) {
                throw new GradleException("[CubaWarBuilding] 'portalProject' property is not supported in single WAR building. " +
                        "Please remove the 'portalProject' property.")
            }
        }

        project.delete(distrDir)

        Set coreDeployTasks = coreProject.getTasksByName('deploy', false)
        if (coreDeployTasks.isEmpty())
            throw new GradleException("[CubaWarBuilding] 'core' module has no 'deploy' task")
        def deployCore = coreDeployTasks.first()

        Set webDeployTasks = webProject.getTasksByName('deploy', false)
        if (webDeployTasks.isEmpty())
            throw new GradleException("[CubaWarBuilding] 'web' module has no 'deploy' task")
        def deployWeb = webDeployTasks.first()

        def deployPortal = null
        if (portalProject) {
            Set portalDeployTasks = webProject.getTasksByName('deploy', false)
            if (portalDeployTasks.isEmpty())
                throw new GradleException("[CubaWarBuilding] 'portal' module has no 'deploy' task")
            deployPortal = portalDeployTasks.first()
        }

        if (!coreJarNames) {
            coreJarNames = deployCore.getAllJarNames()
        }

        if (!webJarNames) {
            webJarNames = deployWeb.getAllJarNames()
        }

        if (deployPortal && !portalJarNames) {
            portalJarNames = deployPortal.getAllJarNames()
        }

        if (singleWar) {
            coreTmpWarDir = "${project.buildDir}/tmp/war"
            webTmpWarDir = coreTmpWarDir
        } else {
            coreTmpWarDir = "${project.buildDir}/tmp/core/war"
            webTmpWarDir = "${project.buildDir}/tmp/web/war"
            portalTmpWarDir = "${project.buildDir}/tmp/portal/war"
        }

        if (!appName) {
            appName = deployWeb.appName
        }
    }

    private Map<String, Object> collectProperties(Project theProject) {
        def properties = [
                'cuba.logDir' : "$appHome/logs",
                'cuba.confDir': "$appHome/\${cuba.webContextName}/conf",
                'cuba.tempDir': "$appHome/\${cuba.webContextName}/temp",
                'cuba.dataDir': "$appHome/\${cuba.webContextName}/work"
        ]

        if (theProject == coreProject) {
            properties += [
                    'cuba.dataSourceJndiName'  : "jdbc/CubaDS",
                    'cuba.download.directories': "\${cuba.tempDir};\${cuba.logDir}",
                    'cuba.dbDir'               : "web-inf:db"
            ]
        }

        if (theProject == webProject) {
            properties += [
                    'cuba.connectionUrlList'        : "http://localhost:8080/${appName}-core",
                    'cuba.useLocalServiceInvocation': singleWar ? "true" : "false"
            ]
        }

        if (theProject == portalProject) {
            properties += [
                    'cuba.connectionUrlList'        : "http://localhost:8080/${appName}-core",
                    'cuba.useLocalServiceInvocation': "false"
            ]
        }

        if (appProperties) {
            properties += appProperties
        }
        properties
    }

    private void copyLibs(Project theProject) {
        theProject.logger.info("[CubaWarBuilding] copying libs from configurations.runtime")
        theProject.copy {
            from theProject.configurations.runtime
            from theProject.libsDir
            into "${warDir(theProject)}/WEB-INF/lib"
            include { details ->
                def name = details.file.name
                return !(name.endsWith('-sources.jar'))
            }
        }

        if (includeJdbcDriver) {
            theProject.logger.info("[CubaWarBuilding] copying libs from configurations.jdbc")
            def libsDir = "${warDir(theProject)}/WEB-INF/lib"
            theProject.copy {
                from theProject.configurations.jdbc {
                    exclude { f ->
                        def file = f.file
                        def fileName = (String) file.name
                        if (new File(libsDir, fileName).exists()) {
                            return true
                        }

                        file.absolutePath.startsWith(project.file(libsDir).absolutePath)
                    }
                }
                into libsDir
            }
        }
    }

    private void writeDependencies(Project theProject, String applicationType, def jarNames) {
        File dependenciesFile = new File("${warDir(theProject)}/WEB-INF/${applicationType}.dependencies")
        if (!dependenciesFile.exists()) {
            throw new GradleException("[CubaWarBuilding] Can't find dependencies file.")
        }

        dependenciesFile.withWriter('UTF-8') { writer ->
            theProject.configurations.runtime.each { File lib ->
                if (!lib.name.endsWith('-sources.jar')
                        && !lib.name.endsWith('-tests.jar')
                        && jarNames.find { lib.name.startsWith(it) } != null) {
                    writer << lib.name << '\n'
                }
            }

            new File("$theProject.libsDir").listFiles().each { File lib ->
                if (!lib.name.endsWith('-sources.jar')
                        && !lib.name.endsWith('-tests.jar')
                        && jarNames.find { lib.name.startsWith(it) } != null) {
                    writer << lib.name << '\n'
                }
            }
        }
    }

    private void copyDbScripts(Project theProject) {
        theProject.copy {
            from "${theProject.buildDir}/db"
            into "${warDir(theProject)}/WEB-INF/db"
        }
    }

    private void copyWebContent(Project theProject) {
        theProject.logger.info("[CubaWarBuilding] copying from web to ${warDir(theProject)}")

        if (webXmlPath) {
            def webXml = new File(webXmlPath)
            if (!webXml.exists()) {
                throw new GradleException("[CubaWarBuilding] Can't find web.xml.")
            }

            def webXmlFileName = webXml.name
            theProject.copy {
                from 'web'
                into warDir(theProject)
                exclude '**/context.xml'
                exclude "**/$webXmlFileName"//do not copy webXml file twice
            }

            theProject.logger.info("[CubaWarBuilding] copying web.xml from ${webXmlPath} to ${warDir(theProject)}/WEB-INF/web.xml")
            theProject.copy {
                from webXmlPath
                into "${warDir(theProject)}/WEB-INF/"
                rename { String fileName ->
                    "web.xml"
                }
            }
        } else {
            theProject.copy {
                from 'web'
                into warDir(theProject)
                exclude '**/context.xml'
            }
        }
    }

    private void copyWebContext(Project theProject) {
        theProject.logger.info("[CubaWarBuilding] copying context.xml to ${warDir(theProject)}")
        if (theProject == coreProject && coreContextXmlPath) {
            def coreContextXml = new File(coreContextXmlPath)
            if (!coreContextXml.exists()) {
                throw new GradleException("[CubaWarBuilding] Can't find core context.xml.")
            }

            def coreContextXmlFileName = coreContextXml.name
            theProject.logger.info("[CubaWarBuilding] copying context.xml from ${coreContextXmlPath} to ${warDir(theProject)}/META-INF/context.xml")
            theProject.copy {
                from coreContextXmlPath
                into "${warDir(theProject)}/META-INF/"
                rename { String fileName ->
                    "context.xml"
                }
            }
            if (!'context.xml'.equals(coreContextXmlFileName)) {
                theProject.delete("${warDir(theProject)}/META-INF/${coreContextXmlFileName}")
            }
        } else if (theProject == coreProject && hsqlInProcess) {
            def contextFile = new File("${theProject.projectDir}/web/META-INF/context.xml")
            if (!contextFile.exists()) {
                throw new GradleException("[CubaWarBuilding] Can't find core context.xml.")
            }

            def context
            try {
                context = new XmlParser().parse(contextFile)
            } catch (Exception ignored) {
                throw new GradleException("[CubaWarBuilding] Core context.xml parsing error.")
            }

            String url = context.Resource.@url.get(0)
            context.Resource.@url = "jdbc:hsqldb:file:db/hsql" + url.substring(url.lastIndexOf('/'))

            def targetFile = new File("${warDir(theProject)}/META-INF/context.xml")
            def printer = new XmlNodePrinter(new PrintWriter(new FileWriter(targetFile)))
            printer.preserveWhitespace = true
            printer.print(context)
        } else {
            theProject.copy {
                from 'web/META-INF/context.xml'
                into "${warDir(theProject)}/META-INF"
            }
        }
    }

    private void copySpecificWebContent(Project theProject) {
        if (!projectAll) {
            def excludePatterns = ['**/web.xml', '**/context.xml'] + webContentExclude
            if (theProject.configurations.findByName('webcontent')) {
                theProject.configurations.webcontent.files.each { dep ->
                    theProject.logger.info("[CubaWarBuilding] copying webcontent from $dep.absolutePath to ${warDir(theProject)}")
                    theProject.copy {
                        from theProject.zipTree(dep.absolutePath)
                        into warDir(theProject)
                        excludes = excludePatterns
                        includeEmptyDirs = false
                    }
                }
            }
            theProject.logger.info("[CubaWarBuilding] copying webcontent from ${theProject.buildDir}/web to ${warDir(theProject)}")
            theProject.copy {
                from "${theProject.buildDir}/web"
                into warDir(theProject)
                excludes = excludePatterns
            }
            project.logger.info("[CubaWarBuilding] copying from web to ${warDir(theProject)}")
            project.copy {
                from theProject.file('web')
                into warDir(theProject)
                excludes = excludePatterns
            }
            def webToolkit = theProject.rootProject.subprojects.find { subprj -> subprj.name.endsWith('web-toolkit') }
            if (webToolkit) {
                theProject.logger.info("[CubaWarBuilding] copying webcontent from ${webToolkit.buildDir}/web to ${warDir(theProject)}")
                theProject.copy {
                    from "${webToolkit.buildDir}/web"
                    into warDir(theProject)
                    exclude '**/gwt-unitCache/'
                }
            }
        } else {
            theProject.logger.info("[CubaWarBuilding] copying webcontent from theProject-all directories to ${warDir(theProject)}")
            theProject.copy {
                from new File(project.project(':cuba-web').projectDir, 'web')
                from new File(project.project(':charts-web').projectDir, 'web')
                from new File(project.project(':workflow-web').projectDir, 'web')
                from new File(project.project(':reports-web').projectDir, 'web')
                from new File(project.project(':refapp-web').buildDir, 'web')
                from new File(project.project(':refapp-web-toolkit').buildDir, 'web')
                from new File(project.project(':refapp-web').projectDir, 'web')
                into warDir(theProject)
                exclude '**/web.xml'
                exclude '**/context.xml'
                exclude '**/gwt-unitCache/'
            }
        }
    }

    private void writeLocalAppProperties(Project theProject, def properties) {
        File appPropFile = new File("${warDir(theProject)}/WEB-INF/local.app.properties")

        project.logger.info("[CubaWarBuilding] writing $appPropFile")
        appPropFile.withWriter('UTF-8') { writer ->
            properties.each { key, value ->
                writer << key << ' = ' << value << '\n'
            }
        }
    }

    private void processDoAfter() {
        if (doAfter) {
            project.logger.info("[CubaWarBuilding] calling doAfter")
            doAfter.call()
        }
    }

    private void touchWebXml(Project theProject) {
        def webXml = new File("${warDir(theProject)}/WEB-INF/web.xml")
        if (!webXml.exists()) {
            throw new GradleException("[CubaWarBuilding] Can't find web.xml for the ${theProject}")
        }

        if (theProject.ext.has('webResourcesTs')) {
            theProject.logger.info("[CubaWarBuilding] update web resources timestamp")

            // detect version automatically
            String buildTimeStamp = theProject.ext.get('webResourcesTs').toString()

            def webXmlText = webXml.text
            if (StringUtils.contains(webXmlText, '${webResourcesTs}')) {
                webXmlText = webXmlText.replace('${webResourcesTs}', buildTimeStamp)
            }
            webXml.write(webXmlText)
        }
        theProject.logger.info("[CubaWarBuilding] touch ${warDir(theProject)}/WEB-INF/web.xml")
        webXml.setLastModified(new Date().getTime())
    }

    private void packWarFile(Project project, File destFile) {
        ant.jar(destfile: destFile, basedir: warDir(project))
    }
}