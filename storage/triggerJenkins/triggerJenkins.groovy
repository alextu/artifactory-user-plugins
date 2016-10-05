/*
 * Copyright (C) 2014 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.transform.Field
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.HttpResponseException
import static groovyx.net.http.Method.*
import static groovyx.net.http.ContentType.URLENC
@Grapes([
    @Grab(group = 'org.codehaus.groovy.modules.http-builder',
          module = 'http-builder', version = '0.6')
])
@GrabExclude('commons-codec:commons-codec')
import groovyx.net.http.Method

@Field final List REPOS = [ 'libs-release-local' ]

storage {

    afterCreate { item ->
        String repoKey = item.repoPath.repoKey
        log.warn "will try to trigger jenkins job"
        if (repoKey in REPOS) {
            log.warn "will try to trigger jenkins job $repoKey"
            triggerJenkins repoKey
        }
    }

}

def triggerJenkins(String repoKey) {

    def http = new HTTPBuilder("http://localhost:8080/jenkins")

    def jobName = repoKey + 'Job'
    def parameters= [ json : '{"parameter": [{"name":"myparam", "value":"0"}, {"name":"myparam2", "value":"9"}]}']
    http.request(POST) { 
        uri.path = "/job/$jobName/build"
        send URLENC, parameters

        response.success = { resp, reader ->
            log.warn "Successfully triggered Jenkins build"
        }

        response.failure = { resp, reader ->    
            if (resp.statusLine.statusCode == 401) {
                log.error "401 Unauthorized, Username or password are wrong $reader"
            } else {
                log.error "Error $resp.statusLine.statusCode , Jenkins job failed to trigger $reader"
            }
        }
    }

}

