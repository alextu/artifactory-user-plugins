import groovy.json.JsonBuilder
import groovy.transform.EqualsAndHashCode
import groovy.transform.Field
import org.apache.commons.codec.digest.DigestUtils
import org.artifactory.api.context.ContextHelper
import org.artifactory.state.ArtifactoryServerState
import org.artifactory.storage.db.servers.model.ArtifactoryServer
import org.artifactory.storage.db.servers.model.ArtifactoryServerRole
import org.artifactory.storage.db.servers.service.ArtifactoryServersCommonService

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
        cleanBucket()
        // we must clean the servers
        // cleanArtifactoryServers()
    }
}

@Field
Bucket licensesBucket = new Bucket(ContextHelper.get().beanForType(ArtifactoryServersCommonService)).loadLicensesFromEnv(System.getenv('ART_LICENSES'))

private String getLicenceFromBucket(String nodeId) {
    // get a licence from the bucket
    licensesBucket.getLicenseKey(nodeId)
}

private void cleanBucket() {
    licensesBucket.clean()
}

enum State {
    AVAILABLE, TAKEN
}

@EqualsAndHashCode(includes = 'keyHash')
public class License {

    String keyHash
    String nodeId
    State state
    Date takenTimestamp

}

public class Bucket {

    final long DELAY_TO_ALLOW_TAKEN_LICENSE = 60_000

    Set<License> licenses = new HashSet<License>()
    ArtifactoryServersCommonService artifactoryServersCommonService

    public Bucket(ArtifactoryServersCommonService artifactoryServersCommonService) {
        this.artifactoryServersCommonService = artifactoryServersCommonService
    }

    void loadLicensesFromEnv(String licensesConcatenated) {
        // licences from Env, by default all available
        String[] licenseKeys = licensesConcatenated?.split(',')
        for (String licenseKey : licenseKeys) {
            licenses << new License(keyHash:hashLicenseKey(licenseKey), state: State.AVAILABLE)
        }
        // licenses from the artifactory servers infos, mark the ones taken, and the one eventually taken
        List<ArtifactoryServer> servers = artifactoryServersCommonService.getAllArtifactoryServers()
        for (ArtifactoryServer server : servers) {
            if (server.serverRole != ArtifactoryServerRole.PRIMARY) {
                // Looking for existing license from the registered servers
                License existing = licenses.find { it.keyHash == server.licenseKeyHash }
                if (existing) {
                    existing.nodeId = server.serverId
                    if (server.getServerState() == ArtifactoryServerState.RUNNING) {
                        existing.state = State.TAKEN
                    } else {
                        eventuallyTakeLicense(existing)
                    }
                }
            }
        }
    }

    String hashLicenseKey(String licenseKey) {
        DigestUtils.sha1Hex(licenseKey) + "3"
    }

    void eventuallyTakeLicense(License license) {
        license.state = State.TAKEN
        license.takenTimestamp = new Date()
    }

    String getLicenseKey(String nodeId) {
        License available = licenses.find { it.state == State.AVAILABLE }
        if (available) {
            eventuallyTakeLicense(available)
            return available.keyHash
        }
    }

    void clean() {
        // (Some locking expected)
        // We check that a license
        for (License license : licenses) {
            if (license.state == State.TAKEN) {
                // check that the server is still running
                // if yes, reset the takenTimestamp

                // if not, check the takenTimestamp + delay < now

                    // change the state to available and reset the takenTimestamp

            } else if (license.state == State.AVAILABLE) {
                // check that there's no running server corresponding to the license
                // if so change the state to TAKEN
            }
        }
    }

}