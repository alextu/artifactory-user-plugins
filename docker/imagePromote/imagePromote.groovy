import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.artifactory.addon.docker.rest.DockerResource
import org.artifactory.api.context.ContextHelper
import org.artifactory.build.ReleaseStatus
import org.artifactory.exception.CancelException
import org.artifactory.repo.RepoPathFactory
import org.jfrog.repomd.docker.model.DockerPromotion
import static com.google.common.collect.Multimaps.forMap

promotions {
    promoteDocker(
            users: ['admin'],
            params: [targetRepository: 'Repository to move the Docker image to',
                     status: 'Status of the build (default : Release)',
                     comment: 'Comment added to the build (default: Promoting docker build)']) { buildName, buildNumber, params ->
        log.warn("params: $params")
        log.warn "promoting $buildName, $buildNumber"
        def results = searches.itemsByProperties(forMap(['build.name': buildName, 'build.number': buildNumber]))
        if (!results) {
            throw new CancelException("No docker image found with properties : build.name=$buildName, build.number=$buildNumber", 500)
        }

        def (String imageName, String imageTag) = results[0].path.split('/')
        String targetRepository = paramWithDefault(params, 'targetRepository', 'docker-prod-local')
        String status = paramWithDefault(params, 'status', 'Released')
        String comment = paramWithDefault(params, 'comment', 'Promoting Docker build')
        log.warn "Will try to promote docker image with : " +
                "targetRepository : $targetRepository, imageName : $imageName, imageTag : $imageTag"

        promoteDockerImage(targetRepository, imageName, imageTag)
        handleLatestTag(targetRepository, imageName, imageTag)
        promoteBuild(buildName, buildNumber, status, comment, targetRepository)

        message = " Build $buildName/$buildNumber has been successfully promoted"
        status = 200
    }
}

private String paramWithDefault(def params, String key, String defaultValue) {
    return params[key] ? (params[key])[0] : defaultValue
}

private void promoteBuild(String buildName, String buildNumber, String status, String comment, String targetRepository) {
    def buildRun = builds.getBuilds(buildName, buildNumber, null)[0]
    def build = builds.getDetailedBuild(buildRun)
    def statuses = build.releaseStatuses
//        log.warn "Ci user ${(params['ciUser'])[0]}"
    statuses << new ReleaseStatus(status, comment, targetRepository, null, security.currentUsername)
    builds.saveBuild(build)
}

private void promoteDockerImage(String targetRepository, imageName, imageTag) {
    DockerResource dockerResource = ContextHelper.get().beanForType(DockerResource)
    DockerPromotion dockerPromotion =
            new DockerPromotion(targetRepo: targetRepository, dockerRepository: imageName, tag: imageTag, copy: false)
    dockerResource.promoteV2('docker-dev-local', dockerPromotion)
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
    Map manifestJson = new JsonSlurper().parseText(manifest)
    manifestJson['tag'] = "$latest"
    String json = JsonOutput.toJson(manifestJson)
    json = JsonOutput.prettyPrint(json)
    repositories.deploy(manifestPath, new ByteArrayInputStream(json.getBytes()))
}