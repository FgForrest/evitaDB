/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityContract;
import lombok.ToString;

import javax.annotation.Nonnull;

/**
 * Record contains base server wide settings for the evitaDB.
 *
 * @param requestThreadPool                     Defines limits for core thread pool that is used for serving all incoming
 *                                              requests. Threads from this pool handles all queries and updates up until
 *                                              the transaction is committed / rolled-back.
 * @param transactionThreadPool                 Sets limits on the transaction thread pool used to process transactions
 *                                              when they're committed. I.e. conflict resolution, inclusion in trunk,
 *                                              and replacement of shared indexes used.
 * @param serviceThreadPool                     Sets limits on the service thread pool used for service tasks such as
 *                                              maintenance, backup creation, backup restoration, and so on.
 * @param queryTimeoutInMilliseconds            Sets the timeout in milliseconds after which threads executing read-only
 *                                              session requests should timeout and abort their execution.
 * @param transactionTimeoutInMilliseconds      Sets the timeout in milliseconds after which threads executing
 *                                              read-write session requests should timeout and abort their execution.
 * @param closeSessionsAfterSecondsOfInactivity Sets the timeout in seconds after which the session is automatically
 *                                              closed if no activity is observed on it.
 * @param trafficRecording                      If true, the server records all traffic to the database (all catalogs)
 *                                              in a single shared memory buffer that could be optionally persisted to file.
 * @param trafficSamplingPercentage			    Sets the percentage of traffic that should be recorded. The value is
 *                                              between 0 and 100.
 * @param trafficMemoryBufferSizeInBytes        Sets the size of the memory buffer used for traffic recording in Bytes.
 * @param readOnly                              starts the database in full read-only mode, prohibiting write operations
 *                                              on {@link EntityContract} level and open read-write {@link EvitaSessionContract}.
 * @param quiet                                 If true, all output to the system console is suppressed.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ServerOptions(
	@Nonnull ThreadPoolOptions requestThreadPool,
	@Nonnull ThreadPoolOptions transactionThreadPool,
	@Nonnull ThreadPoolOptions serviceThreadPool,
	long queryTimeoutInMilliseconds,
	long transactionTimeoutInMilliseconds,
	int closeSessionsAfterSecondsOfInactivity,
	boolean trafficRecording,
	long trafficMemoryBufferSizeInBytes,
	int trafficSamplingPercentage,
	boolean readOnly,
	boolean quiet
) {
	public static final long DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS = 5000L;
	public static final long DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS = 300 * 1000L;
	public static final int DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY = 60 * 20;
	public static final long DEFAULT_TRAFFIC_MEMORY_BUFFER = 4_194_304L;
	public static final int DEFAULT_TRAFFIC_SAMPLING_PERCENTAGE = 100;
	public static final boolean DEFAULT_TRAFFIC_RECORDING = false;
	public static final boolean DEFAULT_READ_ONLY = false;
	public static final boolean DEFAULT_QUIET = false;

	/**
	 * Builder for the server options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ServerOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the server options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ServerOptions.Builder builder(@Nonnull ServerOptions serverOptions) {
		return new Builder(serverOptions);
	}

	public ServerOptions() {
		this(
			ThreadPoolOptions.requestThreadPoolBuilder().build(),
			ThreadPoolOptions.transactionThreadPoolBuilder().build(),
			ThreadPoolOptions.serviceThreadPoolBuilder().build(),
			DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS,
			DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS,
			DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY,
			DEFAULT_TRAFFIC_RECORDING,
			DEFAULT_TRAFFIC_MEMORY_BUFFER,
			DEFAULT_TRAFFIC_SAMPLING_PERCENTAGE,
			DEFAULT_READ_ONLY,
			DEFAULT_QUIET
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private ThreadPoolOptions requestThreadPool = ThreadPoolOptions.requestThreadPoolBuilder().build();
		private ThreadPoolOptions transactionThreadPool = ThreadPoolOptions.transactionThreadPoolBuilder().build();
		private ThreadPoolOptions serviceThreadPool = ThreadPoolOptions.serviceThreadPoolBuilder().build();
		private long queryTimeoutInMilliseconds = DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS;
		private long transactionTimeoutInMilliseconds = DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS;
		private int closeSessionsAfterSecondsOfInactivity = DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY;
		private boolean trafficRecording = DEFAULT_TRAFFIC_RECORDING;
		private long trafficMemoryBufferSizeInBytes = DEFAULT_TRAFFIC_MEMORY_BUFFER;
		private int trafficSamplingPercentage = DEFAULT_TRAFFIC_SAMPLING_PERCENTAGE;
		private boolean readOnly = DEFAULT_READ_ONLY;
		private boolean quiet = DEFAULT_QUIET;

		Builder() {
		}

		Builder(@Nonnull ServerOptions serverOptions) {
			this.requestThreadPool = serverOptions.requestThreadPool();
			this.transactionThreadPool = serverOptions.transactionThreadPool();
			this.serviceThreadPool = serverOptions.serviceThreadPool();
			this.queryTimeoutInMilliseconds = serverOptions.queryTimeoutInMilliseconds();
			this.transactionTimeoutInMilliseconds = serverOptions.transactionTimeoutInMilliseconds();
			this.closeSessionsAfterSecondsOfInactivity = serverOptions.closeSessionsAfterSecondsOfInactivity();
			this.trafficRecording = serverOptions.trafficRecording();
			this.trafficMemoryBufferSizeInBytes = serverOptions.trafficMemoryBufferSizeInBytes();
			this.trafficSamplingPercentage = serverOptions.trafficSamplingPercentage();
			this.readOnly = serverOptions.readOnly();
			this.quiet = serverOptions.quiet();
		}

		@Nonnull
		public ServerOptions.Builder requestThreadPool(@Nonnull ThreadPoolOptions requestThreadPool) {
			this.requestThreadPool = requestThreadPool;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder transactionThreadPool(@Nonnull ThreadPoolOptions transactionThreadPool) {
			this.transactionThreadPool = transactionThreadPool;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder serviceThreadPool(@Nonnull ThreadPoolOptions serviceThreadPool) {
			this.serviceThreadPool = serviceThreadPool;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder queryTimeoutInMilliseconds(long queryTimeoutInMilliseconds) {
			this.queryTimeoutInMilliseconds = queryTimeoutInMilliseconds;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder transactionTimeoutInMilliseconds(long transactionTimeoutInMilliseconds) {
			this.transactionTimeoutInMilliseconds = transactionTimeoutInMilliseconds;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder closeSessionsAfterSecondsOfInactivity(int closeSessionsAfterSecondsOfInactivity) {
			this.closeSessionsAfterSecondsOfInactivity = closeSessionsAfterSecondsOfInactivity;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder trafficRecording(boolean trafficRecording) {
			this.trafficRecording = trafficRecording;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder trafficMemoryBufferSizeInBytes(long trafficMemoryBufferSizeInBytes) {
			this.trafficMemoryBufferSizeInBytes = trafficMemoryBufferSizeInBytes;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder trafficSamplingPercentage(int trafficSamplingPercentage) {
			this.trafficSamplingPercentage = trafficSamplingPercentage;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder readOnly(boolean readOnly) {
			this.readOnly = readOnly;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder quiet(boolean quiet) {
			this.quiet = quiet;
			return this;
		}

		@Nonnull
		public ServerOptions build() {
			return new ServerOptions(
				requestThreadPool,
				transactionThreadPool,
				serviceThreadPool,
				queryTimeoutInMilliseconds,
				transactionTimeoutInMilliseconds,
				closeSessionsAfterSecondsOfInactivity,
				trafficRecording,
				trafficMemoryBufferSizeInBytes,
				trafficSamplingPercentage,
				readOnly,
				quiet
			);
		}

	}

}
