import org.artifactory.storage.db.servers.service.ArtifactoryServersCommonService
import spock.lang.Specification

class LicensesBucketTest extends Specification {

    Bucket bucket

    def 'test loading licenses from env with no existing servers'() {
        setup:
        ArtifactoryServersCommonService serversCommonService = [ getAllArtifactoryServers : { [] }] as ArtifactoryServersCommonService
        bucket = new Bucket(serversCommonService)

        when:
        bucket.loadLicensesFromEnv()

        then:
        bucket.licenses.size() == 2
        bucket.licenses.contains(new License(keyHash: 'zzfzofjzof'))
        bucket.licenses.contains(new License(keyHash: 'zzfzofjzozzzzf'))
    }



}