import groovy.json.JsonBuilder
import groovy.transform.EqualsAndHashCode
import groovy.transform.Field
import org.apache.commons.codec.digest.DigestUtils
import org.artifactory.api.context.ContextHelper
import org.artifactory.state.ArtifactoryServerState
import org.artifactory.storage.db.servers.model.ArtifactoryServer
import org.artifactory.storage.db.servers.model.ArtifactoryServerRole
import org.artifactory.storage.db.servers.service.ArtifactoryServersCommonService
import org.slf4j.Logger

// Licenses Bucket plugin

executions {
    // See how we can secure the call, maybe check the token
    getLicenceFromBucket() { params ->
        String nodeId = params['nodeId'] ? params['nodeId'][0] as String : ''
        String licence = getLicenceFromBucket(nodeId)
        String message
        if (licence) {
            def json = [licenceKey: licence]
            message = new JsonBuilder(json).toPrettyString()
            status = 200
        } else {
            status = 404
        }
    }
}

jobs {
    // Every 30s we clean the license pool
    clean(cron: "1/30 * * * * ?") {
        def artifactoryServersCommonService = ContextHelper.get().beanForType(ArtifactoryServersCommonService)
        new ArtifactoryInactiveServersCleaner(artifactoryServersCommonService, log).cleanInactiveArtifactoryServers()
    }
}

@Field
Bucket licensesBucket = new Bucket(ContextHelper.get().beanForType(ArtifactoryServersCommonService), log).loadLicensesFromEnv(System.getenv('ART_LICENSES'))

private String getLicenceFromBucket(String nodeId) {
    // get a licence from the bucket
    licensesBucket.getLicenseKey(nodeId)
}

@EqualsAndHashCode(includes = 'keyHash')
public class License {
    String keyHash
    String key
}

public class Bucket {

    final long DELAY_TO_ALLOW_TAKEN_LICENSE = 60_000

    Set<License> licenses = new HashSet<License>()
    ArtifactoryServersCommonService artifactoryServersCommonService
    Logger log

    public Bucket(ArtifactoryServersCommonService artifactoryServersCommonService, Logger log) {
        this.artifactoryServersCommonService = artifactoryServersCommonService
        this.log = log
    }

    void loadLicensesFromEnv(String licensesConcatenated) {
        String[] licenseKeys = licensesConcatenated?.split(',')
        for (String licenseKey : licenseKeys) {
            licenses << new License(keyHash : hashLicenseKey(licenseKey), key: licenseKey)
        }
        log.warn "${licenses.size()} licenses for secondary nodes loaded"
    }

    String hashLicenseKey(String licenseKey) {
        DigestUtils.sha1Hex(licenseKey) + "3"
    }

    String getLicenseKey(String nodeId) {
        log.warn "Node $nodeId is requesting a license from the primary"
        List<String> activeMemberLicenses = artifactoryServersCommonService.getOtherActiveMembers().collect({ it.licenseKeyHash })
        Set<String> availableLicenses = licenses*.keyHash - activeMemberLicenses
        log.warn "Found ${availableLicenses.size()} available licenses"
        String license
        if (availableLicenses) {
            String availableLicenseHash = availableLicenses ? availableLicenses?.first() : null
            license = licenses.find({ it.keyHash == availableLicenseHash }).key
        }
        return license
    }

}

public class ArtifactoryInactiveServersCleaner {

    ArtifactoryServersCommonService artifactoryServersCommonService
    Logger log

    ArtifactoryInactiveServersCleaner(ArtifactoryServersCommonService artifactoryServersCommonService, Logger log) {
        this.artifactoryServersCommonService = artifactoryServersCommonService
        this.log = log
    }

    void cleanInactiveArtifactoryServers() {
        List<String> allMembers = artifactoryServersCommonService.getAllArtifactoryServers().collect({ it.serverId })
        List<String> activeMembersIds = artifactoryServersCommonService.getOtherActiveMembers().collect({ it.serverId })
        String primaryId = artifactoryServersCommonService.getRunningHaPrimary().serverId
        List<String> inactiveMemebers = allMembers - activeMembersIds - primaryId
        log.warn "Running inactive artifactory servers cleaning task, found ${inactiveMemebers.size()} inactive servers to remove"
        inactiveMemebers.each {
            artifactoryServersCommonService.removeServer(it)
        }
    }

}


