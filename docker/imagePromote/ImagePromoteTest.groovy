import com.fasterxml.jackson.databind.ObjectMapper
import groovy.json.JsonSlurper
import groovyx.net.http.HttpResponseException
import org.codehaus.groovy.runtime.IOGroovyMethods
import org.jfrog.artifactory.client.Artifactory
import org.jfrog.artifactory.client.ArtifactoryRequest
import org.jfrog.artifactory.client.ItemHandle
import org.jfrog.artifactory.client.impl.ArtifactoryRequestImpl
import spock.lang.Specification

import static org.jfrog.artifactory.client.ArtifactoryClient.create

class ImagePromoteTest extends Specification {

    final String DOCKER_IMAGE = 'mybusybox/10'
    final String DOCKER_DEV = 'docker-dev-local'
    final String DOCKER_PROD = 'docker-prod-local'
    Artifactory artifactory
    final String artUrl = "http://localhost:8088/artifactory"
    final def repos = [DOCKER_DEV, DOCKER_PROD]
    final def BUILD_NAME = 'docker_jenkins_release'
    final def BUILD_NUMBER = 10

    def 'test promoting a docker image for the first time, specifying image name and tag'() {
        setup:
        artifactory = create(artUrl, "admin", "password")
        cleanFakeDockerRepos()
        cleanFakeBuildInfo()
        createFakeDockerRepos()
        deployFakeBuildInfo()
        deployFakeImage()

        when: "promoting docker image without existing latest tag"
        ArtifactoryRequest request = new ArtifactoryRequestImpl()
                .apiUrl("api/plugins/build/promote/promoteDocker/$BUILD_NAME/$BUILD_NUMBER")
                .addQueryParam("params", "status=released|comment=Promoting")
                .responseType(ArtifactoryRequest.ContentType.TEXT)
                .method(ArtifactoryRequest.Method.POST)
        artifactory.restCall(request)

        then: "the image should have moved from docker-dev-local to docker-prod-local"
        assert !fileExists(DOCKER_DEV, "$DOCKER_IMAGE/manifest.json")
        assert fileExists(DOCKER_PROD, "$DOCKER_IMAGE/manifest.json")

        then: "the latest tag should have been created"
        assert fileExists(DOCKER_PROD, "mybusybox/latest/manifest.json")

        then: "the manifest.json file should contains the latest tag"
        InputStream inputStream = artifactory.repository(DOCKER_PROD).download('mybusybox/latest/manifest.json').doDownload()
        String manifest = IOGroovyMethods.getText(inputStream)
        def manifestJson = new JsonSlurper().parseText(manifest)
        assert manifestJson['tag'] == 'latest'
    }

    def 'test promoting a docker image for the first time, without specifying image name and tag'() {

    }


    def 'test promoting a docker image for the second time'() {
        // TODO : create a mybusybox:latest in the docker-prod-local

    }

    private boolean fileExists(String repoKey, String path) {
        boolean exists = true
        try {
            artifactory.repository(repoKey).file(path).info()
        } catch (HttpResponseException e) {
            exists = false
        }
        return exists
    }

    private def cleanFakeBuildInfo() {
        try {
            ArtifactoryRequest deleteBuild = new ArtifactoryRequestImpl()
                    .apiUrl("api/build/$BUILD_NAME")
                    .method(ArtifactoryRequest.Method.DELETE)
                    .responseType(ArtifactoryRequest.ContentType.TEXT)
                    .addQueryParam("buildNumbers", BUILD_NUMBER as String)
                    .addQueryParam("deleteAll", "1")
                    .addQueryParam("artifacts", "1");
            artifactory.restCall(deleteBuild);
        } catch (Exception e) {
            e.printStackTrace()
        }
    }

    private def deployFakeBuildInfo() {

        def buildInfoJSON = """
        {
            "version" : "1.0.1",
            "name" : "docker_jenkins_release",
            "number" : "10",
            "type" : "GENERIC",
            "buildAgent" : {
              "name" : "Generic",
              "version" : "Generic"
            },
            "agent" : {
              "name" : "hudson",
              "version" : "1.636"
            },
            "started" : "2015-12-22T16:50:06.774+0100",
            "durationMillis" : 7833,
            "principal" : "anonymous",
            "artifactoryPrincipal" : "devs",
            "artifactoryPluginVersion" : "2.4.6",
            "url" : "http://localhost:8080/job/docker_jenkins_release/7/",
            "vcsRevision" : "9128a309de963487f362ccffb45f894ba282a322",
            "licenseControl" : {
              "runChecks" : false,
              "includePublishedArtifacts" : false,
              "autoDiscover" : false,
              "licenseViolationsRecipientsList" : "",
              "scopesList" : ""
            },
            "buildRetention" : {
              "count" : -1,
              "deleteBuildArtifacts" : true,
              "buildNumbersNotToBeDiscarded" : [ ]
            },
            "modules" : [ {
              "id" : "docker_jenkins_release:7",
              "artifacts" : [ ],
              "dependencies" : [ ]
            } ],
            "governance" : {
              "blackDuckProperties" : {
                "runChecks" : false,
                "includePublishedArtifacts" : false,
                "autoCreateMissingComponentRequests" : false,
                "autoDiscardStaleComponentRequests" : false
              }
            }
        }

        """
        ObjectMapper mapper = new ObjectMapper();
        def buildInfo = mapper.readValue(buildInfoJSON, Map.class);
        ArtifactoryRequest storageRequest = new ArtifactoryRequestImpl()
                .apiUrl("api/build")
                .responseType(ArtifactoryRequest.ContentType.JSON)
                .method(ArtifactoryRequest.Method.PUT)
                .requestType(ArtifactoryRequest.ContentType.JSON)
                .requestBody(buildInfo)
        artifactory.restCall(storageRequest)
    }

    private def deployFakeImage() {
        ItemHandle imageFolder = artifactory.repository(DOCKER_DEV).folder(DOCKER_IMAGE)
        imageFolder.create()
        imageFolder.properties().addProperty('build.name', "$BUILD_NAME").addProperty('build.number', '10').doSet(false)
        def manifestJsonContent = """
            {
               "schemaVersion": 1,
               "name": "mybusybox",
               "tag": "10",
               "architecture": "amd64",
               "fsLayers": [
                  {
                     "blobSum": "sha256:a3ed95caeb02ffe68cdd9fd84406680ae93d633cb16422d00e8a7c22955b46d4"
                  },
                  {
                     "blobSum": "sha256:a3ed95caeb02ffe68cdd9fd84406680ae93d633cb16422d00e8a7c22955b46d4"
                  },
                  {
                     "blobSum": "sha256:d7e8ec85c5abc60edf74bd4b8d68049350127e4102a084f22060f7321eac3586"
                  }
               ]
            }
        """
        def fileContent = new ByteArrayInputStream(manifestJsonContent.bytes)
        artifactory.repository(DOCKER_DEV).upload('mybusybox/10/manifest.json', fileContent).doUpload();
    }

    private def cleanFakeDockerRepos() {
       try {
           repos.each {
               artifactory?.repository(it)?.delete()
           }
       } catch (Exception e) {
           e.printStackTrace()
       }
    }

    private def createFakeDockerRepos() {
        repos.each {
            String jsonRepo = jsonDockerRepo(it)
            ObjectMapper mapper = new ObjectMapper();
            def repoInfo = mapper.readValue(jsonRepo, Map.class);
            ArtifactoryRequest request = new ArtifactoryRequestImpl()
                    .apiUrl("api/repositories/$it")
                    .responseType(ArtifactoryRequest.ContentType.TEXT)
                    .method(ArtifactoryRequest.Method.PUT)
                    .requestType(ArtifactoryRequest.ContentType.JSON)
                    .requestBody(repoInfo)
            artifactory.restCall(request)
        }
    }

    private String jsonDockerRepo(String repoKey) {
        return """
            {
              "key" : "$repoKey",
              "packageType" : "docker",
              "description" : "",
              "notes" : "",
              "includesPattern" : "**/*",
              "excludesPattern" : "",
              "repoLayoutRef" : "simple-default",
              "enableNuGetSupport" : false,
              "enableGemsSupport" : false,
              "enableNpmSupport" : false,
              "enableBowerSupport" : false,
              "enableDebianSupport" : false,
              "debianTrivialLayout" : false,
              "enablePypiSupport" : false,
              "enableDockerSupport" : true,
              "dockerApiVersion" : "V2",
              "forceDockerAuthentication" : false,
              "forceNugetAuthentication" : false,
              "enableVagrantSupport" : false,
              "enableGitLfsSupport" : false,
              "checksumPolicyType" : "client-checksums",
              "handleReleases" : true,
              "handleSnapshots" : true,
              "maxUniqueSnapshots" : 0,
              "snapshotVersionBehavior" : "unique",
              "suppressPomConsistencyChecks" : true,
              "blackedOut" : false,
              "propertySets" : [ "artifactory" ],
              "archiveBrowsingEnabled" : false,
              "calculateYumMetadata" : false,
              "yumRootDepth" : 0,
              "rclass" : "local"
            }
        """
    }


}