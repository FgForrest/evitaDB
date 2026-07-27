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

package io.evitadb.index.attribute;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.function.Functions;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.attribute.ChainIndex.ChainDescriptor;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.component.loader.AttributeIndexLoader;
import io.evitadb.index.component.loader.LoadContext;
import io.evitadb.index.component.loader.LoadedComponentBundle;
import io.evitadb.index.component.loader.LoadedComponentBundle.AttributeIndexes;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.KeyCompressorSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.SharedIndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService.FileLocationAndWrittenBytes;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end reload of a granular (`PAGED`) {@link ChainIndex} through the REAL production loader path: a large chain
 * is paged out as individual {@link ChainIndexLeafPagePart} leaf pages, written to a real on-disk {@link OffsetIndex}
 * (Kryo serialization + the byte-43 record type + a real {@code KeyCompressor}), reloaded, and reconstructed by
 * invoking {@link AttributeIndexLoader#load(LoadContext)} — which drives the `PAGED` branch of
 * {@code AttributeIndexLoader.fetchChain} for real: it resolves the CHAIN stream id from the sub-index identity through
 * the persisted key compressor and reads each listed leaf page back via {@code getStoragePart}, then reassembles the
 * chain boundary-stable via {@code ChainIndex.fromPersistedPages}.
 *
 * This closes the gap left by `ChainIndexTest.GranularFlushReloadRoundTripTest` (whose in-memory `ChainStore`
 * hand-replicates the fetch logic) and by `EntityIndexRoundTripTest` (whose chain reload shortcut only reconstructs the
 * inline SINGLE shape): here the reload goes through the production loader over real Kryo bytes. The
 * {@link OffsetIndexReadService} is NOT an in-memory shortcut — it is a thin read-only view over the real
 * {@link OffsetIndex}, forwarding the only two methods the loader calls ({@code getStoragePart} and
 * {@code getReadOnlyKeyCompressor}) to the real store so the byte-level page-read path is fully exercised.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
@Tag(ATTRIBUTE)
@DisplayName("Chain index granular paging reloads through the real AttributeIndexLoader + OffsetIndex")
class ChainIndexLoaderPagingRoundTripTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_INDEX_PK = 7;
	private static final AttributeIndexKey CHAIN_KEY = new AttributeIndexKey(null, "order", null);
	private static final EntityIndexKey ENTITY_INDEX_KEY = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);
	/** > 3 leaf pages (leaf capacity 1024) so the chain pages out across several leaves. */
	private static final int CHAIN_SIZE = 3200;
	private static final long PERSISTED_VERSION = 1L;
	private static final Consumer<NonFlushedBlock> NO_OP_NON_FLUSHED_BLOCK_CALLBACK = Functions.noOpConsumer();
	private static final Consumer<Optional<OffsetDateTime>> NO_OP_OLDEST_RECORD_CALLBACK = Functions.noOpConsumer();

	private final OffsetIndexRecordTypeRegistry recordRegistry = new OffsetIndexRecordTypeRegistry();
	private final StorageSettings storageSettings = new StorageSettings(
		StorageOptions.temporary(), TransactionOptions.builder().build()
	);
	private ObservableOutputKeeper observableOutputKeeper;
	private Path targetFile;

	@BeforeEach
	void setUp() throws Exception {
		this.observableOutputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
		this.targetFile = Files.createTempFile("chainIndexLoaderPagingRoundTrip", ".kryo");
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
		this.targetFile.toFile().delete();
	}

	@Test
	@DisplayName("a >1024-element PAGED chain reloads identically through the real fetchChain page-read path")
	void shouldReloadPagedChainThroughTheRealLoader() {
		final ChainIndex source = new ChainIndex(CHAIN_KEY);
		// one consistent head-first chain 1 -> 2 -> ... -> CHAIN_SIZE, large enough to page out
		for (int pk = 1; pk <= CHAIN_SIZE; pk++) {
			source.upsertPredecessor(pk == 1 ? new Predecessor() : new Predecessor(pk - 1), pk);
		}
		assertTrue(source.isConsistent(), "the source chain must be consistent");

		final List<StoragePart> emitted = emit(source);
		final ChainIndexStoragePart root = chainRoot(emitted);
		assertTrue(root.isPaged(), "the source chain must page out");
		assertTrue(leafPages(emitted).size() >= 3, "a paged chain must emit at least three leaf pages");

		final OffsetIndexDescriptor descriptor = persist(stripRemovals(emitted));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);

			// drive the REAL production loader over the real OffsetIndex bytes: it resolves the CHAIN stream id via the
			// persisted key compressor and reads every listed leaf page through getStoragePart, then reassembles the chain
			final StoragePartPersistenceService<StorageDescriptor> service = new OffsetIndexReadService(reloaded);
			final LoadedComponentBundle bundle = new AttributeIndexLoader().load(loadContext(service));
			final AttributeIndexes attributes = assertInstanceOf(
				AttributeIndexes.class, bundle, "the loader must return an AttributeIndexes bundle"
			);

			final ChainIndex restored = attributes.chainIndexes().get(CHAIN_KEY);
			assertNotNull(restored, "the loader must reconstruct the chain via the PAGED fetchChain branch");
			assertReloadIdentical(source, restored);

			// a clean reload leaves the index non-dirty: the first post-load flush must emit nothing
			final TrappedChanges reflush = new TrappedChanges();
			restored.appendStorageParts(ENTITY_INDEX_PK, reflush);
			assertEquals(
				0, reflush.getTrappedChangesCount(),
				"an untouched reloaded paged chain must emit nothing on its first flush"
			);
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	/*
		PRIVATE HELPERS
	 */

	/**
	 * Asserts the reconstructed chain is identical to the source: same physical element order, same chain head set and
	 * per-head descriptor, same predecessors, same successor inverse and the same consistency verdict.
	 */
	private static void assertReloadIdentical(@Nonnull ChainIndex expected, @Nonnull ChainIndex actual) {
		assertArrayEquals(
			expected.elements.getArray(), actual.elements.getArray(), "physical element order must round-trip"
		);
		assertEquals(expected.chains.keySet(), actual.chains.keySet(), "chain head set must round-trip");
		for (final Entry<Integer, ChainDescriptor> entry : expected.chains.entrySet()) {
			final Integer headPk = entry.getKey();
			assertEquals(
				entry.getValue(), actual.chains.get(headPk), "descriptor for head " + headPk
			);
		}
		assertEquals(
			expected.predecessors.keySet(), actual.predecessors.keySet(), "predecessor key set must round-trip"
		);
		for (final Entry<Integer, Integer> entry : expected.predecessors.entrySet()) {
			final Integer pk = entry.getKey();
			assertEquals(entry.getValue(), actual.predecessors.get(pk), "predecessor of " + pk);
		}
		assertEquals(
			expected.successorsByPredecessor.keySet(), actual.successorsByPredecessor.keySet(),
			"successor-inverse key set must round-trip"
		);
		for (final Entry<Integer, TransactionalBitmap> entry : expected.successorsByPredecessor.entrySet()) {
			final Integer predPk = entry.getKey();
			assertArrayEquals(
				entry.getValue().getArray(),
				actual.successorsByPredecessor.get(predPk).getArray(),
				"successor inverse for predecessor " + predPk
			);
		}
		assertEquals(expected.isConsistent(), actual.isConsistent(), "consistency verdict must round-trip");
	}

	/**
	 * Wraps `service` in a {@link LoadContext} whose manifest advertises exactly the seeded CHAIN key, mirroring the
	 * context the engine builds on catalog boot.
	 */
	@Nonnull
	private static LoadContext loadContext(@Nonnull StoragePartPersistenceService<StorageDescriptor> service) {
		final Set<AttributeIndexStorageKey> manifestKeys = Set.of(
			new AttributeIndexStorageKey(ENTITY_INDEX_KEY, AttributeIndexType.CHAIN, CHAIN_KEY)
		);
		final EntityIndexStoragePart manifest = new EntityIndexStoragePart(
			ENTITY_INDEX_PK, 1, ENTITY_INDEX_KEY,
			new BaseBitmap(), new HashMap<Locale, TransactionalBitmap>(0),
			manifestKeys, Set.of(), false, Set.of(), Set.of()
		);
		return new LoadContext(
			PERSISTED_VERSION,
			ENTITY_INDEX_PK,
			EntitySchema._internalBuild(ENTITY_TYPE),
			ENTITY_INDEX_KEY,
			manifest,
			manifest.getVersion(),
			EmptyBitmap.INSTANCE,
			Map.of(),
			service,
			null
		);
	}

	@Nonnull
	private static List<StoragePart> emit(@Nonnull ChainIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	@Nonnull
	private static List<StoragePart> stripRemovals(@Nonnull List<StoragePart> parts) {
		final List<StoragePart> result = new ArrayList<>(parts.size());
		for (final StoragePart part : parts) {
			if (!(part instanceof DeferredRemovalStoragePart)) {
				result.add(part);
			}
		}
		return result;
	}

	@Nonnull
	private static List<ChainIndexLeafPagePart> leafPages(@Nonnull List<StoragePart> parts) {
		final List<ChainIndexLeafPagePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof ChainIndexLeafPagePart leafPage) {
				result.add(leafPage);
			}
		}
		return result;
	}

	@Nonnull
	private static ChainIndexStoragePart chainRoot(@Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof ChainIndexStoragePart root) {
				return root;
			}
		}
		throw new IllegalStateException("The emission carries no ChainIndexStoragePart root!");
	}

	private static void writeEmission(
		@Nonnull OffsetIndex offsetIndex, long catalogVersion, @Nonnull List<StoragePart> parts
	) {
		for (final StoragePart part : parts) {
			if (part instanceof DeferredRemovalStoragePart deferredRemoval) {
				final long removedPartPK =
					deferredRemoval.computeUniquePartIdAndSet(offsetIndex.getReadOnlyKeyCompressor());
				offsetIndex.remove(catalogVersion, removedPartPK, deferredRemoval.removedContainerType());
			} else {
				offsetIndex.put(catalogVersion, part);
			}
		}
	}

	@Nonnull
	private OffsetIndex openWritableOffsetIndex() {
		return new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(new EntityCollectionFileHeader(ENTITY_TYPE, 1, 0), createKryo(), 1.0, 0L),
			this.storageSettings.outputBufferSize(),
			this.storageSettings.maxOpenedReadHandlesOrDefault(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			this.storageSettings,
			this.storageSettings,
			this.recordRegistry,
			createWriteHandle(),
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private OffsetIndexDescriptor persist(@Nonnull List<StoragePart> parts) {
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		try {
			writeEmission(offsetIndex, PERSISTED_VERSION, parts);
			return offsetIndex.flush(PERSISTED_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}
	}

	@Nonnull
	private OffsetIndex loadOffsetIndex(@Nonnull OffsetIndexDescriptor descriptor, long catalogVersion) {
		return new OffsetIndex(
			catalogVersion,
			new OffsetIndexDescriptor(
				new FileLocationAndWrittenBytes(descriptor.fileLocation(), 0),
				descriptor,
				1.0,
				descriptor.getFileSize()
			),
			this.storageSettings.outputBufferSize(),
			this.storageSettings.maxOpenedReadHandlesOrDefault(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			this.storageSettings,
			this.storageSettings,
			this.recordRegistry,
			createWriteHandle(),
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private WriteOnlyFileHandle createWriteHandle() {
		return new WriteOnlyFileHandle(
			this.targetFile,
			this.storageSettings.outputBufferSize(),
			this.storageSettings.syncWrites(),
			this.storageSettings,
			this.storageSettings,
			this.observableOutputKeeper
		);
	}

	@Nonnull
	private static Function<VersionedKryoKeyInputs, VersionedKryo> createKryo() {
		return keyInputs -> VersionedKryoFactory.createKryo(
			keyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SharedIndexStoragePartConfigurer.INSTANCE)
				.andThen(new IndexStoragePartConfigurer(keyInputs.keyCompressor()))
		);
	}

	/**
	 * Thin READ-ONLY {@link StoragePartPersistenceService} over a real {@link OffsetIndex}: it forwards the only two
	 * methods the loader calls — {@link #getStoragePart} and {@link #getReadOnlyKeyCompressor} — straight to the real
	 * store (so the byte-level Kryo page-read path is fully exercised) and fails loudly on everything else, so an
	 * accidental dependency on unimplemented behavior surfaces rather than silently returning a default.
	 */
	private record OffsetIndexReadService(@Nonnull OffsetIndex offsetIndex)
		implements StoragePartPersistenceService<StorageDescriptor> {

		@Nullable
		@Override
		public <T extends StoragePart> T getStoragePart(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			return this.offsetIndex.get(catalogVersion, storagePartPk, containerType);
		}

		@Nonnull
		@Override
		public KeyCompressor getReadOnlyKeyCompressor() {
			return this.offsetIndex.getReadOnlyKeyCompressor();
		}

		// --- the loader never calls anything below; fail loudly if that changes -------------------

		@Nonnull
		@Override
		public StoragePartPersistenceService<StorageDescriptor> createTransactionalService(@Nonnull UUID transactionId) {
			throw new UnsupportedOperationException();
		}

		@Nullable
		@Override
		public <T extends StoragePart> byte[] getStoragePartAsBinary(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> boolean removeStoragePart(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> boolean containsStoragePart(
			long catalogVersion, long primaryKey, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int countStorageParts(long catalogVersion) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> T deserializeStoragePart(
			@Nonnull byte[] storagePart, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public KeyCompressorSnapshot getKeyCompressorSnapshot() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getVersion() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void forgetVolatileData() {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public StorageDescriptor flush(long catalogVersion) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public StorageDescriptor copySnapshotTo(
			long catalogVersion, @Nonnull OutputStream outputStream,
			@Nullable IntConsumer progressConsumer, @Nullable StoragePart... updatedStorageParts
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isNew() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isClosed() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
			throw new UnsupportedOperationException();
		}
	}
}
