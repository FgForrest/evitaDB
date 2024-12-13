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

package io.evitadb.api.configuration;


import lombok.ToString;

import javax.annotation.Nonnull;

/**
 * Traffic recording options define how the server should record the traffic to the database. The traffic recording
 * is useful for debugging, performance analysis, reproducing issues, and verifying the correctness of the new database
 * version. The traffic recording can be enabled or disabled, and it can be configured to record only a subset of the
 * traffic (e.g., 10% of the traffic). The traffic recording can be done in memory or on disk. The traffic recording
 * is always related to a single catalog.
 *
 * @param enabled                        If true, the server records all traffic to the database (all catalogs)
 *                                       in a single shared memory and disk buffer that could be optionally
 *                                       persisted to file. If traffic recording is disabled, it can still be enabled
 *                                       on demand via the API (but it's not automatically enabled and recording).
 * @param sourceQueryTracking            If true, the server records the query in its original form (GraphQL / REST)
 *                                       and tracks sub-queries related to the original query. This is useful for
 *                                       debugging and performance analysis.
 * @param trafficSamplingPercentage      Sets the percentage of traffic that should be recorded. The value is
 *                                       between 0 and 100.
 * @param trafficMemoryBufferSizeInBytes Sets the size of the memory buffer used for traffic recording in Bytes.
 *                                       Even if `enabled` is disabled this property is used when on
 *                                       demand traffic recording is requested.
 * @param trafficDiskBufferSizeInBytes   Sets the size of the disk buffer used for traffic recording in Bytes.
 *                                       Even if `enabled` is disabled this property is used when on
 *                                       demand traffic recording is requested.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record TrafficRecordingOptions(
	boolean enabled,
	boolean sourceQueryTracking,
	long trafficMemoryBufferSizeInBytes,
	long trafficDiskBufferSizeInBytes,
	int trafficSamplingPercentage
) {
	public static final long DEFAULT_TRAFFIC_MEMORY_BUFFER = 4_194_304L;
	public static final long DEFAULT_TRAFFIC_DISK_BUFFER = 26_214_400L;
	public static final int DEFAULT_TRAFFIC_SAMPLING_PERCENTAGE = 100;
	public static final boolean DEFAULT_TRAFFIC_RECORDING = false;
	public static final boolean DEFAULT_TRAFFIC_SOURCE_QUERY_TRACKING = false;

	/**
	 * Builder for the server options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static TrafficRecordingOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the server options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static TrafficRecordingOptions.Builder builder(@Nonnull TrafficRecordingOptions trafficRecordingOptions) {
		return new Builder(trafficRecordingOptions);
	}

	public TrafficRecordingOptions() {
		this(
			DEFAULT_TRAFFIC_RECORDING,
			DEFAULT_TRAFFIC_SOURCE_QUERY_TRACKING,
			DEFAULT_TRAFFIC_MEMORY_BUFFER,
			DEFAULT_TRAFFIC_DISK_BUFFER,
			DEFAULT_TRAFFIC_SAMPLING_PERCENTAGE
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private boolean enabled = DEFAULT_TRAFFIC_RECORDING;
		private boolean sourceQueryTracking = DEFAULT_TRAFFIC_SOURCE_QUERY_TRACKING;
		private long trafficMemoryBufferSizeInBytes = DEFAULT_TRAFFIC_MEMORY_BUFFER;
		private long trafficDiskBufferSizeInBytes = DEFAULT_TRAFFIC_DISK_BUFFER;
		private int trafficSamplingPercentage = DEFAULT_TRAFFIC_SAMPLING_PERCENTAGE;

		Builder() {
		}

		Builder(@Nonnull TrafficRecordingOptions trafficRecordingOptions) {
			this.enabled = trafficRecordingOptions.enabled();
			this.trafficMemoryBufferSizeInBytes = trafficRecordingOptions.trafficMemoryBufferSizeInBytes();
			this.trafficDiskBufferSizeInBytes = trafficRecordingOptions.trafficDiskBufferSizeInBytes();
			this.trafficSamplingPercentage = trafficRecordingOptions.trafficSamplingPercentage();
		}

		@Nonnull
		public TrafficRecordingOptions.Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		@Nonnull
		public TrafficRecordingOptions.Builder sourceQueryTracking(boolean sourceQueryTracking) {
			this.sourceQueryTracking = sourceQueryTracking;
			return this;
		}

		@Nonnull
		public TrafficRecordingOptions.Builder trafficMemoryBufferSizeInBytes(long trafficMemoryBufferSizeInBytes) {
			this.trafficMemoryBufferSizeInBytes = trafficMemoryBufferSizeInBytes;
			return this;
		}

		@Nonnull
		public TrafficRecordingOptions.Builder trafficDiskBufferSizeInBytes(long trafficDiskBufferSizeInBytes) {
			this.trafficDiskBufferSizeInBytes = trafficDiskBufferSizeInBytes;
			return this;
		}

		@Nonnull
		public TrafficRecordingOptions.Builder trafficSamplingPercentage(int trafficSamplingPercentage) {
			this.trafficSamplingPercentage = trafficSamplingPercentage;
			return this;
		}

		@Nonnull
		public TrafficRecordingOptions build() {
			return new TrafficRecordingOptions(
				enabled,
				sourceQueryTracking,
				trafficMemoryBufferSizeInBytes,
				trafficDiskBufferSizeInBytes,
				trafficSamplingPercentage
			);
		}

	}

}
