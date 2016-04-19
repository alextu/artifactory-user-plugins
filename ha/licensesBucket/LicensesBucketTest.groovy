import org.artifactory.storage.db.servers.service.ArtifactoryServersCommonService
import spock.lang.Specification

class LicensesBucketTest extends Specification {

    Bucket bucket

    def 'initial ha setup : loading licenses from env with no existing servers'() {
        setup:
        ArtifactoryServersCommonService serversCommonService = [ getAllArtifactoryServers : { [] }] as ArtifactoryServersCommonService
        bucket = new Bucket(serversCommonService)
        String licences = 'somelicence1,somelicence2'

        when:
        bucket.loadLicensesFromEnv(licences)

        then:
        bucket.licenses.size() == 2
        bucket.licenses.contains(new License(keyHash: '3e0a45254a3f8f46fba2da4e07558a1cbdca308d3'))
        bucket.licenses.contains(new License(keyHash: '97e0f207825d3c9ad0b38d170ff0735904375a833'))
    }



}