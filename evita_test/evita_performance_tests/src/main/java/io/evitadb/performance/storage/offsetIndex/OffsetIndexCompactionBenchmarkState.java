/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.performance.storage.offsetIndex;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.utils.IOUtils;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Function;

/**
 * JMH state for {@link OffsetIndexCompactionBenchmark}.
 *
 * The state builds a populated {@link OffsetIndex} file on disk once per trial, parameterised on:
 *
 * - `recordCount` + `payloadSizeProfile` — control the total file size and per-record payload distribution
 *   (small/medium/large), mirroring the production mix described in issue #1157
 * - `compression` — toggle the `ZipCompressionFactory` path used by `copySnapshotTo`
 * - `crc32` — toggle the `Crc32CChecksumFactory` path used by `copySnapshotTo`
 *
 * The source `OffsetIndex` is opened once per trial and kept open across invocations, so each
 * benchmark measurement only includes the cost of `OffsetIndex.compact(Path)` — the read of the
 * source file, the per-record byte[] allocations, and the write of the destination file. The
 * destination file is recreated on a fresh path for every invocation to avoid append-mode
 * interference between iterations.
 *
 * The benchmark deliberately targets `OffsetIndex.compact` (not just
 * `OffsetIndexSerializationService.copySnapshotTo`) so the production code path — including
 * `FileOutputStream` open/close — is exercised end-to-end.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class OffsetIndexCompactionBenchmarkState {

	private static final String ENTITY_TYPE = "benchmark";
	private static final Locale[] LOCALES = new Locale[] {
		Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN, new Locale("cs", "CZ")
	};

	/**
	 * Total number of records inserted into the source `OffsetIndex` before compaction.
	 *
	 * The defaults span three orders of magnitude on the per-record path: many tiny records (where
	 * fragment-header overhead dominates), a moderate mix that approximates the entity-storage-part
	 * mid-band, and few large records (where the per-record `byte[]` allocation observed in the
	 * issue is most visible).
	 */
	@Param({"200", "2000"})
	public int recordCount;

	/**
	 * Per-record payload size profile.
	 *
	 * `SMALL`/`MEDIUM`/`LARGE` use {@link EntityBodyStoragePart} filled with random
	 * {@link AssociatedDataKey} entries — realistic shapes but heavily compressed by `KeyCompressor`
	 * at write time, so the on-disk record sizes top out around a few KB regardless of the key
	 * count. Use these to exercise the per-record loop overhead and per-fragment bookkeeping.
	 *
	 * `HUGE` switches to {@link RawBytesStoragePart} carrying an opaque random `byte[]` that
	 * survives serialization at full size. This is the profile that reproduces the per-record
	 * `byte[]` allocation pattern described in issue #1157 (records up to ~660 KB).
	 */
	@Param({"SMALL", "MEDIUM", "LARGE", "HUGE"})
	public PayloadSizeProfile payloadSizeProfile;

	/**
	 * Toggles the `ZipCompressionFactory` path. Issue #1157 requires the allocation rate to be
	 * independent of compression mode, so both values are exercised.
	 */
	@Param({"true", "false"})
	public boolean compression;

	/**
	 * Toggles the `Crc32CChecksumFactory` path. Production uses CRC32C, but disabling it lets us
	 * attribute time/allocations between checksum work and the per-record copy itself.
	 */
	@Param({"true"})
	public boolean crc32;

	private Path benchmarkRoot;
	private Path sourceFile;
	private OffsetIndexRecordTypeRegistry recordTypeRegistry;
	private StorageSettings storageSettings;
	private ObservableOutputKeeper observableOutputKeeper;
	private ScheduledThreadPoolExecutor schedulerExecutor;
	private WriteOnlyFileHandle sourceWriteHandle;
	private OffsetIndex sourceOffsetIndex;
	private long catalogVersion;
	private long sourceFileSize;

	private Path targetFile;

	/**
	 * Builds the source `OffsetIndex` file once per benchmark trial. The file lives in a fresh
	 * tmp dir so concurrent forks don't race on the same path.
	 */
	@Setup(Level.Trial)
	public void setUpTrial() throws IOException {
		this.benchmarkRoot = Files.createTempDirectory("evita-offsetIndex-compaction-bench");
		this.sourceFile = this.benchmarkRoot.resolve("source.kryo");

		this.recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
		// `RawBytesStoragePart` is a benchmark-only type unknown to the production
		// `EntityStoragePartRegistry` SPI; register it explicitly so `OffsetIndex.put` can resolve
		// its type id. Picked 99 to stay well clear of the production range (currently up to 52).
		this.recordTypeRegistry.registerFileOffsetIndexType(
			(byte) 99, RawBytesStoragePart.class
		);
		this.storageSettings = new StorageSettings(
			StorageOptions.builder(StorageOptions.temporary())
				.computeCRC32(this.crc32)
				.compress(this.compression)
				.build(),
			TransactionOptions.builder().build()
		);

		// real (non-mocked) scheduler so we don't drag Mockito into JMH measurement overhead;
		// the lifecycle flags are required so `shutdownNow()` actually evicts the cut-task delayed
		// in the queue — without them JMH's forked VM hangs for 30s on every trial teardown.
		this.schedulerExecutor = new ScheduledThreadPoolExecutor(1);
		this.schedulerExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		this.schedulerExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		this.schedulerExecutor.setRemoveOnCancelPolicy(true);
		this.observableOutputKeeper = ObservableOutputKeeper._internalBuild(
			new Scheduler(this.schedulerExecutor)
		);
		this.sourceWriteHandle = createWriteHandle(this.sourceFile);
		this.sourceOffsetIndex = new OffsetIndex(
			0L,
			createInitialDescriptor(),
			this.storageSettings.outputBufferSize(),
			this.storageSettings.maxOpenedReadHandlesOrDefault(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			this.storageSettings,
			this.storageSettings,
			this.recordTypeRegistry,
			this.sourceWriteHandle,
			null,
			null
		);

		final Random random = new Random(42L);
		for (int primaryKey = 1; primaryKey <= this.recordCount; primaryKey++) {
			this.sourceOffsetIndex.put(0L, buildStoragePart(random, primaryKey));
		}
		this.sourceOffsetIndex.flush(0L);
		this.catalogVersion = 1L;
		this.sourceFileSize = Files.size(this.sourceFile);
		System.err.printf(
			"[OffsetIndexCompactionBenchmark setup] recordCount=%d profile=%s compression=%s crc32=%s "
				+ "sourceFile=%.2f MB avgRecord=%.2f KB%n",
			this.recordCount,
			this.payloadSizeProfile,
			this.compression,
			this.crc32,
			this.sourceFileSize / 1024.0 / 1024.0,
			this.sourceFileSize / 1024.0 / Math.max(1, this.recordCount)
		);
	}

	/**
	 * Creates a fresh destination file path for every invocation. Compaction must not see a
	 * pre-existing file because `FileOutputStream` would otherwise reuse it and skew timings.
	 */
	@Setup(Level.Invocation)
	public void setUpInvocation() {
		this.targetFile = this.benchmarkRoot.resolve(
			"compacted-" + System.nanoTime() + ".kryo"
		);
	}

	/**
	 * Removes the per-invocation destination file so the temp dir doesn't grow without bound
	 * during long-running benchmark runs.
	 */
	@TearDown(Level.Invocation)
	public void tearDownInvocation() {
		if (this.targetFile != null) {
			final Path toDelete = this.targetFile;
			this.targetFile = null;
			toDelete.toFile().delete();
		}
	}

	/**
	 * Closes the source `OffsetIndex` and removes the trial directory.
	 */
	@TearDown(Level.Trial)
	public void tearDownTrial() throws IOException {
		if (this.sourceOffsetIndex != null) {
			IOUtils.closeQuietly(this.sourceOffsetIndex::close);
			this.sourceOffsetIndex = null;
		}
		if (this.sourceWriteHandle != null) {
			IOUtils.closeQuietly(this.sourceWriteHandle::close);
			this.sourceWriteHandle = null;
		}
		if (this.observableOutputKeeper != null) {
			IOUtils.closeQuietly(this.observableOutputKeeper::close);
			this.observableOutputKeeper = null;
		}
		if (this.schedulerExecutor != null) {
			this.schedulerExecutor.shutdownNow();
			this.schedulerExecutor = null;
		}
		if (this.benchmarkRoot != null) {
			deleteRecursively(this.benchmarkRoot);
			this.benchmarkRoot = null;
		}
	}

	/**
	 * Returns the source `OffsetIndex` used by the benchmark methods. Kept alive for the entire
	 * trial — compaction does not mutate the source.
	 */
	@Nonnull
	public OffsetIndex getSourceOffsetIndex() {
		return this.sourceOffsetIndex;
	}

	/**
	 * Returns the per-invocation destination file path. A fresh path is generated for every
	 * benchmark invocation to avoid file-append interference.
	 */
	@Nonnull
	public Path getTargetFile() {
		return this.targetFile;
	}

	/**
	 * Returns the catalog version used when compacting. Compaction calls
	 * `OffsetIndex.compact(Path)` which internally pins a `keyCatalogVersion` snapshot.
	 */
	public long getCatalogVersion() {
		return this.catalogVersion;
	}

	/**
	 * Returns the bytes on disk for the source `OffsetIndex`. Useful for sanity-checking the
	 * workload size in JMH `@AuxCounters` or in printed setup logs.
	 */
	public long getSourceFileSize() {
		return this.sourceFileSize;
	}

	@Nonnull
	private WriteOnlyFileHandle createWriteHandle(@Nonnull Path target) {
		return new WriteOnlyFileHandle(
			target,
			this.storageSettings.outputBufferSize(),
			this.storageSettings.syncWrites(),
			this.storageSettings,
			this.storageSettings,
			this.observableOutputKeeper
		);
	}

	@Nonnull
	private OffsetIndexDescriptor createInitialDescriptor() {
		return new OffsetIndexDescriptor(
			new EntityCollectionFileHeader(ENTITY_TYPE, 1, 0),
			createKryoFactory(),
			1.0,
			0L
		);
	}

	@Nonnull
	private static Function<VersionedKryoKeyInputs, VersionedKryo> createKryoFactory() {
		return keyInputs -> VersionedKryoFactory.createKryo(
			keyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(new EntityStoragePartConfigurer(keyInputs.keyCompressor()))
				.andThen(kryo -> kryo.register(
					RawBytesStoragePart.class,
					new RawBytesStoragePartSerializer(),
					999
				))
		);
	}

	@Nonnull
	private StoragePart buildStoragePart(@Nonnull Random random, int primaryKey) {
		if (this.payloadSizeProfile.usesRawBytes) {
			return buildRawBytesPart(random, primaryKey);
		}
		return buildEntityBody(random, primaryKey);
	}

	@Nonnull
	private RawBytesStoragePart buildRawBytesPart(@Nonnull Random random, int primaryKey) {
		final int payloadBytes = this.payloadSizeProfile.samplePayloadBytes(random);
		final byte[] data = new byte[payloadBytes];
		random.nextBytes(data);
		return new RawBytesStoragePart(primaryKey, data);
	}

	@Nonnull
	private EntityBodyStoragePart buildEntityBody(@Nonnull Random random, int primaryKey) {
		final int keyCount = this.payloadSizeProfile.sampleKeyCount(random);
		final Set<AssociatedDataKey> associatedData = new HashSet<>(keyCount);
		// AssociatedDataKey serializes to roughly 20-60 bytes; the resulting EntityBodyStoragePart
		// size therefore scales approximately linearly with keyCount, which gives us a predictable
		// way to dial the per-record payload distribution without bringing in javafaker.
		for (int i = 0; i < keyCount; i++) {
			associatedData.add(
				new AssociatedDataKey(
					"key_" + primaryKey + "_" + i + "_" + random.nextInt(1_000_000),
					LOCALES[random.nextInt(LOCALES.length)]
				)
			);
		}
		return new EntityBodyStoragePart(
			1,
			primaryKey,
			io.evitadb.dataType.Scope.LIVE,
			null,
			Set.of(),
			Set.of(),
			associatedData,
			0
		);
	}

	private static void deleteRecursively(@Nonnull Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		Files.walk(root)
			.sorted((a, b) -> b.getNameCount() - a.getNameCount())
			.forEach(p -> p.toFile().delete());
	}

	/**
	 * Per-record payload size profile.
	 *
	 * `SMALL`/`MEDIUM`/`LARGE` are configured by `AssociatedDataKey` count — these get heavily
	 * compressed by the offset-index `KeyCompressor` and produce ~1-5 KB on-disk records regardless
	 * of the key count. They exercise the per-record loop overhead and per-fragment bookkeeping.
	 *
	 * `HUGE` is configured directly in bytes and uses {@link RawBytesStoragePart} — the random
	 * bytes survive serialization at full size, producing on-disk records that match the
	 * production scenario from issue #1157 (records up to ~660 KB on a multi-hundred-MB file).
	 */
	public enum PayloadSizeProfile {
		SMALL(false, 8, 64),
		MEDIUM(false, 64, 512),
		LARGE(false, 1_024, 4_096),
		HUGE(true, 256 * 1024, 1024 * 1024);

		final boolean usesRawBytes;
		private final int min;
		private final int max;

		PayloadSizeProfile(boolean usesRawBytes, int min, int max) {
			this.usesRawBytes = usesRawBytes;
			this.min = min;
			this.max = max;
		}

		int sampleKeyCount(@Nonnull Random random) {
			return sampleInRange(random);
		}

		int samplePayloadBytes(@Nonnull Random random) {
			return sampleInRange(random);
		}

		private int sampleInRange(@Nonnull Random random) {
			final int span = this.max - this.min;
			return this.min + (span == 0 ? 0 : random.nextInt(span));
		}
	}

}
