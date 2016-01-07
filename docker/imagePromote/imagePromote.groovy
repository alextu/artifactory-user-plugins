import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7' )
import groovyx.net.http.HTTPBuilder
import org.artifactory.build.ReleaseStatus
import org.artifactory.exception.CancelException
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory

import static com.google.common.collect.Multimaps.forMap
import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.ContentType.TEXT
import static groovyx.net.http.Method.POST

@Field
def artifactoryBaseUrl = 'http://localhost:8088/artifactory'
@Field
def restrictedUsers = ['admin', 'devs']

promotions {
    promoteDocker(
            users: restrictedUsers,
            params: [targetRepository: 'Repository to move the Docker image to (default : docker-prod-local)',
                     status: 'Status of the build (default : Release)',
                     comment: 'Comment added to the build (default: Promoting docker build)']) { buildName, buildNumber, params ->
        log.warn("params: $params")
        log.warn "promoting $buildName, $buildNumber"
        List<RepoPath> results = searches.itemsByProperties(forMap(['build.name': buildName, 'build.number': buildNumber]))
        if (!results) {
            throw new CancelException("No docker image found with properties : build.name=$buildName, build.number=$buildNumber", 500)
        }
        String sourceRepository = results[0].getRepoKey()
        def (String imageName, String imageTag) = results[0].path.split('/')
        String targetRepository = paramWithDefault(params, 'targetRepository', 'docker-prod-local')
        String status = paramWithDefault(params, 'status', 'Released')
        String comment = paramWithDefault(params, 'comment', 'Promoting Docker build')
        String ciUser = paramWithDefault(params, 'ciUser', 'jenkins')
        log.warn "Will try to promote docker image with : " +
                "sourceRepository : $sourceRepository, " +
                "targetRepository : $targetRepository, " +
                "imageName : $imageName, imageTag : $imageTag"

        promoteDockerImage(sourceRepository, targetRepository, imageName, imageTag)
        handleLatestTag(targetRepository, imageName, imageTag)
        promoteBuild(buildName, buildNumber, status, comment, ciUser, targetRepository)

        message = " Build $buildName/$buildNumber has been successfully promoted"
        status = 200
    }
}

private String paramWithDefault(Map<String, List> params, String key, String defaultValue) {
    return params[key] ? (params[key])[0] : defaultValue
}

private void promoteBuild(String buildName, String buildNumber, String status, String comment,
                          String ciUser, String targetRepository) {
    def buildRun = builds.getBuilds(buildName, buildNumber, null)[0]
    def build = builds.getDetailedBuild(buildRun)
    def statuses = build.releaseStatuses
    statuses << new ReleaseStatus(status, comment, targetRepository, ciUser, security.currentUsername)
    builds.saveBuild(build)
}

private void promoteDockerImage(String sourceRepository, String targetRepository, imageName, imageTag) {
    def http = new HTTPBuilder("$artifactoryBaseUrl/api/docker/$sourceRepository/v2/promote")
    def (login, password) = currentUserCredentials()
    http.handler.failure = { resp ->
        log.warn "Unexpected failure while trying to promote image : ${resp.statusLine}"
        throw new CancelException("Artifactory couldn\'t promote image $imageName:$imageTag", 500)
    }
    log.warn "Trying to promote image with rest call"
    http.request(POST, TEXT) { req ->
        headers['Authorization'] = "Basic ${"$login:$password".bytes.encodeBase64().toString()}"
        requestContentType = JSON
        body = [targetRepo: targetRepository, dockerRepository: imageName, tag: imageTag, copy: false]
    }
    // TODO : delete promoted folder, it's still there after being promoted, maybe because it has properties ?
}

private void handleLatestTag(String repoKey, String imageName, String imageTag) {
    final def latest = 'latest'
    copyFolder(repoKey, imageName, imageTag, latest)
    updateManifest(repoKey, imageName, latest)
}

private void copyFolder(String repoKey, String imageName, String imageTag, String latest) {
    def origin = RepoPathFactory.create(repoKey, "$imageName/$imageTag")
    def target = RepoPathFactory.create(repoKey, "$imageName/$latest")
    if (repositories.exists(target)) {
        repositories.delete(target)
    }
    repositories.copy(origin, target)
}

private void updateManifest(String repoKey, String imageName, String latest) {
    def manifestPath = RepoPathFactory.create(repoKey, "$imageName/$latest/manifest.json")
    String manifest = repositories.getStringContent(manifestPath)
    if (!manifest) {
        throw new CancelException("Couldn\'t find $imageName/$latest/manifest.json", 500)
    }
    Map manifestJson = (Map)new JsonSlurper().parseText(manifest)
    manifestJson['tag'] = "$latest"
    String json = JsonOutput.toJson(manifestJson)
    json = JsonOutput.prettyPrint(json)
    repositories.deploy(manifestPath, new ByteArrayInputStream(json.getBytes()))
    // TODO : calculate sha2 to avoid an artifactory bug when displaying layers
}

private def currentUserCredentials() {
    def userName = security.getCurrentUsername()
    def password = security.getEncryptedPassword()
    return [userName, password]
}