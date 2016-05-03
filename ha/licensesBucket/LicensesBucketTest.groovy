import groovy.util.logging.Slf4j
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.artifactory.addon.AddonsManager
import org.artifactory.addon.ArtifactoryRunningMode
import org.artifactory.state.ArtifactoryServerState
import org.artifactory.storage.db.servers.model.ArtifactoryServer
import org.artifactory.storage.db.servers.model.ArtifactoryServerRole
import org.artifactory.storage.db.servers.service.ArtifactoryServersCommonService
import spock.lang.Specification

@Slf4j
class LicensesBucketTest extends Specification {

    Bucket bucket

    def 'test hashing'() {
        setup:
        ArtifactoryServersCommonService serversCommonService = [
                getAllArtifactoryServers : { [] }
        ] as ArtifactoryServersCommonService
        bucket = new Bucket(serversCommonService, log)

        when:
        String hash = bucket.licenseKeyHash('somelicense1')
        String hash2 = bucket.licenseKeyHash('somelicense2')
        println "somelicense2 : $hash2"

        then:
        hash == 'ef80a5ff1fa7bd35887674aa0f922c43ddff017c'
    }

    def 'loading licenses from env'() {
        setup:
        ArtifactoryServersCommonService serversCommonService = [
                getAllArtifactoryServers : { [] }
        ] as ArtifactoryServersCommonService
        bucket = new Bucket(serversCommonService, log)
        String licences = 'somelicense1,somelicense2'

        when:
        bucket.loadLicensesFromEnv(licences)

        then:
        bucket.licenses.size() == 2
        bucket.licenses.contains(new License(keyHash: 'ef80a5ff1fa7bd35887674aa0f922c43ddff017c'))
        bucket.licenses.contains(new License(keyHash:'24f17ca9f2aa245809d3262a045996e5db1d561f'))
    }

    def 'get license from bucket with no server started should return one license'() {
        setup:
        ArtifactoryServersCommonService serversCommonService = [
                getAllArtifactoryServers : { [] },
                getOtherActiveMembers : { [] }
        ] as ArtifactoryServersCommonService
        bucket = new Bucket(serversCommonService, log)
        String licences = 'somelicense1,somelicense2'
        bucket.loadLicensesFromEnv(licences)

        when:
        String license = bucket.getLicenseKey('art-2')

        then:
        license == 'somelicense1'
    }

    def 'get license from bucket with one server started should return the other license'() {
        setup:
        ArtifactoryServersCommonService serversCommonService = [
                getAllArtifactoryServers : { [] },
                getOtherActiveMembers : { [ createArtifactoryMemberServer('art-4', ArtifactoryServerState.RUNNING, '24f17ca9f2aa245809d3262a045996e5db1d561f1') ] }
        ] as ArtifactoryServersCommonService
        bucket = new Bucket(serversCommonService, log)
        String licences = 'somelicense1,somelicense2'
        bucket.loadLicensesFromEnv(licences)

        when:
        String license = bucket.getLicenseKey('art-2')

        then:
        license == 'somelicense1'
    }

    def 'get license from bucket with all servers started should return null'() {
        setup:
        ArtifactoryServersCommonService serversCommonService = [
                getAllArtifactoryServers : { [] },
                getOtherActiveMembers : { [ createArtifactoryMemberServer('art-4', ArtifactoryServerState.RUNNING, 'ef80a5ff1fa7bd35887674aa0f922c43ddff017c1'),
                                            createArtifactoryMemberServer('art-5', ArtifactoryServerState.RUNNING, '24f17ca9f2aa245809d3262a045996e5db1d561f1')    ] }
        ] as ArtifactoryServersCommonService
        bucket = new Bucket(serversCommonService, log)
        String licences = 'somelicense1,somelicense2'
        bucket.loadLicensesFromEnv(licences)

        when:
        String license = bucket.getLicenseKey('art-2')

        then:
        license == null
    }

    def 'cleaning servers should remove inactive servers'() {
        def art1 = createArtifactoryPrimaryServer('art-1', ArtifactoryServerState.RUNNING, 'something')
        def art4 = createArtifactoryMemberServer('art-4', ArtifactoryServerState.RUNNING, 'ef80a5ff1fa7bd35887674aa0f922c43ddff017c1')
        def art5 = createArtifactoryMemberServer('art-5', ArtifactoryServerState.STOPPED, '24f17ca9f2aa245809d3262a045996e5db1d561f1')

        setup:
        ArtifactoryServersCommonService serversCommonService = [
                getAllArtifactoryServers : { [ art1, art4, art5] },
                getOtherActiveMembers : { [ art4 ] },
                getRunningHaPrimary : { art1 },
                removeServer : { server -> true }
        ] as ArtifactoryServersCommonService
        ArtifactoryInactiveServersCleaner cleaner = new ArtifactoryInactiveServersCleaner(serversCommonService, log)

        when:
        def servers = cleaner.cleanInactiveArtifactoryServers()

        then:
        servers.size() == 1
        servers.first() == 'art-5'
    }

    private ArtifactoryServer createArtifactoryMemberServer(String serverId, ArtifactoryServerState state, String licenseHash) {
        new ArtifactoryServer(serverId, new Date().time, null, 0,  state, ArtifactoryServerRole.MEMBER, 0, null, 0, 0, ArtifactoryRunningMode.HA, licenseHash)
    }

    private ArtifactoryServer createArtifactoryPrimaryServer(String serverId, ArtifactoryServerState state, String licenseHash) {
        new ArtifactoryServer(serverId, new Date().time, null, 0,  state, ArtifactoryServerRole.PRIMARY, 0, null, 0, 0, ArtifactoryRunningMode.HA, licenseHash)
    }

}