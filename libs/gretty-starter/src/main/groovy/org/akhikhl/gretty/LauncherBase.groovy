/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import groovy.json.JsonBuilder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 * @author akhikhl
 */
abstract class LauncherBase implements Launcher {

  protected static final Logger log = LoggerFactory.getLogger(LauncherBase)

  protected final LauncherConfig config
  protected final ServerConfig sconfig
  protected final Iterable<WebAppConfig> webAppConfigs
  protected final WebAppClassPathResolver classPathResolver
  protected final ExecutorService executorService

  ScannerManager scannerManager

  LauncherBase(LauncherConfig config) {
    this.config = config
    sconfig = config.getServerConfig()
    webAppConfigs = config.getWebAppConfigs()
    classPathResolver = config.getWebAppClassPathResolver()
    executorService = Executors.newSingleThreadExecutor()
  }

  private getRunConfigJson() {
    def json = new JsonBuilder()
    json {
      writeRunConfigJson(delegate)
    }
    json
  }

  protected abstract Collection<URL> getRunnerClassPath()

  protected String getServerManagerFactory() {
    'org.akhikhl.gretty.ServerManagerFactory'
  }

  protected abstract String getServletContainerName()

  @Override
  void launch() {
    Thread thread = launchThread()

    if(config.getInteractive()) {
      System.out.println 'Press any key to stop the server.'
      System.in.read()
      log.debug 'Sending command: {}', 'stop'
      ServiceProtocol.send(sconfig.servicePort, 'stop')
    } else
      System.out.println "Run '${config.getStopCommand()}' to stop the server."
    thread.join()
    log.warn '{} stopped.', getServletContainerName()
  }

  protected abstract void javaExec(JavaExecParams params)

  protected void launchProcess() {
    JavaExecParams params = new JavaExecParams()
    params.classpath = getRunnerClassPath()
    params.main = 'org.akhikhl.gretty.Runner'
    params.args = [ "--servicePort=${sconfig.servicePort}", "--statusPort=${sconfig.statusPort}", "--serverManagerFactory=${getServerManagerFactory()}" ]
    params.debug = config.getDebug()
    params.jvmArgs = sconfig.jvmArgs
    params.systemProperties = sconfig.systemProperties
    javaExec(params)
  }

  @Override
  Thread launchThread() {

    for(WebAppConfig webAppConfig in webAppConfigs)
      prepareToRun(webAppConfig)

    Future futureStatus = executorService.submit({ ServiceProtocol.readMessage(sconfig.statusPort) } as Callable)
    def thread = Thread.start {
      sconfig.onStart*.call()
      try {
        scannerManager?.startScanner()
        try {
          launchProcess()
        } finally {
          scannerManager?.stopScanner()
        }
      } finally {
        sconfig.onStop*.call()
      }
    }
    def status = futureStatus.get()
    log.debug 'Got init status: {}', status

    futureStatus = executorService.submit({ ServiceProtocol.readMessage(sconfig.statusPort) } as Callable)
    def runConfigJson = getRunConfigJson()
    log.debug 'Sending parameters to port {}', sconfig.servicePort
    log.debug runConfigJson.toPrettyString()
    ServiceProtocol.send(sconfig.servicePort, runConfigJson.toString())
    status = futureStatus.get()
    log.debug 'Got start status: {}', status

    System.out.println()
    log.warn '{} started.', getServletContainerName()
    for(WebAppConfig webAppConfig in webAppConfigs) {
      String webappName
      if(webAppConfig.inplace)
        webappName = webAppConfig.projectPath
      else {
        def warFile = webAppConfig.warResourceBase
        if(!(warFile instanceof File))
          warFile = new File(warFile.toString())
        webappName = warFile.name
      }
      if(sconfig.httpEnabled && sconfig.httpsEnabled) {
        log.warn '{} runs at the addresses:', webappName
        log.warn '  http://{}:{}{}', sconfig.host, sconfig.httpPort, webAppConfig.contextPath
        log.warn '  https://{}:{}{}', sconfig.host, sconfig.httpsPort, webAppConfig.contextPath
      }
      else if(sconfig.httpEnabled)
        log.warn '{} runs at the address http://{}:{}{}', webappName, sconfig.host, sconfig.httpPort, webAppConfig.contextPath
      else if(sconfig.httpsEnabled)
        log.warn '{} runs at the address https://{}:{}{}', webappName, sconfig.host, sconfig.httpsPort, webAppConfig.contextPath
    }
    log.info 'servicePort: {}, statusPort: {}', sconfig.servicePort, sconfig.statusPort

    thread
  }

  protected void prepareToRun(WebAppConfig wconfig) {
    wconfig.prepareToRun()
  }

  protected void writeLoggingConfig(json) {
    json.with {
      if(sconfig.logbackConfigFile)
        logbackConfig sconfig.logbackConfigFile.absolutePath
      else
        logging {
          loggingLevel sconfig.loggingLevel
          consoleLogEnabled sconfig.consoleLogEnabled
          fileLogEnabled sconfig.fileLogEnabled
          logFileName sconfig.logFileName
          logDir sconfig.logDir
        }
    }
  }

  protected void writeRunConfigJson(json) {
    def self = this
    json.with {
      if(sconfig.host)
        host sconfig.host
      if(sconfig.httpEnabled) {
        httpPort sconfig.httpPort
        if(sconfig.httpIdleTimeout)
          httpIdleTimeout sconfig.httpIdleTimeout
      }
      if(sconfig.httpsEnabled) {
        httpsPort sconfig.httpsPort
        if(sconfig.httpsIdleTimeout)
          httpsIdleTimeout sconfig.httpsIdleTimeout
        if(sconfig.sslKeyStorePath)
          sslKeyStorePath sconfig.sslKeyStorePath.absolutePath
        if(sconfig.sslKeyStorePassword)
          sslKeyStorePassword sconfig.sslKeyStorePassword
        if(sconfig.sslKeyManagerPassword)
          sslKeyManagerPassword sconfig.sslKeyManagerPassword
        if(sconfig.sslTrustStorePath)
          sslTrustStorePath sconfig.sslTrustStorePath.absolutePath
        if(sconfig.sslTrustStorePassword)
          sslTrustStorePassword sconfig.sslTrustStorePassword
      }
      if(sconfig.jettyXmlFile)
        jettyXml sconfig.jettyXmlFile.absolutePath
      writeLoggingConfig(json)
      webApps webAppConfigs.collect { WebAppConfig webAppConfig ->
        { ->
          inplace webAppConfig.inplace
          self.writeWebAppClassPath(delegate, webAppConfig)
          contextPath webAppConfig.contextPath
          resourceBase (webAppConfig.inplace ? webAppConfig.inplaceResourceBase : webAppConfig.warResourceBase ?: webAppConfig.warResourceBase.toString() ?: '')
          if(webAppConfig.initParameters)
            initParams webAppConfig.initParameters
          if(webAppConfig.realm)
            realm webAppConfig.realm
          if(webAppConfig.realmConfigFile)
            realmConfigFile webAppConfig.realmConfigFile.absolutePath
          if(webAppConfig.jettyEnvXmlFile)
            jettyEnvXml webAppConfig.jettyEnvXmlFile.absolutePath
          if(webAppConfig.springBootSources)
            springBootSources webAppConfig.springBootSources
        }
      }
    }
  }

  protected void writeWebAppClassPath(json, WebAppConfig webAppConfig) {
    def classPath = classPathResolver.resolveWebAppClassPath(webAppConfig)
    if(classPath)
      json.webappClassPath classPath
  }
}
