/*
 * Copyright (C) 2007-2023 Crafter Software Corporation. All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package upgrade

import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.io.FileUtils

@Grapes([
        @Grab(group = 'org.slf4j', module = 'slf4j-nop', version = '1.7.25'),
        @Grab(group = 'org.apache.commons', module = 'commons-lang3', version = '3.7'),
        @Grab(group = 'org.apache.commons', module = 'commons-collections4', version = '4.1'),
        @Grab(group = 'commons-codec', module = 'commons-codec', version = '1.11'),
        @Grab(group = 'commons-io', module = 'commons-io', version = '2.6'),
        @Grab(group='org.elasticsearch.client', module='elasticsearch-rest-high-level-client', version='7.10.0')
])

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.time.temporal.ChronoUnit
import java.util.stream.Stream
import java.time.ZonedDateTime

import static upgrade.utils.UpgradeUtils.*
import static utils.EnvironmentUtils.*
import static utils.ScriptUtils.*
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.cluster.health.*
import org.elasticsearch.client.*
import org.elasticsearch.client.tasks.*
import org.elasticsearch.cluster.health.ClusterHealthStatus
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.index.reindex.BulkByScrollResponse
import org.elasticsearch.common.unit.TimeValue
import java.util.concurrent.TimeUnit

import org.apache.http.HttpHost
import org.apache.http.client.CredentialsProvider
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.auth.AuthScope
import org.apache.commons.lang3.StringUtils

ES_VERSION = '7.10.0'
ES_DOWNLOAD_URL_BASE = 'https://artifacts.elastic.co/downloads/elasticsearch'
ES_URL_BASE = 'http://localhost'
ES_LOG_FILE = 'update-es.log'
REINDEXED = '_reindexed_710'

/**
 * Builds the CLI and adds the possible options
 */
def static buildCli(CliBuilder cli) {
    cli.setStopAtNonOption(false)
    cli.h(longOpt: 'help', 'Show usage information')
    cli._(longOpt: 'port', args: 1, argName: 'port', defaultValue: '9201', 'Elasticsearch port to use for upgrade ES temporary instance')
    cli._(longOpt: 'status-retries', args: 1, argName: 'max status retries', defaultValue: '5', type: Integer.class, 'How many times to try to get a yellow status from the ES cluster (waiting 10s between retries)')
    cli._(longOpt: 'status-timeout', args: 1, argName: 'seconds', defaultValue: 60, type: Integer.class, 'Timeout in seconds for the status check of the ES cluster')
    cli._(longOpt: 'stay-alive', argName: 'stayAlive', defaultValue: false, 'Set to true to keep the process alive after reindexing is complete. This allows to query the ES server and review.')
}

/**
 * Prints the help info
 */
def static printHelp(CliBuilder cli) {
    cli.usage()
}

/**
 * Exits the script with an error message, the usage and an error status.
 */
def exitWithError(CliBuilder cli, String msg) {
    println msg
    println ''

    printHelp(cli)

    System.exit(1)
}

/**
 * Downloads and extracts the ES bundle
 */
def downloadES(Path esFileName) {
    String esDownloadUrl = "$ES_DOWNLOAD_URL_BASE/$esFileName"
    println "Downloading Elasticsearch 7 from ${esDownloadUrl}"

    def upgradeTmpFolder = getUpgradeTmpFolder()
    if (Files.exists(upgradeTmpFolder)) {
        FileUtils.deleteDirectory(upgradeTmpFolder.toFile())
        Files.createDirectory(upgradeTmpFolder)
    } else {
        Files.createDirectories(upgradeTmpFolder)
    }

    Path esBundleFile = getUpgradeTmpFolder().resolve(esFileName)
    esDownloadUrl.toURL().withInputStream { is ->
        Files.copy(is, esBundleFile)
    }

    println "Extracting ES tar file"
    executeCommand(["tar", "xzf", esBundleFile.toAbsolutePath().toString()], upgradeTmpFolder)
}

/**
 * Starts the ES7 server with the existing config and indices data
 */
Process startES7(Path targetFolder, Map optionValues) {
    Path upgradeTmpFolder = getUpgradeTmpFolder()
    Closure setupCallback = { pb ->
        Map environment = pb.environment()
        environment.put('CRAFTER_BIN_DIR', targetFolder.resolve("bin").toString())
        environment.put('CRAFTER_HOME', targetFolder.toString())
        environment.put('ES_PATH_CONF', "$targetFolder/bin/elasticsearch/config".toString())
        environment.put('ES_PORT', optionValues.port)
        pb.redirectOutput(upgradeTmpFolder.resolve(ES_LOG_FILE).toFile())
    }

    // Run ES 7
    String esExecutablePath = upgradeTmpFolder.resolve("elasticsearch-$ES_VERSION/bin/elasticsearch")
    String targetCrafterSetEnv = targetFolder.resolve('bin/crafter-setenv.sh')
    return executeCommand(['bash', '-c', ". $targetCrafterSetEnv && $esExecutablePath".toString()], targetFolder, setupCallback, [0], false)
}

/**
 * Waits for ES status to be yellow
 */
boolean waitForES(RestHighLevelClient esClient, Map optionValues) {
    ClusterHealthStatus expectedStatus = ClusterHealthStatus.YELLOW
    int timeoutSeconds = optionValues['status-timeout']
    int maxRetries = optionValues['status-retries']
    while ((maxRetries--) > 0) {
        println "Check ES cluster status"
        try {
            ClusterHealthResponse healthResponse = esClient.cluster()
                    .health(new ClusterHealthRequest()
                            .waitForStatus(expectedStatus)
                            .timeout(new TimeValue(timeoutSeconds, TimeUnit.SECONDS)),
                    RequestOptions.DEFAULT)

            if (expectedStatus.equals(healthResponse.getStatus())) {
                println "ES cluster ready, status is ${healthResponse.getStatus()}"
                return true
            }
            println "ES cluster not ready, status is ${healthResponse.getStatus()}"
        } catch (Exception e) {
            println "Failed to check cluster status: ${e.getMessage()}"
        }
        if (maxRetries <= 0) {
            println "ES not ready. Max number of retries reached."
            return false
        }
        println "ES not ready yet, will retry after 5s"
        sleep(TimeUnit.SECONDS.toMillis(5))
    }

    return false
}

String getESUrl(Map optionValues) {
    "${ES_URL_BASE}:${optionValues.port}"
}

RestHighLevelClient createESClient(Map optionValues, int connectTimeout = 0, int socketTimeout = 0) {
    String[] serverUrls = [getESUrl(optionValues)]
    HttpHost[] hosts = Stream.of(serverUrls).map(HttpHost::create).toArray(HttpHost[]::new)
    RestClientBuilder clientBuilder = RestClient.builder(hosts)
    RestClientBuilder.HttpClientConfigCallback httpClientConfigCallback = builder -> {
        return builder
    }

    clientBuilder.setHttpClientConfigCallback(httpClientConfigCallback)
    return new RestHighLevelClient(clientBuilder)
}

def submitReindexAndWait(RestHighLevelClient client, String indexName, String newIndexName) {
    TaskSubmissionResponse reindexTask = client.submitReindexTask(
            new ReindexRequest().setSourceIndices(indexName).setDestIndex(newIndexName).setRefresh(true),
            RequestOptions.DEFAULT)
    TaskId taskId = new TaskId(reindexTask.getTask())
    GetTaskRequest getTaskRequest = new GetTaskRequest(taskId.getNodeId(), taskId.getId())

    long waitPeriod = TimeUnit.SECONDS.toMillis(2)
    long maxWaitPeriodMillis = TimeUnit.MINUTES.toMillis(1)
    do {
        sleep(waitPeriod)
        GetTaskResponse taskStatus = client.tasks()
                .get(getTaskRequest, RequestOptions.DEFAULT)
                .orElseThrow(() -> new IllegalStateException("Failed to retrieve task with id=$taskId"))
        if (taskStatus.isCompleted()) {
            return
        }
        println "Waiting for reindex task for index '$indexName' to be completed"
        waitPeriod = Math.min(waitPeriod * 2, maxWaitPeriodMillis)
    } while (true)
}

def reindex(RestHighLevelClient client, String alias, String indexName) {
    println "Reindexing index '${indexName}'"

    if (indexName.contains(REINDEXED)) {
        println "Index '${indexName}' already upgraded. Skipping..."
        return
    }

    String[] tokens = indexName.split("_v")
    if (tokens.length != 2) {
        println "Could not find current version for index: ${indexName}. Skipping..."
        return
    }
    int currentVersion = Integer.parseInt(tokens[1])

    // create a new index
    String newVersion = "_v" + (currentVersion + 1)
    String newIndexName = "${alias}${REINDEXED}${newVersion}"
    println "Using new version ${newVersion} for index ${indexName}. Alias '${alias}'"

    // Reindex
    println "Reindex '${indexName}' -> '${newIndexName}'"
    submitReindexAndWait(client, indexName, newIndexName)

    // Delete old
    println "Delete old index '${indexName}'"
    ActionResponse deleteResponse =  client.indices().delete(
            new DeleteIndexRequest(indexName),
            RequestOptions.DEFAULT)

    // Create alias
    println "Create alias '${alias} for index '${newIndexName}'"
    ActionResponse aliasResponse = client.indices().updateAliases(new IndicesAliasesRequest()
            .addAliasAction(
                    new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                            .index(newIndexName)
                            .alias(alias)
            ), RequestOptions.DEFAULT)
}

/**
 * Reindex all existing indices
 */
def reindexAll(esClient, optionValues) {
    String esUrl = "${getESUrl(optionValues)}/_cat/aliases?h=alias,index"
    def indices = esUrl.toURL().readLines()
    println "Prepare to reindex ${indices.size()} indices"
    indices.each { aliasIndexString ->
        String[] aliasIndex = aliasIndexString.split('\\s+')
        reindex(esClient, aliasIndex[0], aliasIndex[1])
    }
    println "All indices reindex complete"
}

/**
 * Executes the upgrade.
 */
def upgradeSearch(Path targetFolder, Map optionValues) {
    println "========================================================================"
    println "Search upgrade started"
    println "========================================================================"
    String fileName = "elasticsearch-${ES_VERSION}-linux-x86_64.tar.gz"
    downloadES(Paths.get(fileName))
    Process esProcess = null
    try {
        esProcess = startES7(targetFolder, optionValues)

        println "Elasticsearch is starting. To follow the log, you can run:"
        println "tail -F ${upgradeTmpFolder.resolve(ES_LOG_FILE).toFile().getCanonicalPath()}"

        println "Create ElasticSearch client"
        RestHighLevelClient esClient = createESClient(optionValues)
        if (waitForES(esClient, optionValues)) {
            println "ES cluster started. Preparing to reindex"
            ZonedDateTime start = ZonedDateTime.now()
            reindexAll(esClient, optionValues)
            ZonedDateTime end = ZonedDateTime.now()
            double durationSeconds = start.until(end, ChronoUnit.MILLIS) / 1000.0
            println "Reindex finished in ${String.format("%.3f", durationSeconds)} seconds"
        } else {
            println "ES cluster did not start properly. Review configs and start timeout"
        }
    } finally {
        if (esProcess && esProcess.isAlive()) {
            if (optionValues['stay-alive']) {
                println "'stay-alive' flag on, hit enter to stop Elasticsearch"
                System.console().readLine '>'
            }
            println "End process. Stop Elasticsearch"
            esProcess.destroy()
            esProcess.waitFor()
        }
    }

    // If indexes dir is not set in this environment, use the default one
    if (getEnv('ES_INDEXES_DIR') == null) {
        println "Move indexes from 'data/indexes-es' to 'indexes'"
        Path defaultSearchIndexesDir = targetFolder.resolve('data/indexes-es')
        Files.move(defaultSearchIndexesDir, defaultSearchIndexesDir.resolveSibling('indexes'))
    }
    println "========================================================================"
    println "Search upgrade completed"
    println "========================================================================"
}

checkDownloadGrapesOnlyMode(getClass())

CliBuilder cli = new CliBuilder(usage: 'upgrade-search [options] <target-installation-path>')
buildCli(cli)

def options = cli.parse(args)
if (options) {
    // Show usage text when -h or --help option is used.
    if (options.help) {
        printHelp(cli)
        return
    }

    def typedOptions = cli.getSavedTypeOptions()

    Map optionValues = typedOptions.values()
        .collectEntries {
            [(it.longOpt): (options.hasOption(it) ? options.getProperty(it.longOpt) : it.defaultValue())]
        }

    // Parse the options and arguments
    def extraArguments = options.arguments()
    if (CollectionUtils.isNotEmpty(extraArguments)) {
        def targetPath = extraArguments[0]
        def targetFolder = Paths.get(targetPath)

        upgradeSearch(targetFolder, optionValues)

    } else {
        exitWithError(cli, 'No <target-installation-path> was specified')
    }
    System.exit(0)
}
