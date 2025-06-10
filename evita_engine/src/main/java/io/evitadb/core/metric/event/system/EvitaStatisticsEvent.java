/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.metric.event.system;

import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.requestResponse.system.SystemStatus;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

import static java.util.Optional.ofNullable;

/**
 * Event that is fired when evitaDB instance is started.
 */
@Name(AbstractSystemEvent.PACKAGE_NAME + ".EvitaStatistics")
@Description("Event that is triggered when the evitaDB instance is started.")
@ExportInvocationMetric(label = "Evita started total")
@Label("Evita started")
@Getter
public class EvitaStatisticsEvent extends AbstractSystemCatalogEvent {

	@Label("Server version")
	@Description("Precise version of the evitaDB server.")
	@ExportMetricLabel
	private final String serverVersion;

	@Label("Server instance id")
	@Description("Unique server name taken from the configuration file.")
	@ExportMetricLabel
	private final String instanceId;

	@Label("Transaction flush frequency")
	@Description("Frequency of transaction flush in milliseconds.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long transactionFlushFrequencyInMillis;

	@Label("Close sessions after inactivity")
	@Description("Number of seconds after which the session is closed if it is inactive.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int closeSessionsAfterSecondsOfInactivity;

	@Label("Traffic recording enabled")
	@Description("Flag indicating whether the traffic recording is enabled.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int trafficRecordingEnabled;

	@Label("Time travel enabled")
	@Description("Flag indicating whether the time travel is enabled.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int timeTravelEnabled;

	@Label("Maximum number of threads to handle read-only requests")
	@Description("Configured threshold for the maximum number of threads to handle read-only requests (`server.requestThreadPool.maxThreadCount`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int requestMaxThreads;

	@Label("Maximum queue size for read-only request handling")
	@Description("Configured threshold for the maximum queue size for read-only request handling (`server.requestThreadPool.queueSize`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int requestMaxThreadsQueueSize;

	@Label("Maximum number of threads for read/write requests")
	@Description("Configured threshold for the maximum number of threads for read/write requests (`server.transactionThreadPool.maxThreadCount`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int transactionMaxThreads;

	@Label("Maximum queue size for read/write requests")
	@Description("Configured threshold for the maximum queue size for read/write requests (`server.transactionThreadPool.queueSize`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int transactionMaxThreadsQueueSize;

	@Label("Maximum number of threads for service tasks")
	@Description("Configured threshold for the maximum number of threads for service tasks (`server.serviceThreadPool.maxThreadCount`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int serviceMaxThreads;

	@Label("Maximum queue size for service tasks")
	@Description("Configured threshold for the maximum queue size for service tasks (`server.serviceThreadPool.queueSize`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int serviceMaxThreadsQueueSize;

	@Label("Read-only request timeout in seconds")
	@Description("Configured threshold for the read-only request timeout in seconds (`server.queryTimeoutInMilliseconds`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int queryTimeoutSeconds;

	@Label("Read/write request timeout in seconds")
	@Description("Configured threshold for the read/write request timeout in seconds (`server.transactionTimeoutInMilliseconds`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int transactionTimeoutSeconds;

	@Label("Maximum session inactivity time in seconds")
	@Description("Configured threshold for the maximum session inactivity time in seconds (`server.closeSessionsAfterSecondsOfInactivity`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int sessionMaxInactiveAgeSeconds;

	@Label("Maximum number of open read-only handles")
	@Description("Configured threshold for the maximum number of open read-only handles (`storage.maxOpenedReadHandles`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int readOnlyHandlesLimit;

	@Label("Minimum percentage of active records in the file to start compacting in %.")
	@Description("Configured threshold for the minimum percentage of active records in the file to start compacting in % (`storage.minimalActiveRecordShare`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int compactionMinimalActiveRecordSharePercent;

	@Label("Minimum file size threshold to start compress in bytes")
	@Description("Configured threshold for the minimum file size threshold to start compress in bytes (`storage.fileSizeCompactionThresholdBytes`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long compactionFileSizeThresholdBytes;

	@Label("Off-heap memory buffer size for transactions in Bytes")
	@Description("Configured threshold for the off-heap memory buffer size for transactions in Bytes (`transaction.transactionMemoryBufferLimitSizeBytes`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long transactionMemoryBufferLimitSizeBytes;

	@Label("Number of off-heap memory regions for transactions")
	@Description("Configured threshold for the number of off-heap memory regions for transactions (`transaction.transactionMemoryRegionCount`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int transactionMemoryRegions;

	@Label("Maximum write-ahead log file size in Bytes")
	@Description("Configured threshold for the maximum write-ahead log file size in Bytes (`transaction.walFileSizeBytes`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long walMaxFileSizeBytes;

	@Label("Maximum number of write-ahead log files to keep")
	@Description("Configured threshold for the maximum number of write-ahead log files to keep (`transaction.walFileCountKept`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int walMaxFileCountKept;

	@Label("Cache reevaluation interval in seconds")
	@Description("Configured threshold for the cache reevaluation interval in seconds (`cache.reevaluateEachSeconds`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int cacheReevaluationSeconds;

	@Label("Maximum number of records in the cache anteroom")
	@Description("Configured threshold for the maximum number of records in the cache anteroom (`cache.anteroomRecordCount`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int cacheAnteroomRecordLimit;

	@Label("Maximum cache size in Bytes")
	@Description("Configured threshold for the maximum cache size in Bytes (`cache.cacheSizeInBytes`).")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long cacheSizeInBytes;

	@Label("Catalog count")
	@Description("Number of accessible catalogs managed by this instance of evitaDB.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int catalogs;

	@Label("Corrupted catalog count")
	@Description("Number of corrupted catalogs that evitaDB could not load.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int corruptedCatalogs;

	@Label("Inactive catalog count")
	@Description("Number of inaccessible (not loaded to memory) catalogs present in storage directory of this instance of evitaDB.")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int inactiveCatalogs;

	public EvitaStatisticsEvent(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull SystemStatus systemStatus
	) {
		super(null);

		this.serverVersion = systemStatus.version();
		this.instanceId = systemStatus.instanceId();
		this.catalogs = systemStatus.catalogsActive();
		this.inactiveCatalogs = systemStatus.catalogsInactive();
		this.corruptedCatalogs = systemStatus.catalogsCorrupted();

		final ServerOptions serverConfiguration = configuration.server();
		this.requestMaxThreads = serverConfiguration.requestThreadPool().maxThreadCount();
		this.requestMaxThreadsQueueSize = serverConfiguration.requestThreadPool().queueSize();
		this.transactionMaxThreads = serverConfiguration.transactionThreadPool().maxThreadCount();
		this.transactionMaxThreadsQueueSize = serverConfiguration.transactionThreadPool().queueSize();
		this.serviceMaxThreads = serverConfiguration.serviceThreadPool().maxThreadCount();
		this.serviceMaxThreadsQueueSize = serverConfiguration.serviceThreadPool().queueSize();
		this.queryTimeoutSeconds = (int) (serverConfiguration.queryTimeoutInMilliseconds() / 1000L);
		this.transactionTimeoutSeconds = (int) (serverConfiguration.transactionTimeoutInMilliseconds() / 1000L);
		this.sessionMaxInactiveAgeSeconds = serverConfiguration.closeSessionsAfterSecondsOfInactivity();
		this.closeSessionsAfterSecondsOfInactivity = serverConfiguration.closeSessionsAfterSecondsOfInactivity();
		this.trafficRecordingEnabled = serverConfiguration.trafficRecording().enabled() ? 1 : 0;

		final StorageOptions storageConfiguration = configuration.storage();
		this.readOnlyHandlesLimit = storageConfiguration.maxOpenedReadHandles();
		this.compactionMinimalActiveRecordSharePercent = Math.toIntExact(Math.round(storageConfiguration.minimalActiveRecordShare() * 100.0));
		this.compactionFileSizeThresholdBytes = storageConfiguration.fileSizeCompactionThresholdBytes();
		this.timeTravelEnabled = storageConfiguration.timeTravelEnabled() ? 1 : 0;

		final TransactionOptions transactionConfiguration = configuration.transaction();
		this.transactionMemoryBufferLimitSizeBytes = transactionConfiguration.transactionMemoryBufferLimitSizeBytes();
		this.transactionMemoryRegions = transactionConfiguration.transactionMemoryRegionCount();
		this.walMaxFileSizeBytes = transactionConfiguration.walFileSizeBytes();
		this.walMaxFileCountKept = transactionConfiguration.walFileCountKept();
		this.transactionFlushFrequencyInMillis = transactionConfiguration.flushFrequencyInMillis();

		final CacheOptions cacheConfiguration = configuration.cache();
		this.cacheReevaluationSeconds = cacheConfiguration.reevaluateEachSeconds();
		this.cacheAnteroomRecordLimit = cacheConfiguration.anteroomRecordCount();
		this.cacheSizeInBytes = ofNullable(cacheConfiguration.cacheSizeInBytes()).orElse(0L);

	}

}
