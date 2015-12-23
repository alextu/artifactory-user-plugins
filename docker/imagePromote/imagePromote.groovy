import org.artifactory.build.*
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory

import static com.google.common.collect.Multimaps.forMap

@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7')

import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.POST
import static groovyx.net.http.ContentType.*

promotions {
    promoteDocker(users: "admin", params: [targetRepository: 'docker-prod-local', status: 'Released', comment: 'Promoting Docker build']) { buildName, buildNumber, params ->
        log.warn("params: $params")
        log.warn "promoting $buildName, $buildNumber"
//        def (name,version) = searches.itemsByProperties(forMap(['build.name': buildName,'build.number': buildNumber]))[0].path.split('/')
//        log.warn "found image $name/$version"
        def targetRepository = params['targetRepository'] ? params['targetRepository'][0] : 'docker-prod-local'
        def imageName = params['imageName'][0]
        def imageTag = params['imageTag'][0]

        log.warn "targetRepository : $targetRepository, imageName : $imageName, imageTag : $imageTag"

        // TODO : replace this by a call to the internal API ? or at least deduce artifactory URL from the incoming request
        def http = new HTTPBuilder('http://localhost:8088/artifactory/api/docker/docker-dev-local/v2/promote')

        http.request(POST, TEXT) { req ->
            headers.'Authorization' = "Basic ${"admin:password".bytes.encodeBase64().toString()}"
            requestContentType = JSON
            body = [targetRepo: targetRepository, dockerRepository: imageName, tag: imageTag, copy: false]
            response.success = { resp, json ->
//                def buildRun = builds.getBuilds(buildName, buildNumber, null)[0]
//                log.warn "found build $buildRun"
//                def build = builds.getDetailedBuild(buildRun)
//                log.warn "build $build"
//                def statuses = build.releaseStatuses
//                log.warn "current statuses $statuses"
//                log.warn "Ci user ${(params['ciUser'])[0]}"
//                statuses << new ReleaseStatus((params['status'])[0], (params['comment'])[0], (params['targetRepository'])[0], (params['ciUser'])[0], security.currentUsername)
//                log.warn "New statues ${statuses[0]}"
//                builds.saveBuild(build)
                handleLatestTag(targetRepository, imageName, imageTag)
                message = " Build $buildName/$buildNumber has been successfully promoted"
                status = 200
            }
        }
    }
}

def handleLatestTag(String repoKey, String imageName, String imageTag) {
    def origin = RepoPathFactory.create(repoKey, "$imageName/$imageTag")
    def target = RepoPathFactory.create(repoKey, "$imageName/latest")
    if (repositories.exists(target)) {
        repositories.delete(target)
    }
    repositories.copy(origin, target)
}