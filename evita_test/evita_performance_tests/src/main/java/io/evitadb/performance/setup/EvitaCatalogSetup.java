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

package io.evitadb.performance.setup;

import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;

import javax.annotation.Nonnull;
import java.nio.file.Path;

/**
 * Base implementation for InMemory tests that doesn't allow catalog recurring usage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EvitaCatalogSetup extends CatalogSetup, EvitaTestSupport {

	/**
	 * System property overriding {@link TransactionOptions#flushFrequencyInMillis()} for a benchmark run.
	 *
	 * The value bounds how long trunk incorporation keeps greedily draining the WAL before it cuts a new
	 * catalog version, so it trades commit throughput (fewer, larger merges) against how long a client
	 * waiting for its changes to become visible is kept. Left unset the engine default applies, which
	 * keeps every existing benchmark on exactly the configuration it had before this knob was exposed.
	 */
	String FLUSH_FREQUENCY_PROPERTY = "evita.benchmark.flushFrequencyInMillis";

	/**
	 * System property switching {@link StorageOptions#syncWrites()} off, so a run can attribute how much of
	 * its cost is the data-file fsync that trunk incorporation performs when it checkpoints a round.
	 *
	 * This is a measurement knob, never a deployment one: with it off a crash can lose acknowledged data.
	 */
	String SYNC_WRITES_PROPERTY = "evita.benchmark.syncWrites";

	/**
	 * System property overriding {@link TransactionOptions#checkpointIntervalInMillis()} for a benchmark run.
	 *
	 * Set it to `0` to make every trunk round checkpoint - the behaviour that predates the interval - so a run can
	 * be compared against the engine default without a recompile.
	 *
	 * Note how this differs from {@link #SYNC_WRITES_PROPERTY}: switching sync writes off removes the device flush
	 * altogether and loses acknowledged data on a crash, whereas the interval only decides how often it happens.
	 * A `syncWrites=false` run therefore measures the **upper bound** of what the interval can deliver, not the
	 * interval itself - a deferred-checkpoint run scoring at or above that bound means the checkpoint never fired.
	 */
	String CHECKPOINT_INTERVAL_PROPERTY = "evita.benchmark.checkpointIntervalInMillis";

	/**
	 * Returns the storage root a benchmarked instance should own exclusively.
	 *
	 * evitaDB treats **every** sub-directory of its storage directory as a catalog and refuses to boot when one of
	 * them cannot be read as such. Pointing a benchmark at the shared test directory therefore makes it inherit every
	 * catalog any other test or benchmark ever left behind there — including half-written ones from runs that were
	 * killed — and the instance dies during construction with a storm of `SchemaNotFoundException` /
	 * `InvalidClassifierFormatException`. JMH reports that as a finished run with no measured operations, so the
	 * failure is easy to mistake for a benchmark that simply produced no score.
	 *
	 * Giving each catalog its own root removes the whole class of failure: the instance can only ever see its own
	 * data, no matter what else is lying around. The path is derived deterministically from the catalog name rather
	 * than randomised, because {@link EvitaCatalogReusableSetup} has to find the catalog a previous run built.
	 *
	 * The `benchmark_` prefix is not cosmetic — the functional test suite writes its catalogs straight into
	 * {@link #getTestDirectory()} as `<base>/<catalogName>`, so an unprefixed root would collide with those and
	 * reintroduce exactly the problem this method exists to prevent.
	 *
	 * @param catalogName name of the catalog the instance will serve
	 * @return directory that will contain this catalog and nothing else
	 */
	@Nonnull
	default Path benchmarkStorageDirectory(@Nonnull String catalogName) {
		return getTestDirectory().resolve(BENCHMARK_STORAGE_PREFIX + catalogName);
	}

	/**
	 * Prefix distinguishing benchmark-owned storage roots from the catalogs the functional test suite writes directly
	 * into the shared test directory.
	 */
	String BENCHMARK_STORAGE_PREFIX = "benchmark_";

	/**
	 * Builds the transaction options for a benchmarked instance, honouring {@link #FLUSH_FREQUENCY_PROPERTY}
	 * when it is set so a run can sweep the greedy batching budget without a recompile.
	 *
	 * @return transaction options with the configured flush frequency, or the engine defaults when unset
	 */
	@Nonnull
	default TransactionOptions benchmarkTransactionOptions() {
		final TransactionOptions.Builder builder = TransactionOptions.builder();
		final String flushFrequency = System.getProperty(FLUSH_FREQUENCY_PROPERTY);
		if (flushFrequency != null) {
			builder.flushFrequencyInMillis(Long.parseLong(flushFrequency));
		}
		final String checkpointInterval = System.getProperty(CHECKPOINT_INTERVAL_PROPERTY);
		if (checkpointInterval != null) {
			builder.checkpointIntervalInMillis(Long.parseLong(checkpointInterval));
		}
		return builder.build();
	}

	/**
	 * Returns the configured {@link StorageOptions#syncWrites()} for a benchmarked instance, honouring
	 * {@link #SYNC_WRITES_PROPERTY} when it is set.
	 *
	 * @return FALSE only when explicitly switched off for a measurement, the engine default otherwise
	 */
	default boolean benchmarkSyncWrites() {
		final String syncWrites = System.getProperty(SYNC_WRITES_PROPERTY);
		return syncWrites == null || Boolean.parseBoolean(syncWrites);
	}

	@Override
	default Evita createEmptyEvitaInstance(@Nonnull String catalogName) {
		cleanTestSubDirectoryWithRethrow(BENCHMARK_STORAGE_PREFIX + catalogName);
		// create new empty database
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.queryTimeoutInMilliseconds(60_000)
						.transactionTimeoutInMilliseconds(60_000)
						.closeSessionsAfterSecondsOfInactivity(60)
						.build()
				)
				.storage(
					StorageOptions.builder()
						.storageDirectory(benchmarkStorageDirectory(catalogName))
						.lockTimeoutSeconds(50)
						.waitOnCloseSeconds(50)
						.outputBufferSize(4_194_304)
						.maxOpenedReadHandles(Runtime.getRuntime().availableProcessors() * 4)
						.computeCRC32(true)
						.minimalActiveRecordShare(0.01)
						.syncWrites(benchmarkSyncWrites())
						.build()
				)
				.transaction(benchmarkTransactionOptions())
				.cache(
					CacheOptions.builder()
						.enabled(true)
						.reevaluateEachSeconds(60)
						.anteroomRecordCount(100_000)
						.minimalComplexityThreshold(10_000)
						.minimalUsageThreshold(2)
						.cacheSizeInBytes(1_000_000_000L)
						.build()
				)
				.build()
		);
		evita.deleteCatalogIfExists(catalogName);
		evita.defineCatalog(catalogName);
		return evita;
	}

}
