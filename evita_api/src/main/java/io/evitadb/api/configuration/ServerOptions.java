/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import javax.annotation.Nullable;

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
 * @param dropCollationKeysAfterSecondsOfInactivity Sets the timeout in seconds after which a cached collation key is
 *                                              released if nothing has compared it in the meantime; the default is
 *                                              5 minutes, and `0` keeps every key for the lifetime of the process.
 *                                              Sorting a localized
 *                                              attribute means consulting the JVM collator, which is about two orders
 *                                              of magnitude more expensive than comparing two pre-computed collation
 *                                              keys, so evitaDB caches those keys per locale. A workload that compares
 *                                              nearly every distinct value in the corpus - a bulk import, or a large
 *                                              transaction over a sortable localized attribute - fills that cache and
 *                                              benefits from it; steady-state query serving compares a much smaller
 *                                              hot subset and has no reason to keep paying for the import's footprint.
 *                                              This timeout bounds how long the unused remainder is retained.
 * @param changeDataCapture                     Defines settings for change data capture (CDC) that allows clients to subscribe
 *                                              to a stream of changes that occur in the database, enabling near real-time
 *                                              data synchronization, event-driven architectures, and audit logging.
 * @param trafficRecording                      Defines settings for traffic recording.
 * @param readOnly                              Starts the database in full read-only mode, prohibiting write operations
 *                                              on `EntityContract` level and open read-write `EvitaSessionContract`.
 * @param quiet                                 If true, all output to the system console is suppressed.
 * @param usageStatisticsTracking               Whether the engine counts how often each index and each schema
 *                                              capability flag is *queried* against how often it is *maintained* - the
 *                                              readings behind `BrowseIndexes` and `ListSchemaCapabilityUsage`. Enabled
 *                                              by default, because those readings are what tells an operator that a
 *                                              `filterable()` flag is being paid for and never used. Switching it off
 *                                              costs the diagnosis but reclaims the accounting: no `IndexActivity`
 *                                              holder is allocated per index (five longs each, and a large catalog runs
 *                                              to hundreds of thousands of indexes), and neither the query nor the
 *                                              write path resolves a capability holder. The surfaces keep working and
 *                                              keep listing every declared capability - each row simply reports itself
 *                                              as not measured rather than as never requested.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ServerOptions(
	@Nonnull ThreadPoolOptions requestThreadPool,
	@Nonnull ThreadPoolOptions transactionThreadPool,
	@Nonnull ThreadPoolOptions serviceThreadPool,
	long queryTimeoutInMilliseconds,
	long transactionTimeoutInMilliseconds,
	int closeSessionsAfterSecondsOfInactivity,
	int dropCollationKeysAfterSecondsOfInactivity,
	@Nonnull ChangeDataCaptureOptions changeDataCapture,
	@Nonnull TrafficRecordingOptions trafficRecording,
	boolean readOnly,
	boolean quiet,
	boolean usageStatisticsTracking
) {
	public static final long DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS = 5000L;
	/** Default read-write transaction timeout: 5 minutes (`300 * 1000` ms). */
	public static final long DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS = 300 * 1000L;
	/** Default idle-session auto-close threshold: 20 minutes (`60 * 20` seconds). */
	public static final int DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY = 60 * 20;
	/**
	 * Default collation-key retention: 5 minutes (`60 * 5` seconds) of inactivity before a key is released.
	 *
	 * Releasing the keys is cheap where it was expected to be expensive: a bulk import pays **nothing** for it - a
	 * 972k-article localized import measured the same 379 s with the sweep enabled and disabled - while the release
	 * returns roughly 146 MB per locale.
	 *
	 * The one place it used to cost was the first write transaction after a quiet spell, because a transaction in the
	 * ALIVE state rebuilt its sort index's whole distinct-value structure and therefore re-collated every value that
	 * had just been released: at 640k distinct values that first transaction grew from 7.4 s to 12.9 s. Retention was
	 * originally unbounded for exactly that reason. That rebuild is gone - an insert now anchors on its own value
	 * bucket and touches only `O(depth)` values - so a cold collation cache no longer has a rebuild to worsen, and
	 * bounding the retention became the better trade.
	 *
	 * A deployment that sorts on very few distinct values, or one that wants the keys held for the process lifetime,
	 * can still set `0` explicitly to restore unbounded retention.
	 */
	public static final int DEFAULT_DROP_COLLATION_KEYS_AFTER_SECONDS_OF_INACTIVITY = 60 * 5;
	public static final boolean DEFAULT_READ_ONLY = false;
	public static final boolean DEFAULT_QUIET = false;
	/**
	 * Usage statistics are tracked by default: the counters are what make an unused index or an unused capability flag
	 * visible at all, and a diagnostic that has to be switched on before it can answer is one nobody has switched on
	 * when the question finally gets asked.
	 */
	public static final boolean DEFAULT_USAGE_STATISTICS_TRACKING = true;

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

	/**
	 * Canonical constructor that normalizes optional inputs: any `null` thread-pool, change-data-capture, or
	 * traffic-recording component is replaced with its default build, so the resulting record never holds a
	 * `null` component.
	 */
	public ServerOptions(
		@Nullable ThreadPoolOptions requestThreadPool,
		@Nullable ThreadPoolOptions transactionThreadPool,
		@Nullable ThreadPoolOptions serviceThreadPool,
		long queryTimeoutInMilliseconds,
		long transactionTimeoutInMilliseconds,
		int closeSessionsAfterSecondsOfInactivity,
		int dropCollationKeysAfterSecondsOfInactivity,
		@Nullable ChangeDataCaptureOptions changeDataCapture,
		@Nullable TrafficRecordingOptions trafficRecording,
		boolean readOnly,
		boolean quiet,
		boolean usageStatisticsTracking
	) {
		this.requestThreadPool = requestThreadPool == null ? ThreadPoolOptions.requestThreadPoolBuilder().build() : requestThreadPool;
		this.transactionThreadPool = transactionThreadPool == null ? ThreadPoolOptions.transactionThreadPoolBuilder().build() : transactionThreadPool;
		this.serviceThreadPool = serviceThreadPool == null ? ThreadPoolOptions.serviceThreadPoolBuilder().build() : serviceThreadPool;
		this.queryTimeoutInMilliseconds = queryTimeoutInMilliseconds;
		this.transactionTimeoutInMilliseconds = transactionTimeoutInMilliseconds;
		this.closeSessionsAfterSecondsOfInactivity = closeSessionsAfterSecondsOfInactivity;
		this.dropCollationKeysAfterSecondsOfInactivity = dropCollationKeysAfterSecondsOfInactivity;
		this.changeDataCapture = changeDataCapture == null ? ChangeDataCaptureOptions.builder().build() : changeDataCapture;
		this.trafficRecording = trafficRecording == null ? TrafficRecordingOptions.builder().build() : trafficRecording;
		this.readOnly = readOnly;
		this.quiet = quiet;
		this.usageStatisticsTracking = usageStatisticsTracking;
	}

	public ServerOptions() {
		this(
			ThreadPoolOptions.requestThreadPoolBuilder().build(),
			ThreadPoolOptions.transactionThreadPoolBuilder().build(),
			ThreadPoolOptions.serviceThreadPoolBuilder().build(),
			DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS,
			DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS,
			DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY,
			DEFAULT_DROP_COLLATION_KEYS_AFTER_SECONDS_OF_INACTIVITY,
			ChangeDataCaptureOptions.builder().build(),
			TrafficRecordingOptions.builder().build(),
			DEFAULT_READ_ONLY,
			DEFAULT_QUIET,
			DEFAULT_USAGE_STATISTICS_TRACKING
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
		private int dropCollationKeysAfterSecondsOfInactivity = DEFAULT_DROP_COLLATION_KEYS_AFTER_SECONDS_OF_INACTIVITY;
		private ChangeDataCaptureOptions changeDataCapture = ChangeDataCaptureOptions.builder().build();
		private TrafficRecordingOptions trafficRecording = TrafficRecordingOptions.builder().build();
		private boolean readOnly = DEFAULT_READ_ONLY;
		private boolean quiet = DEFAULT_QUIET;
		private boolean usageStatisticsTracking = DEFAULT_USAGE_STATISTICS_TRACKING;

		Builder() {
		}

		Builder(@Nonnull ServerOptions serverOptions) {
			this.requestThreadPool = serverOptions.requestThreadPool();
			this.transactionThreadPool = serverOptions.transactionThreadPool();
			this.serviceThreadPool = serverOptions.serviceThreadPool();
			this.queryTimeoutInMilliseconds = serverOptions.queryTimeoutInMilliseconds();
			this.transactionTimeoutInMilliseconds = serverOptions.transactionTimeoutInMilliseconds();
			this.closeSessionsAfterSecondsOfInactivity = serverOptions.closeSessionsAfterSecondsOfInactivity();
			this.dropCollationKeysAfterSecondsOfInactivity = serverOptions.dropCollationKeysAfterSecondsOfInactivity();
			this.trafficRecording = serverOptions.trafficRecording();
			this.changeDataCapture = serverOptions.changeDataCapture();
			this.readOnly = serverOptions.readOnly();
			this.quiet = serverOptions.quiet();
			this.usageStatisticsTracking = serverOptions.usageStatisticsTracking();
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

		/**
		 * Sets how long a cached collation key may go uncompared before it is released.
		 *
		 * @param dropCollationKeysAfterSecondsOfInactivity timeout in seconds, `0` to retain keys for the lifetime of
		 *                                                 the process
		 */
		@Nonnull
		public ServerOptions.Builder dropCollationKeysAfterSecondsOfInactivity(int dropCollationKeysAfterSecondsOfInactivity) {
			this.dropCollationKeysAfterSecondsOfInactivity = dropCollationKeysAfterSecondsOfInactivity;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder changeDataCapture(@Nonnull ChangeDataCaptureOptions changeDataCapture) {
			this.changeDataCapture = changeDataCapture;
			return this;
		}

		@Nonnull
		public ServerOptions.Builder trafficRecording(@Nonnull TrafficRecordingOptions trafficRecording) {
			this.trafficRecording = trafficRecording;
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

		/**
		 * Sets whether index and schema-capability usage counters are maintained - see the record's own
		 * `usageStatisticsTracking` documentation for what switching this off costs and what it reclaims.
		 *
		 * @param usageStatisticsTracking true to keep counting, false to pay nothing for the counters
		 */
		@Nonnull
		public ServerOptions.Builder usageStatisticsTracking(boolean usageStatisticsTracking) {
			this.usageStatisticsTracking = usageStatisticsTracking;
			return this;
		}

		@Nonnull
		public ServerOptions build() {
			return new ServerOptions(
				this.requestThreadPool,
				this.transactionThreadPool,
				this.serviceThreadPool,
				this.queryTimeoutInMilliseconds,
				this.transactionTimeoutInMilliseconds,
				this.closeSessionsAfterSecondsOfInactivity,
				this.dropCollationKeysAfterSecondsOfInactivity,
				this.changeDataCapture,
				this.trafficRecording,
				this.readOnly,
				this.quiet,
				this.usageStatisticsTracking
			);
		}

	}

}
