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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.entity.serializer.AttributeValueSerializer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens an entity collection with the engine stopped through a persistence service whose Kryo writes every
 * {@link AttributeValue} the way release 2026.2 did: the `serialVersionUID` that release carried, followed by the
 * unchanged payload. The record layout never changed, only the discriminator did, so a body written through this
 * service is what 2026.2 stored, with real checksums, written by the very record machinery the engine uses.
 *
 * The service is an ordinary {@link DefaultEntityCollectionPersistenceService} whose
 * {@link DefaultEntityCollectionPersistenceService#createTypeKryoInstance()} - the method the constructor asks for its
 * Kryo - is overridden.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ReleasedAttributeValueImages {
	/**
	 * The `serialVersionUID` an {@link AttributeValue} carried in release 2026.2.
	 */
	static final long RELEASED_ATTRIBUTE_VALUE_UID = -5387437940533059959L;

	private ReleasedAttributeValueImages() {
	}

	/**
	 * Opens the collection, runs `work` on its service and closes everything it needed again.
	 *
	 * @param configuration       the configuration of the engine
	 * @param catalogName         the catalog
	 * @param catalogVersion      the catalog version to open the collection at
	 * @param catalogStoragePath  the folder of the catalog
	 * @param header              the reconciled header of the collection
	 * @param work                the work to run
	 * @param <T>                 the type of the result
	 * @return the result of the work
	 */
	static <T> T withReleasedBodies(
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
			DefaultEntityCollectionPersistenceService collection = new ReleasedBodiesCollectionService(
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

	/**
	 * The collection service whose Kryo writes {@link AttributeValue} with the 2026.2 version.
	 */
	private static final class ReleasedBodiesCollectionService extends DefaultEntityCollectionPersistenceService {

		ReleasedBodiesCollectionService(
			long catalogVersion,
			@Nonnull String catalogName,
			@Nonnull Path catalogStoragePath,
			@Nonnull EntityCollectionFileHeader header,
			@Nonnull StorageSettings settings,
			@Nonnull CatalogOffHeapMemoryManager offHeap,
			@Nonnull ObservableOutputKeeper outputKeeper
		) {
			super(
				catalogVersion, catalogName, catalogStoragePath, header, settings, offHeap, outputKeeper,
				new OffsetIndexRecordTypeRegistry(), null
			);
		}

		@Nonnull
		@Override
		public Function<VersionedKryoKeyInputs, VersionedKryo> createTypeKryoInstance() {
			final Function<VersionedKryoKeyInputs, VersionedKryo> production = super.createTypeKryoInstance();
			return inputs -> {
				final VersionedKryo kryo = production.apply(inputs);
				kryo.register(
					AttributeValue.class,
					new ReleasedAttributeValueSerializer(inputs.keyCompressor()),
					kryo.getRegistration(AttributeValue.class).getId()
				);
				return kryo;
			};
		}
	}

	/**
	 * Writes the version of release 2026.2 in front of the payload of the current serializer, and reads either
	 * version, whose payloads are the same.
	 */
	private static final class ReleasedAttributeValueSerializer extends Serializer<AttributeValue> {
		private final AttributeValueSerializer current;

		ReleasedAttributeValueSerializer(@Nonnull KeyCompressor keyCompressor) {
			this.current = new AttributeValueSerializer(keyCompressor);
		}

		@Override
		public void write(Kryo kryo, Output output, AttributeValue attributeValue) {
			output.writeLong(RELEASED_ATTRIBUTE_VALUE_UID);
			this.current.write(kryo, output, attributeValue);
		}

		@Override
		public AttributeValue read(Kryo kryo, Input input, Class<? extends AttributeValue> type) {
			input.readLong();
			return this.current.read(kryo, input, type);
		}
	}

}
