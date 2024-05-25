/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.core.metric.annotation.ExportInvocationMetric;
import io.evitadb.core.metric.annotation.ExportMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event that is fired when evitaDB instance is started.
 */
@Name(AbstractSystemEvent.PACKAGE_NAME + ".EvitaStarted")
@Description("Event that is fired when evitaDB instance is started.")
@ExportInvocationMetric(label = "Evita started total")
@Label("Evita started")
@Getter
public class EvitaStartedEvent extends AbstractSystemEvent {

	@Label("Maximal number of background threads")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int maxThreads;

	@Label("Maximal queue size for background threads")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int maxThreadsQueueSize;

	@Label("Short running tasks timeout in seconds")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int shortTasksTimeoutSeconds;

	@Label("Maximal session inactivity age in seconds")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int sessionMaxInactiveAgeSeconds;

	@Label("Maximal count of opened read-only handles")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int readOnlyHandlesLimit;

	@Label("Minimal share of active records in the file to start compaction in %")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int compactionMinimalActiveRecordSharePercent;

	@Label("Minimal file size threshold to start compaction in Bytes")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long compactionFileSizeThresholdBytes;

	@Label("Size of off-heap memory buffer for transactions in Bytes")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long transactionMemoryBufferLimitSizeBytes;

	@Label("Number of off-heap memory regions for transactions")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int transactionMemoryRegions;

	@Label("Maximal count of commited transactions in queue")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int transactionMaxQueueSize;

	@Label("Maximal write-ahead log file size in Bytes")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long walMaxFileSizeBytes;

	@Label("Maximal write-ahead log file count to keep")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int walMaxFileCountKept;

	@Label("Cache reevaluation interval in seconds")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int cacheReevaluationSeconds;

	@Label("Maximal number of records in cache anteroom")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final int cacheAnteroomRecordLimit;

	@Label("Maximal size of cache in Bytes")
	@ExportMetric(metricType = MetricType.GAUGE)
	private final long cacheSizeInBytes;

	public EvitaStartedEvent(@Nonnull EvitaConfiguration configuration) {
		super(null);

		final ServerOptions serverConfiguration = configuration.server();
		this.maxThreads = serverConfiguration.maxThreadCount();
		this.maxThreadsQueueSize = serverConfiguration.queueSize();
		this.shortTasksTimeoutSeconds = serverConfiguration.shortRunningThreadsTimeoutInSeconds();
		this.sessionMaxInactiveAgeSeconds = serverConfiguration.closeSessionsAfterSecondsOfInactivity();

		final StorageOptions storageConfiguration = configuration.storage();
		this.readOnlyHandlesLimit = storageConfiguration.maxOpenedReadHandles();
		this.compactionMinimalActiveRecordSharePercent = Math.toIntExact(Math.round(storageConfiguration.minimalActiveRecordShare() * 100.0));
		this.compactionFileSizeThresholdBytes = storageConfiguration.fileSizeCompactionThresholdBytes();

		final TransactionOptions transactionConfiguration = configuration.transaction();
		this.transactionMemoryBufferLimitSizeBytes = transactionConfiguration.transactionMemoryBufferLimitSizeBytes();
		this.transactionMemoryRegions = transactionConfiguration.transactionMemoryRegionCount();
		this.walMaxFileSizeBytes = transactionConfiguration.walFileSizeBytes();
		this.walMaxFileCountKept = transactionConfiguration.walFileCountKept();
		this.transactionMaxQueueSize = transactionConfiguration.maxQueueSize();

		final CacheOptions cacheConfiguration = configuration.cache();
		this.cacheReevaluationSeconds = cacheConfiguration.reevaluateEachSeconds();
		this.cacheAnteroomRecordLimit = cacheConfiguration.anteroomRecordCount();
		this.cacheSizeInBytes = cacheConfiguration.cacheSizeInBytes();

	}

}
