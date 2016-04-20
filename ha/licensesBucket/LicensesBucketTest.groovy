import groovy.util.logging.Slf4j
import org.artifactory.addon.ArtifactoryRunningMode
import org.artifactory.state.ArtifactoryServerState
import org.artifactory.storage.db.servers.model.ArtifactoryServer
import org.artifactory.storage.db.servers.model.ArtifactoryServerRole
import org.artifactory.storage.db.servers.service.ArtifactoryServersCommonService
import spock.lang.Specification

@Slf4j
class LicensesBucketTest extends Specification {

    Bucket bucket

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
        bucket.licenses.contains(new License(keyHash: 'dbc6a1c2114418d3b2af3fce71acfbc4c46edc523'))
        bucket.licenses.contains(new License(keyHash:'8fd780adb7f94db8f811f60e6aa9b9ebab0f529f3'))
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
                getOtherActiveMembers : { [ createArtifactoryMemberServer('art-4', ArtifactoryServerState.RUNNING, '8fd780adb7f94db8f811f60e6aa9b9ebab0f529f3') ] }
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
                getOtherActiveMembers : { [ createArtifactoryMemberServer('art-4', ArtifactoryServerState.RUNNING, 'dbc6a1c2114418d3b2af3fce71acfbc4c46edc523'),
                                            createArtifactoryMemberServer('art-5', ArtifactoryServerState.RUNNING, '8fd780adb7f94db8f811f60e6aa9b9ebab0f529f3')    ] }
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
        def art4 = createArtifactoryMemberServer('art-4', ArtifactoryServerState.RUNNING, 'dbc6a1c2114418d3b2af3fce71acfbc4c46edc523')
        def art5 = createArtifactoryMemberServer('art-5', ArtifactoryServerState.STOPPED, '8fd780adb7f94db8f811f60e6aa9b9ebab0f529f3')

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