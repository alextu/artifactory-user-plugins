import groovy.transform.EqualsAndHashCode
import groovy.transform.Field
import org.apache.commons.codec.digest.DigestUtils
import org.artifactory.api.context.ContextHelper
import org.artifactory.resource.ResourceStreamHandle
import org.artifactory.storage.db.servers.service.ArtifactoryServersCommonService
import org.slf4j.Logger

@Field
Bucket bucket = new Bucket(ContextHelper.get().beanForType(ArtifactoryServersCommonService), log)
bucket.loadLicensesFromEnv(System.getenv('ART_LICENSES'))

executions {
    // See how we can secure the call, maybe pass a token
    getLicense() { params ->
        String nodeId = params['nodeId'] ? params['nodeId'][0] as String : ''
        String license = getLicenseFromBucket(nodeId)
        if (license) {
            message = license
            status = 200
        } else {
            status = 404
        }
    }

    importLicense(httpMethod: 'POST') { params, ResourceStreamHandle body ->
        String licenseKey = body.inputStream.getText('UTF-8')
        bucket.loadLicense(licenseKey)
    }
}

jobs {
    clean(cron: "1/30 * * * * ?") {
        def artifactoryServersCommonService = ContextHelper.get().beanForType(ArtifactoryServersCommonService)
        new ArtifactoryInactiveServersCleaner(artifactoryServersCommonService, log).cleanInactiveArtifactoryServers()
    }
}

String getLicenseFromBucket(String nodeId) {
    bucket.getLicenseKey(nodeId)
}

@EqualsAndHashCode(includes = 'keyHash')
public class License {
    String keyHash
    String key
}

public class Bucket {

    Set<License> licenses = new HashSet<License>()
    private ArtifactoryServersCommonService artifactoryServersCommonService
    private Logger log

    public Bucket(ArtifactoryServersCommonService artifactoryServersCommonService, Logger log) {
        this.artifactoryServersCommonService = artifactoryServersCommonService
        this.log = log
    }

    void loadLicensesFromEnv(String licensesConcatenated) {
        String[] licenseKeys = licensesConcatenated?.split(',')
        for (String licenseKey : licenseKeys) {
            licenses << createLicense(licenseKey)
        }
        log.warn "${licenses.size()} licenses for secondary nodes loaded"
    }

    void loadLicense(String licenseKey) {
        licenses << createLicense(licenseKey)
    }

    private License createLicense(String licenseKey) {
        def hash = hashLicenseKey(licenseKey)
        log.warn "Importing license with hash : $hash"
        return new License(keyHash: hash, key: licenseKey)
    }

    private String hashLicenseKey(String licenseKey) {
        DigestUtils.sha1Hex(licenseKey.trim())
    }

    String getLicenseKey(String nodeId) {
        log.warn "Node $nodeId is requesting a license from the primary"
        List<String> activeMemberLicenses = artifactoryServersCommonService.getOtherActiveMembers().collect({ it.licenseKeyHash[0..-2] })
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

    private ArtifactoryServersCommonService artifactoryServersCommonService
    private Logger log

    ArtifactoryInactiveServersCleaner(ArtifactoryServersCommonService artifactoryServersCommonService, Logger log) {
        this.artifactoryServersCommonService = artifactoryServersCommonService
        this.log = log
    }

    List<String> cleanInactiveArtifactoryServers() {
        List<String> allMembers = artifactoryServersCommonService.getAllArtifactoryServers().collect({ it.serverId })
        List<String> activeMembersIds = artifactoryServersCommonService.getOtherActiveMembers().collect({ it.serverId })
        String primaryId = artifactoryServersCommonService.getRunningHaPrimary().serverId
        List<String> inactiveMembers = allMembers - activeMembersIds - primaryId
        log.warn "Running inactive artifactory servers cleaning task, found ${inactiveMembers.size()} inactive servers to remove"
        for (String inactiveMember : inactiveMembers) {
            artifactoryServersCommonService.removeServer(inactiveMember)
        }
        return inactiveMembers
    }

}