/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.store.index.serializer.FilterIndexPayloadSerializer;
import io.evitadb.store.index.serializer.HistogramIndexStoragePartSerializer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.settings.StorageSettings;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writes released second-granularity range roots through the real record and checksum machinery. */
final class LegacyRangeIndexImages {
	/** Prevents instantiation of the fixture. */
	private LegacyRangeIndexImages() {
	}

	/**
	 * Opens the collection, runs `work` on its service and closes everything it needed again.
	 */
	static <T> T withLegacyRanges(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		long catalogVersion,
		@Nonnull Path catalogStoragePath,
		@Nonnull EntityCollectionFileHeader header,
		@Nonnull Function<DefaultEntityCollectionPersistenceService, T> work
	) {
		final StorageSettings settings = new StorageSettings(configuration.storage(), configuration.transaction());
		final Scheduler scheduler = new Scheduler(new ScheduledThreadPoolExecutor(1));
		try (
			CatalogOffHeapMemoryManager offHeap = new CatalogOffHeapMemoryManager(
				catalogName, settings.transactionMemoryBufferLimitSizeBytes(), settings.transactionMemoryRegionCount(),
				settings
			);
			ObservableOutputKeeper outputKeeper = new ObservableOutputKeeper(
				catalogName, settings.lockTimeoutSeconds(), settings.waitOnCloseSeconds(),
				scheduler
			);
			DefaultEntityCollectionPersistenceService collection = new LegacyRangesCollectionService(
				catalogVersion, catalogName, catalogStoragePath, header, settings, offHeap, outputKeeper
			)
		) {
			return work.apply(collection);
		} finally {
			scheduler.shutdownNow();
			try {
				assertTrue(scheduler.awaitTermination(30, TimeUnit.SECONDS), "the offline scheduler did not stop");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Installs legacy root writers while retaining production readers for existing records. */
	private static final class LegacyRangesCollectionService extends DefaultEntityCollectionPersistenceService {

		/** Opens the collection with its published header and the fixture's legacy root writers. */
		LegacyRangesCollectionService(
			long catalogVersion,
			@Nonnull String catalogName,
			@Nonnull Path catalogStoragePath,
			@Nonnull EntityCollectionFileHeader header,
			@Nonnull StorageSettings settings,
			@Nonnull CatalogOffHeapMemoryManager offHeap,
			@Nonnull ObservableOutputKeeper outputKeeper
		) {
			super(catalogVersion, catalogName, catalogStoragePath, header, settings, offHeap, outputKeeper,
				new OffsetIndexRecordTypeRegistry(), null);
		}

		/** Replaces root writers while keeping the full production Kryo registration graph. */
		@Override
		@Nonnull
		public Function<VersionedKryoKeyInputs, VersionedKryo> createTypeKryoInstance() {
			final Function<VersionedKryoKeyInputs, VersionedKryo> production = super.createTypeKryoInstance();
			return inputs -> {
				final VersionedKryo kryo = production.apply(inputs);
				installLegacyWriter(kryo, FilterIndexStoragePart.class, new LegacyFilterWriter(inputs.keyCompressor()));
				installLegacyWriter(
					kryo, HistogramIndexStoragePart.class, new LegacyHistogramWriter(inputs.keyCompressor())
				);
				return kryo;
			};
		}
	}

	/** Keeps the existing production version dispatcher for reads and substitutes only the writer. */
	private static <T> void installLegacyWriter(Kryo kryo, Class<T> type, Serializer<T> writer) {
		final Serializer<T> reader = kryo.getSerializer(type);
		final Serializer<T> codec = new Serializer<>() {
			/** Delegates writes to the released wire fixture. */
			@Override
			public void write(Kryo target, Output output, T part) {
				writer.write(target, output, part);
			}

			/** Reads existing records with the production version dispatcher. */
			@Override
			public T read(Kryo target, Input input, Class<? extends T> partType) {
				return reader.read(target, input, partType);
			}
		};
		kryo.register(type, codec, kryo.getRegistration(type).getId());
	}

	/** Reproduces the pre-value-id filter wire, including the released root version. */
	private static final class LegacyFilterWriter extends Serializer<FilterIndexStoragePart> {
		private final KeyCompressor keyCompressor;

		/** Binds the dictionary shared with the collection writer. */
		private LegacyFilterWriter(KeyCompressor keyCompressor) {
			this.keyCompressor = keyCompressor;
		}

		/** Writes the released filter uid and payload without the later value-id section. */
		@Override
		public void write(Kryo kryo, Output output, FilterIndexStoragePart part) {
			output.writeLong(3847290165472938104L);
			output.writeInt(part.getEntityIndexPrimaryKey());
			output.writeVarLong(part.getStoragePartPK(), true);
			output.writeVarInt(this.keyCompressor.getId(part.getAttributeIndexKey()), true);
			kryo.writeClass(output, part.getAttributeType());
			FilterIndexPayloadSerializer.write(kryo, output,
				part.getHistogramPoints(), part.getRangeIndex(), part.getIndexedDecimalPlaces(),
				part.isPaged(), part.getHighWaterPageSequence(), part.getLeafPageSequences(),
				part.isRangePaged(), part.getRangeHighWaterPageSequence(), part.getRangeLeafPageSequences());
		}

		/** Prevents accidental use of a write-only fixture as a legacy reader. */
		@Override
		public FilterIndexStoragePart read(Kryo kryo, Input input, Class<? extends FilterIndexStoragePart> type) {
			throw new UnsupportedOperationException("The production dispatcher owns reads.");
		}
	}

	/** Histogram root layout is unchanged; only the version identifies seconds. */
	private static final class LegacyHistogramWriter extends Serializer<HistogramIndexStoragePart> {
		private final KeyCompressor keyCompressor;

		/** Binds the histogram identity dictionary. */
		private LegacyHistogramWriter(KeyCompressor keyCompressor) {
			this.keyCompressor = keyCompressor;
		}

		/** Writes the released histogram uid before its unchanged payload. */
		@Override
		public void write(Kryo kryo, Output output, HistogramIndexStoragePart part) {
			output.writeLong(5083172946028471653L);
			new HistogramIndexStoragePartSerializer(this.keyCompressor).write(kryo, output, part);
		}

		/** Prevents accidental use of a write-only fixture as a legacy reader. */
		@Override
		public HistogramIndexStoragePart read(Kryo kryo, Input input, Class<? extends HistogramIndexStoragePart> type) {
			throw new UnsupportedOperationException("The production dispatcher owns reads.");
		}
	}
}
