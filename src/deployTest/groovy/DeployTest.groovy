import groovy.util.logging.Slf4j
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Title

@Slf4j
@Title("Check basic configuration")
class DeployTest extends Specification {

   @Shared
   File projectDir, buildDir, resourcesDir, settingsFile, artifact, buildFile

   @Shared
   def result, tasks

   @Shared
   String projectName = 'simple-deploy', taskName, restUrl = System.getProperty('restUrl')

   def setup() {

      copySource()
   }

   def copySource() {

      new AntBuilder().copy(todir: projectDir) {
         fileset(dir: resourcesDir)
      }
   }

   def setupSpec() {

      projectDir = new File("${System.getProperty("projectDir")}/$projectName")
      buildDir = new File(projectDir, 'build')
      artifact = new File(buildDir, 'distributions/simple-deploy-pipeline.zip')

      resourcesDir = new File('src/test/resources')

      copySource()

      settingsFile = new File(projectDir, 'settings.gradle').write("""rootProject.name = '$projectName'""")

      buildFile = new File(projectDir, 'build.gradle')

      buildFile.write("""
               |plugins {
               |  id 'com.redpillanalytics.gradle-confluent'
               |  id "com.redpillanalytics.gradle-analytics" version "1.2.3"
               |  id 'maven-publish'
               |}
               |
               |publishing {
               |  repositories {
               |    mavenLocal()
               |  }
               |}
               |group = 'com.redpillanalytics'
               |version = '1.0.0'
               |
               |repositories {
               |  jcenter()
               |  mavenLocal()
               |  maven {
               |     name 'test'
               |     url 's3://maven.redpillanalytics.com/demo/maven2'
               |     authentication {
               |        awsIm(AwsImAuthentication)
               |     }
               |  }
               |}
               |
               |dependencies {
               |   archives group: 'com.redpillanalytics', name: 'simple-build', version: '+'
               |   archives group: 'com.redpillanalytics', name: 'simple-build-pipeline', version: '+'
               |}
               |
               |confluent.pipelineEndpoint = '${restUrl}'
               |confluent.functionPattern = 'simple-build'
               |analytics.sinks {
               |   kafka
               |}
               |""".stripMargin())
   }

   // helper method
   def executeSingleTask(String taskName, List otherArgs, Boolean logOutput = true) {

      otherArgs.add(0, taskName)

      log.warn "runner arguments: ${otherArgs.toString()}"

      // execute the Gradle test build
      result = GradleRunner.create()
              .withProjectDir(projectDir)
              .withArguments(otherArgs)
              .withPluginClasspath()
              .build()

      // log the results
      if (logOutput) log.warn result.getOutput()

      return result

   }

   def "Deploy test from Maven S3"() {

      given:
      taskName = 'deploy'
      result = executeSingleTask(taskName, ['-Si'])

      tasks = result.output.readLines().grep(~/(> Task :)(.+)/).collect {
         it.replaceAll(/(> Task :)(\w+)( UP-TO-DATE)*/, '$2')
      }

      log.warn result.getOutput()

      expect:
      result.task(":${taskName}").outcome.name() != 'FAILED'
      tasks.collect { it - ' SKIPPED' } == ['functionCopy', 'pipelineExtract', 'pipelineDeploy', 'deploy']
   }

   def "Producer test to Kafka"() {

      given:
      taskName = 'producer'
      result = executeSingleTask(taskName, ['-Si'])

      tasks = result.output.readLines().grep(~/(> Task :)(.+)/).collect {
         it.replaceAll(/(> Task :)(\w+)( UP-TO-DATE)*/, '$2')
      }

      log.warn result.getOutput()

      expect:
      result.task(":${taskName}").outcome.name() != 'FAILED'
      tasks.collect { it - ' SKIPPED' } == ['kafkaSink','producer']
   }
}
