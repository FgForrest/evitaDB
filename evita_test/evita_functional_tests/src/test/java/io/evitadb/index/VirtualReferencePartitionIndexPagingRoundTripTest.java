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

package io.evitadb.index;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.SortKeyFixture;
import io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.SortSnapshot;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndexView;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.index.component.VirtualReferencePartitionComponent;
import io.evitadb.index.component.loader.LoadContext;
import io.evitadb.index.component.loader.LoadedComponentBundle;
import io.evitadb.index.component.loader.LoadedComponentBundle.VirtualReferencePartitions;
import io.evitadb.index.component.loader.VirtualReferencePartitionLoader;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.KeyCompressorSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.SortValueSlot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionRootRemoval;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the granular persistence of {@link VirtualReferencePartitionIndex} end to end: the emitted parts are
 * written through the real Kryo serializers into a real {@link OffsetIndex}, reopened from disk and reloaded through
 * the production {@link VirtualReferencePartitionLoader}. Covers the PAGED and SINGLE shapes, the write bound of a
 * reloaded index (one changed entry costs one leaf page and no root), the root rewrite a new dictionary tuple forces,
 * a collapse from PAGED to SINGLE, an index emptied after a flush, and the absent root that every catalog without a
 * virtual partition has. The sort value slots are covered too: the sort indexes rebuilt on load equal the live ones
 * after random churn in warm-up and in transactions, a sort value change costs one leaf page, a retired key leaves
 * nothing behind, and a value tree shared with real partitions cannot reach the rebuilt sort index. Dropping an index
 * reclaims its root and every leaf page it has on disk, published or not, a root naming a leaf page the store lacks
 * fails the load, and the reload plan of the referenced type entity index carries the persisted partitions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(INDEXING)
@Tag(REFERENCE)
@DisplayName("Virtual reference partition index paging round-trips through the real OffsetIndex and loader")
class VirtualReferencePartitionIndexPagingRoundTripTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_INDEX_PK = 7;
	private static final String REFERENCE_NAME = "media";
	private static final EntityIndexKey ENTITY_INDEX_KEY =
		new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME);
	/**
	 * Partitions of the large scenario - each holds an owner and a tuple entry, so the tree holds twice as many
	 * entries and spans dozens of leaves.
	 */
	private static final int PARTITION_COUNT = 5_000;
	/**
	 * Partitions kept after the collapse scenario shrinks the index - two entries each, well within one leaf.
	 */
	private static final int COLLAPSE_KEEP = 40;
	/**
	 * The representative tuples the partitions cycle through - mixed value types and a `null` value included.
	 */
	private static final Serializable[][] TUPLES = {
		{"gallery"},
		{"thumbnail"},
		{"video", 7, new BigDecimal("1.50"), null}
	};
	private static final Consumer<NonFlushedBlock> NO_OP_NON_FLUSHED_BLOCK_CALLBACK = Functions.noOpConsumer();
	private static final Consumer<Optional<OffsetDateTime>> NO_OP_OLDEST_RECORD_CALLBACK = Functions.noOpConsumer();

	private final OffsetIndexRecordTypeRegistry recordRegistry = new OffsetIndexRecordTypeRegistry();
	private final StorageSettings storageSettings = new StorageSettings(
		StorageOptions.temporary(), TransactionOptions.builder().build()
	);
	private ObservableOutputKeeper observableOutputKeeper;
	private Path targetFile;
	private OffsetIndex offsetIndex;
	private long version;

	@BeforeEach
	void setUp() throws Exception {
		this.observableOutputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
		this.targetFile = Files.createTempFile("virtualReferencePartitionPagingRoundTrip", ".kryo");
		this.offsetIndex = new OffsetIndex(
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
		this.version = 0L;
	}

	@AfterEach
	void tearDown() {
		IOUtils.closeQuietly(this.offsetIndex::close);
		this.observableOutputKeeper.close();
		this.targetFile.toFile().delete();
	}

	@Test
	@DisplayName("a multi-leaf index pages out and reloads with the same content and leaf boundaries")
	void shouldRoundTripPagedIndex() {
		final VirtualReferencePartitionIndex source = populated(PARTITION_COUNT);
		final List<StoragePart> emission = flushAndPersist(source);
		final VirtualReferencePartitionIndexStoragePart root = singleRoot(emission);
		assertTrue(root.isPaged(), "Ten thousand entries must page out");
		assertTrue(countLeafPages(emission) > 2, "A paged index writes several leaf pages");
		assertEquals(TUPLES.length, root.getTupleIds().length, "Every distinct tuple is stored once");

		final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();
		assertEquals(
			VirtualReferencePartitionIndexTest.contentOf(source),
			VirtualReferencePartitionIndexTest.contentOf(reloaded)
		);
		assertTrue(flush(reloaded).isEmpty(), "A freshly reloaded index is clean and emits nothing");

		// the user counts were rebuilt from the entries: every partition of a tuple must go before the tuple does
		final int galleryId = reloaded.getTupleId(TUPLES[0]);
		for (int partition = 1; partition <= PARTITION_COUNT; partition++) {
			if (reloaded.getRepresentativeTupleId(partition) == galleryId) {
				assertEquals(galleryId, reloaded.getTupleId(TUPLES[0]), "The tuple must outlive all but its last user");
				reloaded.removeRepresentativeTuple(partition);
			}
		}
		assertEquals(VirtualReferencePartitionIndex.NO_TUPLE_ID, reloaded.getTupleId(TUPLES[0]));
	}

	@Test
	@DisplayName("a reloaded index rewrites one leaf for a one-entry change, and the root only for a new tuple")
	void shouldBoundTheWriteOfAOneEntryChange() {
		final VirtualReferencePartitionIndex source = populated(PARTITION_COUNT);
		final VirtualReferencePartitionIndexStoragePart persistedRoot = singleRoot(flushAndPersist(source));
		final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();

		// a new owner of an existing partition touches exactly one leaf
		final int touchedPartition = PARTITION_COUNT / 2;
		reloaded.putPartitionOwner(touchedPartition, -42, Integer.MIN_VALUE);
		final List<StoragePart> ownerChange = flush(reloaded);
		reloaded.resetDirty();
		assertEquals(1, countLeafPages(ownerChange), "One changed entry rewrites one leaf: " + ownerChange);
		assertEquals(0, countRoots(ownerChange), "An unchanged leaf list and dictionary keep the root: " + ownerChange);
		assertEquals(0, countLeafPageRemovals(ownerChange));

		// the leaf boundaries survived the reload: the rewritten page is a persisted one, with its persisted keys
		final VirtualReferencePartitionIndexLeafPagePart changedPage = singleLeafPage(ownerChange);
		assertTrue(
			Arrays.stream(persistedRoot.getLeafPageSequences()).anyMatch(it -> it == changedPage.getPageSequence()),
			"The changed leaf must keep its persisted page sequence"
		);
		final VirtualReferencePartitionIndexLeafPagePart persistedPage = readLeafPage(changedPage.getPageSequence());
		assertArrayEquals(
			persistedPage.getKeys(), changedPage.getKeys(), "The leaf must keep its persisted boundaries"
		);

		// a tuple the dictionary does not know yet rewrites the root as well
		final Serializable[] poster = {"poster"};
		reloaded.putRepresentativeTuple(touchedPartition, poster);
		final List<StoragePart> tupleChange = flush(reloaded);
		reloaded.resetDirty();
		assertEquals(1, countLeafPages(tupleChange), "One changed entry rewrites one leaf: " + tupleChange);
		final VirtualReferencePartitionIndexStoragePart newRoot = singleRoot(tupleChange);
		assertTrue(newRoot.isPaged());
		assertEquals(TUPLES.length + 1, newRoot.getTupleIds().length, "The root must carry the new tuple");
		assertArrayEquals(persistedRoot.getLeafPageSequences(), newRoot.getLeafPageSequences());

		// and both changes reload intact
		persist(ownerChange);
		persist(tupleChange);
		final VirtualReferencePartitionIndex reloadedAgain = reloadThroughLoader();
		assertEquals(
			VirtualReferencePartitionIndexTest.contentOf(reloaded),
			VirtualReferencePartitionIndexTest.contentOf(reloadedAgain)
		);
		assertArrayEquals(poster, reloadedAgain.getTuple(reloadedAgain.getRepresentativeTupleId(touchedPartition)));
	}

	@Test
	@DisplayName("a small index persists inline and reloads with extreme owners and rebuilt user counts")
	void shouldRoundTripSingleIndex() {
		final VirtualReferencePartitionIndex source = new VirtualReferencePartitionIndex();
		final int[] extremes = {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE};
		int partition = 1;
		for (final int owner : extremes) {
			for (final int target : extremes) {
				source.putPartitionOwner(partition, owner, target);
				source.putRepresentativeTuple(partition, TUPLES[partition % TUPLES.length]);
				partition++;
			}
		}
		final VirtualReferencePartitionIndexStoragePart root = singleRoot(flushAndPersist(source));
		assertFalse(root.isPaged(), "Thirty-two entries fit one leaf");

		final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();
		assertEquals(
			VirtualReferencePartitionIndexTest.contentOf(source),
			VirtualReferencePartitionIndexTest.contentOf(reloaded)
		);

		// partitions 2, 5, ... 14 share the third tuple; it must survive all removals but the last
		final int compoundId = reloaded.getTupleId(TUPLES[2]);
		reloaded.removeRepresentativeTuple(2);
		assertEquals(compoundId, reloaded.getTupleId(TUPLES[2]), "The reloaded user count must be more than one");
	}

	@Test
	@DisplayName("a PAGED index collapsing to SINGLE removes every page the previous flush wrote")
	void shouldRemoveEveryPriorPageOnCollapse() {
		final VirtualReferencePartitionIndex source = populated(PARTITION_COUNT);
		final VirtualReferencePartitionIndexStoragePart pagedRoot = singleRoot(flushAndPersist(source));
		final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();

		for (int partition = COLLAPSE_KEEP + 1; partition <= PARTITION_COUNT; partition++) {
			reloaded.removePartitionOwner(partition);
			reloaded.removeRepresentativeTuple(partition);
		}
		final List<StoragePart> collapse = flush(reloaded);
		reloaded.resetDirty();
		assertFalse(singleRoot(collapse).isPaged(), "The shrunken index must persist inline");
		assertEquals(0, countLeafPages(collapse));
		assertEquals(pagedRoot.getLeafPageSequences().length, countLeafPageRemovals(collapse));

		persist(collapse);
		for (final int pageSequence : pagedRoot.getLeafPageSequences()) {
			assertNull(readLeafPageOrNull(pageSequence), "Page " + pageSequence + " must be gone from storage");
		}
		assertEquals(
			VirtualReferencePartitionIndexTest.contentOf(reloaded),
			VirtualReferencePartitionIndexTest.contentOf(reloadThroughLoader())
		);
	}

	@Test
	@DisplayName("an index emptied after a flush leaves no root and no page behind and reloads empty")
	void shouldLeaveNothingBehindWhenEmptied() {
		final VirtualReferencePartitionIndex source = populated(PARTITION_COUNT);
		final VirtualReferencePartitionIndexStoragePart pagedRoot = singleRoot(flushAndPersist(source));
		final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();

		for (int partition = 1; partition <= PARTITION_COUNT; partition++) {
			reloaded.removePartitionOwner(partition);
			reloaded.removeRepresentativeTuple(partition);
		}
		final List<StoragePart> emptied = flush(reloaded);
		assertEquals(0, countRoots(emptied), "An empty index writes no root: " + emptied);
		assertEquals(0, countLeafPages(emptied));
		assertEquals(pagedRoot.getLeafPageSequences().length, countLeafPageRemovals(emptied));
		assertEquals(1, emptied.stream().filter(VirtualReferencePartitionRootRemoval.class::isInstance).count());

		persist(emptied);
		assertNull(readRootOrNull(), "The root must be gone from storage");
		for (final int pageSequence : pagedRoot.getLeafPageSequences()) {
			assertNull(readLeafPageOrNull(pageSequence), "Page " + pageSequence + " must be gone from storage");
		}
		assertTrue(reloadThroughLoader().isEmpty());
	}

	@Test
	@DisplayName("a store without a root loads an empty index, whether or not it knows the reference name")
	void shouldLoadAnAbsentRootAsAnEmptyIndex() {
		// a store that never saw the reference name at all
		assertTrue(
			this.offsetIndex.getReadOnlyKeyCompressor().getIdIfExists(new ReferenceNameKey(REFERENCE_NAME)).isEmpty()
		);
		final VirtualReferencePartitionIndex fromBlankStore = reloadThroughLoader();
		assertTrue(fromBlankStore.isEmpty());
		assertTrue(flush(fromBlankStore).isEmpty());

		// a store whose reference-type cardinality root registered the reference name, but holds no root of ours
		final ReferenceTypeCardinalityIndex cardinality = new ReferenceTypeCardinalityIndex();
		cardinality.addRecord(1, 100);
		final TrappedChanges cardinalityChanges = new TrappedChanges();
		cardinality.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, cardinalityChanges);
		persist(drain(cardinalityChanges));
		assertTrue(
			this.offsetIndex.getReadOnlyKeyCompressor().getIdIfExists(new ReferenceNameKey(REFERENCE_NAME)).isPresent()
		);
		assertTrue(reloadThroughLoader().isEmpty());
	}

	@Test
	@DisplayName("a paged root naming a leaf page the store lacks fails the load and names the page")
	void shouldRefuseToLoadWhenALeafPageIsMissing() {
		final VirtualReferencePartitionIndexStoragePart pagedRoot =
			singleRoot(flushAndPersist(populated(PARTITION_COUNT)));
		final int[] pageSequences = pagedRoot.getLeafPageSequences();
		final int missingPage = pageSequences[pageSequences.length / 2];
		persist(
			List.of(new VirtualReferencePartitionIndexLeafPageRemoval(ENTITY_INDEX_PK, REFERENCE_NAME, missingPage))
		);
		assertNull(readLeafPageOrNull(missingPage), "The page must be gone before the load is attempted");

		final GenericEvitaInternalError error =
			assertThrows(GenericEvitaInternalError.class, this::reloadThroughLoader);
		assertTrue(
			error.getMessage().contains("Leaf page " + missingPage + " "),
			"The error must name the missing page: " + error.getMessage()
		);
	}

	@Test
	@DisplayName("the referenced type index reload plan carries the persisted virtual partitions")
	void shouldReloadTheTypeIndexWithItsVirtualPartitions() {
		final ReferencedTypeEntityIndex typeIndex =
			new ReferencedTypeEntityIndex(ENTITY_INDEX_PK, ENTITY_TYPE, ENTITY_INDEX_KEY);
		// the membership makes the type index write its manifest, ids and cardinality root, as a real one does
		typeIndex.insertPrimaryKeyIfMissing(1, 100);
		final VirtualReferencePartitionIndex live = typeIndex.getVirtualReferencePartitionIndex();
		final AttributeIndexKey order = VirtualReferencePartitionIndexSortValueTest.ORDER;
		final AttributeIndexKey chain = VirtualReferencePartitionIndexChainPredecessorTest.CHAIN;
		live.declareSortKey(order, VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC, 0);
		live.declareChainKey(chain);
		final int partitions = 300;
		for (int partition = 1; partition <= partitions; partition++) {
			live.putPartitionOwner(partition, partition % 13, 1_000 + partition);
			live.putRepresentativeTuple(partition, TUPLES[partition % TUPLES.length]);
			live.putSortValue(order, partition, partition % 17);
			if (partition % 5 != 0) {
				live.putChainPredecessor(chain, partition, partition - 1);
			}
		}

		final TrappedChanges changes = new TrappedChanges();
		typeIndex.getModifiedStorageParts(changes);
		final List<StoragePart> emission = drain(changes);
		assertTrue(singleRoot(emission).isPaged(), "The virtual partitions must page out through the type index");
		persist(emission);

		final ReferencedTypeEntityIndex reloadedTypeIndex = reloadTypeIndexThroughPlan();
		final VirtualReferencePartitionIndex reloaded = reloadedTypeIndex.getVirtualReferencePartitionIndex();
		assertFalse(reloaded.isEmpty(), "The reload plan must hand the type index the persisted virtual partitions");
		assertEquals(
			VirtualReferencePartitionIndexTest.contentOf(live),
			VirtualReferencePartitionIndexTest.contentOf(reloaded)
		);
		assertEquals(
			VirtualReferencePartitionIndexSortValueTest.snapshotOf(live.getSortIndex(order)),
			VirtualReferencePartitionIndexSortValueTest.snapshotOf(reloaded.getSortIndex(order))
		);
		for (int partition = 1; partition <= partitions; partition++) {
			assertEquals(
				live.getChainPredecessor(chain, partition), reloaded.getChainPredecessor(chain, partition),
				"Predecessor of partition " + partition
			);
		}
	}

	@Nested
	@DisplayName("Reclaiming a dropped index")
	class ReclaimingADroppedIndex {

		/**
		 * Emits the removals of the persisted footprint of the index, as dropping its owning entity index does.
		 *
		 * @param index the index being dropped
		 * @return the emitted removals
		 */
		@Nonnull
		private List<StoragePart> drop(@Nonnull VirtualReferencePartitionIndex index) {
			final TrappedChanges sink = new TrappedChanges();
			new VirtualReferencePartitionComponent(index, REFERENCE_NAME)
				.emitPersistedFootprintRemovals(ENTITY_INDEX_PK, sink);
			return drain(sink);
		}

		/**
		 * Asserts the removals reclaim the root and every leaf page of the persisted root, and that once persisted
		 * nothing of the index is left in the store.
		 *
		 * @param pagedRoot the persisted root
		 * @param removals  the removals emitted by the drop
		 */
		private void assertReclaimsEverything(
			@Nonnull VirtualReferencePartitionIndexStoragePart pagedRoot,
			@Nonnull List<StoragePart> removals
		) {
			final int[] pageSequences = pagedRoot.getLeafPageSequences();
			assertEquals(pageSequences.length, countLeafPageRemovals(removals), "Every leaf page: " + removals);
			assertEquals(
				1, removals.stream().filter(VirtualReferencePartitionRootRemoval.class::isInstance).count(),
				"The root: " + removals
			);
			assertEquals(pageSequences.length + 1, removals.size(), "Nothing else: " + removals);

			persist(removals);
			assertNull(readRootOrNull(), "The root must be gone from storage");
			for (final int pageSequence : pageSequences) {
				assertNull(readLeafPageOrNull(pageSequence), "Page " + pageSequence + " must be gone from storage");
			}
			assertTrue(reloadThroughLoader().isEmpty());
		}

		@Test
		@DisplayName("dropping a reloaded paged index reclaims the root and every leaf page")
		void shouldReclaimEveryPageAndTheRootOfADroppedPagedIndex() {
			final VirtualReferencePartitionIndexStoragePart pagedRoot =
				singleRoot(flushAndPersist(populated(PARTITION_COUNT)));
			assertTrue(pagedRoot.isPaged());

			assertReclaimsEverything(pagedRoot, drop(reloadThroughLoader()));
		}

		@Test
		@DisplayName("dropping an index right after an unpublished flush reclaims the pages that flush wrote")
		void shouldReclaimThePagesOfAnUnpublishedFlush() {
			final VirtualReferencePartitionIndex source = populated(PARTITION_COUNT);
			final VirtualReferencePartitionIndexStoragePart pagedRoot = singleRoot(flushAndPersist(source));
			assertTrue(pagedRoot.isPaged());

			// no later flush or commit merge published the page set the flush staged
			assertReclaimsEverything(pagedRoot, drop(source));
		}

		@Test
		@DisplayName("the root removal of a never-persisted index resolves to nothing on a store without its reference")
		void shouldResolveTheRootRemovalOfANeverPersistedIndexOnABlankStore() {
			assertTrue(
				VirtualReferencePartitionIndexPagingRoundTripTest.this.offsetIndex.getReadOnlyKeyCompressor()
					.getIdIfExists(new ReferenceNameKey(REFERENCE_NAME)).isEmpty(),
				"The store must never have seen the reference name"
			);
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.putPartitionOwner(1, 10, 100);
			index.removePartitionOwner(1);

			final List<StoragePart> emission = flush(index);
			assertEquals(1, emission.size(), "A never-flushed emptied index removes its root only: " + emission);
			assertInstanceOf(VirtualReferencePartitionRootRemoval.class, emission.get(0));
			persist(emission);
			assertTrue(reloadThroughLoader().isEmpty());

			// dropping the never-persisted index resolves the same way
			persist(drop(index));
			assertTrue(reloadThroughLoader().isEmpty());
		}
	}

	@Nested
	@DisplayName("Sort value slots")
	class SortValueSlots {
		/**
		 * Partitions the sort value churn draws from - enough for the entries of all sort keys to span many leaves.
		 */
		private static final int SORT_PARTITIONS = 1_500;

		/**
		 * Asserts every fixture's sort index of the reloaded index equals the live one exactly - records, values and
		 * blocks - and follows the model.
		 *
		 * @param live     the index the parts were flushed from
		 * @param reloaded the index reloaded from the persisted parts
		 * @param models   the raw value of every partition per sort key
		 */
		private static void assertRebuiltEqualsLive(
			@Nonnull VirtualReferencePartitionIndex live,
			@Nonnull VirtualReferencePartitionIndex reloaded,
			@Nonnull Map<AttributeIndexKey, Map<Integer, Serializable>> models
		) {
			for (final SortKeyFixture fixture : VirtualReferencePartitionIndexSortValueTest.FIXTURES) {
				final OwnerSortIndex liveIndex = live.getSortIndex(fixture.key());
				final OwnerSortIndex reloadedIndex = reloaded.getSortIndex(fixture.key());
				assertNotNull(liveIndex, "Sort key " + fixture + " must be declared on the live index");
				assertNotNull(reloadedIndex, "Sort key " + fixture + " must be declared on the reloaded index");
				assertEquals(
					VirtualReferencePartitionIndexSortValueTest.snapshotOf(liveIndex),
					VirtualReferencePartitionIndexSortValueTest.snapshotOf(reloadedIndex),
					"The rebuilt sort index of " + fixture + " must equal the live one"
				);
				VirtualReferencePartitionIndexSortValueTest.assertMatchesModel(
					fixture, reloadedIndex, models.getOrDefault(fixture.key(), Map.of())
				);
			}
		}

		@Test
		@DisplayName("sort indexes rebuilt on load equal the live ones after warm-up churn, and keep doing so")
		void shouldRebuildEverySortIndexAfterWarmUpChurn() {
			final VirtualReferencePartitionIndex live = VirtualReferencePartitionIndexSortValueTest.declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			final Random random = new Random(1615);
			for (int partition = 1; partition <= SORT_PARTITIONS; partition += 3) {
				live.putPartitionOwner(partition, -partition, partition);
			}
			for (int round = 0; round < 4; round++) {
				VirtualReferencePartitionIndexSortValueTest.churn(live, models, random, 4_000, SORT_PARTITIONS);
				flushAndPersist(live);
			}
			final VirtualReferencePartitionIndexStoragePart root = readRootOrNull();
			assertNotNull(root);
			assertTrue(root.isPaged(), "The sort value entries must page out");

			final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();
			assertRebuiltEqualsLive(live, reloaded, models);
			assertEquals(
				VirtualReferencePartitionIndexTest.contentOf(live), VirtualReferencePartitionIndexTest.contentOf(reloaded)
			);
			assertTrue(flush(reloaded).isEmpty(), "A freshly reloaded index is clean and emits nothing");

			// the reloaded index goes on from where the live one stopped - same changes on both, flushed from the reload
			final Map<AttributeIndexKey, Map<Integer, Serializable>> liveModels =
				VirtualReferencePartitionIndexSortValueTest.copyOf(models);
			VirtualReferencePartitionIndexSortValueTest.churn(live, liveModels, new Random(7), 2_000, SORT_PARTITIONS);
			VirtualReferencePartitionIndexSortValueTest.churn(reloaded, models, new Random(7), 2_000, SORT_PARTITIONS);
			assertRebuiltEqualsLive(live, reloaded, models);
			flushAndPersist(reloaded);
			assertRebuiltEqualsLive(live, reloadThroughLoader(), models);
		}

		@Test
		@Tag(TRANSACTION)
		@DisplayName("sort indexes rebuilt on load equal the committed ones after transactional churn")
		void shouldRebuildEverySortIndexAfterTransactionalChurn() {
			final VirtualReferencePartitionIndex initial = VirtualReferencePartitionIndexSortValueTest.declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			final Random random = new Random(42);
			VirtualReferencePartitionIndexSortValueTest.churn(initial, models, random, 4_000, SORT_PARTITIONS);
			flushAndPersist(initial);

			final VirtualReferencePartitionIndex[] current = {reloadThroughLoader()};
			for (int round = 0; round < 3; round++) {
				assertStateAfterCommit(
					current[0],
					original -> {
						VirtualReferencePartitionIndexSortValueTest.churn(original, models, random, 1_500, SORT_PARTITIONS);
						// the flush runs inside the transaction, before the commit merges it - as the catalog flushes
						persist(flush(original));
					},
					(original, committed) -> current[0] = committed
				);
				assertRebuiltEqualsLive(current[0], reloadThroughLoader(), models);
			}
		}

		@Test
		@DisplayName("a sort value change rewrites one leaf, the root only when a value appears or disappears")
		void shouldBoundTheWriteOfASortValueChange() {
			final AttributeIndexKey order = VirtualReferencePartitionIndexSortValueTest.ORDER;
			final VirtualReferencePartitionIndex source = new VirtualReferencePartitionIndex();
			source.declareSortKey(order, VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC, 0);
			for (int partition = 1; partition <= PARTITION_COUNT; partition++) {
				source.putSortValue(order, partition, 1 + partition % 77);
			}
			final VirtualReferencePartitionIndexStoragePart persistedRoot = singleRoot(flushAndPersist(source));
			assertTrue(persistedRoot.isPaged());
			final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();

			// partition 2000 holds 1 + 2000 % 77 = 76 - moving it to another existing value touches one leaf only
			final int touchedPartition = 2_000;
			reloaded.putSortValue(order, touchedPartition, 3);
			final List<StoragePart> existingValueChange = flush(reloaded);
			reloaded.resetDirty();
			assertEquals(1, countLeafPages(existingValueChange), "One changed entry rewrites one leaf: " + existingValueChange);
			assertEquals(0, countRoots(existingValueChange), "An existing value must keep the root: " + existingValueChange);
			assertEquals(0, countLeafPageRemovals(existingValueChange));

			// a value the dictionary does not know yet rewrites the root as well
			reloaded.putSortValue(order, touchedPartition, 1_000);
			final List<StoragePart> newValueChange = flush(reloaded);
			reloaded.resetDirty();
			assertEquals(1, countLeafPages(newValueChange), "One changed entry rewrites one leaf: " + newValueChange);
			final VirtualReferencePartitionIndexStoragePart newRoot = singleRoot(newValueChange);
			assertEquals(78, newRoot.getSortValueSlots()[0].values().length, "The root must carry the new value");
			assertArrayEquals(persistedRoot.getLeafPageSequences(), newRoot.getLeafPageSequences());

			// and the root goes again once the value loses its only partition
			reloaded.removeSortValue(order, touchedPartition);
			final List<StoragePart> lastUserRemoval = flush(reloaded);
			reloaded.resetDirty();
			assertEquals(1, countLeafPages(lastUserRemoval));
			assertEquals(77, singleRoot(lastUserRemoval).getSortValueSlots()[0].values().length);

			persist(existingValueChange);
			persist(newValueChange);
			persist(lastUserRemoval);
			assertEquals(
				VirtualReferencePartitionIndexSortValueTest.snapshotOf(reloaded.getSortIndex(order)),
				VirtualReferencePartitionIndexSortValueTest.snapshotOf(reloadThroughLoader().getSortIndex(order))
			);
		}

		@Test
		@DisplayName("a value tree shared with real partitions cannot shift the blocks of the rebuilt sort index")
		void shouldRebuildAnOwnerSortIndexBesideASharedValueTree() {
			final AttributeIndexKey priority = new AttributeIndexKey(REFERENCE_NAME, "priority", null);
			final SortIndex.ComparatorSource[] integerAsc = VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC;
			// the filter value tree of a filterable and sortable attribute at the reference level holds every partition
			// with a value: the real partitions 1 and 2 as well as the virtual ones
			final InvertedIndex sharedTree = new InvertedIndex(
				Integer.class, Serializable.class::cast, SortIndex.createComparatorFor(null, integerAsc[0])
			);
			sharedTree.addRecord(5, 1);
			sharedTree.addRecord(5, 2);
			sharedTree.addRecord(5, 3);
			sharedTree.addRecord(5, 4);
			sharedTree.addRecord(7, 6);

			final VirtualReferencePartitionIndex source = new VirtualReferencePartitionIndex();
			source.declareSortKey(priority, integerAsc, 0);
			source.putSortValue(priority, 3, 5);
			source.putSortValue(priority, 4, 5);
			source.putSortValue(priority, 6, 7);
			flushAndPersist(source);

			final OwnerSortIndex rebuilt = reloadThroughLoader().getSortIndex(priority);
			assertNotNull(rebuilt);
			final SortSnapshot expected = new SortSnapshot(List.of(3, 4, 6), List.of(5, 7), List.of(2, 1));
			assertEquals(expected, VirtualReferencePartitionIndexSortValueTest.snapshotOf(source.getSortIndex(priority)));
			assertEquals(expected, VirtualReferencePartitionIndexSortValueTest.snapshotOf(rebuilt));

			// the negative control: the same persisted blocks folded onto the shared tree as a view take their block
			// boundaries from the real partitions too
			final SortIndex view = SortIndex.create(
				integerAsc, null, priority, 0, rebuilt.getSortedRecords(), rebuilt.getSortedRecordValues(),
				Map.<Serializable, Integer>of(5, 2), () -> sharedTree
			);
			assertInstanceOf(SortIndexView.class, view);
			assertNotEquals(
				rebuilt.getRecordsEqualTo(5).size(), view.getRecordsEqualTo(5).size(),
				"A view counts the real partitions of the shared value as its own"
			);
		}

		@Test
		@DisplayName("a retired sort key leaves no entry, no value and no slot behind on disk")
		void shouldLeaveNothingOfARetiredKeyOnDisk() {
			final VirtualReferencePartitionIndex live = VirtualReferencePartitionIndexSortValueTest.declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			VirtualReferencePartitionIndexSortValueTest.churn(live, models, new Random(3), 6_000, SORT_PARTITIONS);
			flushAndPersist(live);
			final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();

			final SortKeyFixture retired = VirtualReferencePartitionIndexSortValueTest.FIXTURES.get(0);
			assertTrue(reloaded.retireSortKey(retired.key()));
			final List<StoragePart> retirement = flush(reloaded);
			reloaded.resetDirty();
			assertTrue(
				Arrays.stream(singleRoot(retirement).getSortValueSlots())
					.map(SortValueSlot::attributeIndexKey)
					.noneMatch(retired.key()::equals),
				"The root must drop the retired slot"
			);
			persist(retirement);

			// the loader refuses entries of a slot the root does not declare, so a clean load proves the reclaim
			final VirtualReferencePartitionIndex afterRetirement = reloadThroughLoader();
			assertNull(afterRetirement.getSortIndex(retired.key()));
			for (final SortKeyFixture fixture : VirtualReferencePartitionIndexSortValueTest.FIXTURES.subList(
				1, VirtualReferencePartitionIndexSortValueTest.FIXTURES.size()
			)) {
				assertEquals(
					VirtualReferencePartitionIndexSortValueTest.snapshotOf(live.getSortIndex(fixture.key())),
					VirtualReferencePartitionIndexSortValueTest.snapshotOf(afterRetirement.getSortIndex(fixture.key()))
				);
			}
		}

		@Test
		@DisplayName("a declared key without a value persists and reloads, and its retirement leaves an empty index")
		void shouldRoundTripADeclaredKeyWithoutValues() {
			final AttributeIndexKey order = VirtualReferencePartitionIndexSortValueTest.ORDER;
			final VirtualReferencePartitionIndex source = new VirtualReferencePartitionIndex();
			source.declareSortKey(order, VirtualReferencePartitionIndexSortValueTest.INTEGER_ASC, 0);
			final VirtualReferencePartitionIndexStoragePart root = singleRoot(flushAndPersist(source));
			assertFalse(root.isPaged());
			assertEquals(0, root.getKeys().length);

			final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();
			final OwnerSortIndex sortIndex = reloaded.getSortIndex(order);
			assertNotNull(sortIndex);
			assertTrue(sortIndex.isEmpty());

			reloaded.retireSortKey(order);
			final List<StoragePart> retirement = flush(reloaded);
			assertEquals(0, countRoots(retirement));
			assertEquals(1, retirement.stream().filter(VirtualReferencePartitionRootRemoval.class::isInstance).count());
			persist(retirement);
			assertTrue(reloadThroughLoader().isEmpty());
		}
	}

	@Nested
	@DisplayName("Chain predecessor slots")
	class ChainPredecessorSlots {
		private static final AttributeIndexKey CHAIN = VirtualReferencePartitionIndexChainPredecessorTest.CHAIN;
		private static final AttributeIndexKey LOCALIZED_CHAIN =
			VirtualReferencePartitionIndexChainPredecessorTest.LOCALIZED_CHAIN;
		/**
		 * Owners and rows per owner of the large fixture - 29,280 single-owner partitions, the size of the largest
		 * chained reference in the census.
		 */
		private static final int OWNERS = 120;
		private static final int ROWS_PER_OWNER = 244;
		private static final int PARTITIONS = OWNERS * ROWS_PER_OWNER;

		@Test
		@DisplayName("chains reload exactly - every chained partition with its predecessor, every other one unchained")
		void shouldReloadChainsExactly() {
			final VirtualReferencePartitionIndex live = VirtualReferencePartitionIndexChainPredecessorTest.declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models =
				VirtualReferencePartitionIndexChainPredecessorTest.populate(live, OWNERS, ROWS_PER_OWNER);
			final VirtualReferencePartitionIndexStoragePart root = singleRoot(flushAndPersist(live));
			assertTrue(root.isPaged());
			assertEquals(2, root.getChainPredecessorSlots().length);

			final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();
			VirtualReferencePartitionIndexChainPredecessorTest.assertMatchesModels(reloaded, models, PARTITIONS);
			assertEquals(
				VirtualReferencePartitionIndexTest.contentOf(live), VirtualReferencePartitionIndexTest.contentOf(reloaded)
			);
			assertTrue(flush(reloaded).isEmpty(), "A freshly reloaded index is clean and emits nothing");

			// the reloaded index goes on from where it stopped
			VirtualReferencePartitionIndexChainPredecessorTest.churn(reloaded, models, new Random(29), 3_000, PARTITIONS);
			flushAndPersist(reloaded);
			VirtualReferencePartitionIndexChainPredecessorTest.assertMatchesModels(
				reloadThroughLoader(), models, PARTITIONS
			);
		}

		@Test
		@DisplayName("one chained attribute of the census size pages out into a bounded number of leaves")
		void shouldPageOutOneChainIntoBoundedLeaves() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareChainKey(CHAIN);
			// 29,272 chained partitions - the parameter values of the production retail corpus
			for (int partition = 1; partition <= 29_272; partition++) {
				index.putChainPredecessor(CHAIN, partition, partition - 1);
			}
			final List<StoragePart> emission = flushAndPersist(index);
			final long leaves = countLeafPages(emission);
			assertTrue(leaves >= 115 && leaves <= 230, "Expected 115-230 leaves, got " + leaves);
			assertEquals(OptionalInt.of(29_271), reloadThroughLoader().getChainPredecessor(CHAIN, 29_272));
		}

		@Test
		@Tag(TRANSACTION)
		@DisplayName("chains reload exactly after transactional changes")
		void shouldReloadChainsAfterTransactionalChurn() {
			final VirtualReferencePartitionIndex initial = VirtualReferencePartitionIndexChainPredecessorTest.declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models =
				VirtualReferencePartitionIndexChainPredecessorTest.populate(initial, 20, 100);
			flushAndPersist(initial);

			final VirtualReferencePartitionIndex[] current = {reloadThroughLoader()};
			final Random random = new Random(1615);
			for (int round = 0; round < 3; round++) {
				assertStateAfterCommit(
					current[0],
					original -> {
						VirtualReferencePartitionIndexChainPredecessorTest.churn(original, models, random, 800, 2_000);
						// the flush runs inside the transaction, before the commit merges it - as the catalog flushes
						persist(flush(original));
					},
					(original, committed) -> current[0] = committed
				);
				VirtualReferencePartitionIndexChainPredecessorTest.assertMatchesModels(current[0], models, 2_000);
				VirtualReferencePartitionIndexChainPredecessorTest.assertMatchesModels(
					reloadThroughLoader(), models, 2_000
				);
			}
		}

		@Test
		@DisplayName("a predecessor change rewrites one leaf, a newly declared chain key the root as well")
		void shouldBoundTheWriteOfAPredecessorChange() {
			final VirtualReferencePartitionIndex source = VirtualReferencePartitionIndexChainPredecessorTest.declaredIndex();
			VirtualReferencePartitionIndexChainPredecessorTest.populate(source, 20, 100);
			final VirtualReferencePartitionIndexStoragePart persistedRoot = singleRoot(flushAndPersist(source));
			final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();

			reloaded.putChainPredecessor(CHAIN, 1_000, 424_242);
			final List<StoragePart> predecessorChange = flush(reloaded);
			reloaded.resetDirty();
			assertEquals(1, countLeafPages(predecessorChange), "One changed entry rewrites one leaf: " + predecessorChange);
			assertEquals(0, countRoots(predecessorChange), "A changed predecessor keeps the root: " + predecessorChange);

			reloaded.declareChainKey(new AttributeIndexKey(REFERENCE_NAME, "fresh", null));
			final List<StoragePart> declaration = flush(reloaded);
			reloaded.resetDirty();
			assertEquals(0, countLeafPages(declaration));
			assertEquals(3, singleRoot(declaration).getChainPredecessorSlots().length);
			assertArrayEquals(persistedRoot.getLeafPageSequences(), singleRoot(declaration).getLeafPageSequences());

			persist(predecessorChange);
			persist(declaration);
			final VirtualReferencePartitionIndex reloadedAgain = reloadThroughLoader();
			assertEquals(OptionalInt.of(424_242), reloadedAgain.getChainPredecessor(CHAIN, 1_000));
			assertEquals(
				OptionalInt.empty(),
				reloadedAgain.getChainPredecessor(new AttributeIndexKey(REFERENCE_NAME, "fresh", null), 1_000)
			);
		}

		@Test
		@DisplayName("a retired chain key leaves no entry and no slot behind on disk")
		void shouldLeaveNothingOfARetiredChainOnDisk() {
			final VirtualReferencePartitionIndex live = VirtualReferencePartitionIndexChainPredecessorTest.declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Integer>> models =
				VirtualReferencePartitionIndexChainPredecessorTest.populate(live, 20, 100);
			flushAndPersist(live);
			final VirtualReferencePartitionIndex reloaded = reloadThroughLoader();

			assertTrue(reloaded.retireChainKey(CHAIN));
			final List<StoragePart> retirement = flush(reloaded);
			reloaded.resetDirty();
			assertEquals(1, singleRoot(retirement).getChainPredecessorSlots().length);
			persist(retirement);

			// the loader refuses entries of a slot the root does not declare, so a clean load proves the reclaim
			final VirtualReferencePartitionIndex afterRetirement = reloadThroughLoader();
			afterRetirement.declareChainKey(CHAIN);
			models.remove(CHAIN);
			VirtualReferencePartitionIndexChainPredecessorTest.assertMatchesModels(afterRetirement, models, 2_000);
		}
	}

	/*
		PRIVATE HELPERS
	 */

	/**
	 * Builds an index of the given number of partitions, each with an owner and a tuple entry. Owners and targets
	 * cover negative, zero and extreme primary keys.
	 *
	 * @param partitionCount how many partitions to create
	 * @return the populated index
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex populated(int partitionCount) {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		for (int partition = 1; partition <= partitionCount; partition++) {
			final int owner = partition % 7 == 0 ? Integer.MIN_VALUE + partition : -partition;
			final int target = partition % 11 == 0 ? 0 : Integer.MAX_VALUE - partition;
			index.putPartitionOwner(partition, owner, target);
			index.putRepresentativeTuple(partition, TUPLES[partition % TUPLES.length]);
		}
		return index;
	}

	/**
	 * Flushes the index and drains every emitted part, removals included.
	 *
	 * @param index the index to flush
	 * @return the emitted parts
	 */
	@Nonnull
	private static List<StoragePart> flush(@Nonnull VirtualReferencePartitionIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, trappedChanges);
		return drain(trappedChanges);
	}

	/**
	 * Drains the trapped changes into a list.
	 *
	 * @param trappedChanges the changes to drain
	 * @return the drained parts, in emission order
	 */
	@Nonnull
	private static List<StoragePart> drain(@Nonnull TrappedChanges trappedChanges) {
		final List<StoragePart> parts = new ArrayList<>();
		trappedChanges.getTrappedChangesIterator().forEachRemaining(parts::add);
		return parts;
	}

	/**
	 * Flushes the index, persists the emission and resets the index's dirty state.
	 *
	 * @param index the index to flush
	 * @return the emitted parts
	 */
	@Nonnull
	private List<StoragePart> flushAndPersist(@Nonnull VirtualReferencePartitionIndex index) {
		final List<StoragePart> emission = flush(index);
		index.resetDirty();
		persist(emission);
		return emission;
	}

	/**
	 * Applies an emission to the offset index at the next catalog version and flushes it, exactly as the production
	 * drain does: regular parts are put, deferred removals resolve their key store-side and are removed.
	 *
	 * @param parts the emission to apply
	 */
	private void persist(@Nonnull List<StoragePart> parts) {
		this.version++;
		for (final StoragePart part : parts) {
			if (part instanceof DeferredRemovalStoragePart deferredRemoval) {
				final long removedPartPK =
					deferredRemoval.computeUniquePartIdAndSet(this.offsetIndex.getReadOnlyKeyCompressor());
				this.offsetIndex.remove(this.version, removedPartPK, deferredRemoval.removedContainerType());
			} else {
				this.offsetIndex.put(this.version, part);
			}
		}
		final OffsetIndexDescriptor descriptor = this.offsetIndex.flush(this.version);
		// reopen from disk, so every later read deserializes through the real Kryo serializers
		IOUtils.closeQuietly(this.offsetIndex::close);
		this.offsetIndex = new OffsetIndex(
			this.version,
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

	/**
	 * Reloads the index through the production loader from the current offset index.
	 *
	 * @return the reloaded index
	 */
	@Nonnull
	private VirtualReferencePartitionIndex reloadThroughLoader() {
		final EntityIndexStoragePart manifest = new EntityIndexStoragePart(
			ENTITY_INDEX_PK, 1, ENTITY_INDEX_KEY, Set.of(), Set.of(), false, Set.of(), Set.of()
		);
		final LoadedComponentBundle bundle = new VirtualReferencePartitionLoader().load(
			new LoadContext(
				this.version, ENTITY_INDEX_PK, EntitySchema._internalBuild(ENTITY_TYPE), ENTITY_INDEX_KEY, manifest,
				manifest.getVersion(), EmptyBitmap.INSTANCE, Map.of(),
				new OffsetIndexReadService(this.offsetIndex), null
			)
		);
		return assertInstanceOf(VirtualReferencePartitions.class, bundle).virtualReferencePartitionIndex();
	}

	/**
	 * Reloads the whole referenced type entity index through its production reload plan from the current offset
	 * index, building the load context from the persisted manifest and entity ids exactly as the entity collection
	 * persistence service does.
	 *
	 * @return the reloaded referenced type entity index
	 */
	@Nonnull
	private ReferencedTypeEntityIndex reloadTypeIndexThroughPlan() {
		final EntityIndexStoragePart manifest =
			this.offsetIndex.get(this.version, ENTITY_INDEX_PK, EntityIndexStoragePart.class);
		assertNotNull(manifest, "The type index must have persisted its manifest");
		final EntityIdsStoragePart entityIds =
			this.offsetIndex.get(this.version, ENTITY_INDEX_PK, EntityIdsStoragePart.class);
		assertNotNull(entityIds, "The type index must have persisted its entity ids");
		final EntityIndex reloaded = ReferencedTypeEntityIndex.reloadPlan().run(
			new LoadContext(
				this.version, ENTITY_INDEX_PK, EntitySchema._internalBuild(ENTITY_TYPE), manifest.getEntityIndexKey(),
				manifest, Math.max(manifest.getVersion(), entityIds.getVersion()), entityIds.getEntityIds(),
				entityIds.getEntityIdsByLanguage(), new OffsetIndexReadService(this.offsetIndex), null
			)
		);
		return assertInstanceOf(ReferencedTypeEntityIndex.class, reloaded);
	}

	/**
	 * Reads the persisted root, or `null` when there is none.
	 *
	 * @return the root or `null`
	 */
	@Nullable
	private VirtualReferencePartitionIndexStoragePart readRootOrNull() {
		final KeyCompressor compressor = this.offsetIndex.getReadOnlyKeyCompressor();
		return this.offsetIndex.get(
			this.version,
			VirtualReferencePartitionIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, compressor.getId(new ReferenceNameKey(REFERENCE_NAME))
			),
			VirtualReferencePartitionIndexStoragePart.class
		);
	}

	/**
	 * Reads one persisted leaf page, failing when it is absent.
	 *
	 * @param pageSequence the page sequence
	 * @return the leaf page
	 */
	@Nonnull
	private VirtualReferencePartitionIndexLeafPagePart readLeafPage(int pageSequence) {
		final VirtualReferencePartitionIndexLeafPagePart page = readLeafPageOrNull(pageSequence);
		assertNotNull(page, "Leaf page " + pageSequence + " must be readable");
		return page;
	}

	/**
	 * Reads one persisted leaf page, or `null` when it is absent.
	 *
	 * @param pageSequence the page sequence
	 * @return the leaf page or `null`
	 */
	@Nullable
	private VirtualReferencePartitionIndexLeafPagePart readLeafPageOrNull(int pageSequence) {
		final int streamId = this.offsetIndex.getReadOnlyKeyCompressor()
			.getId(new VirtualReferencePartitionLeafStreamKey(ENTITY_INDEX_PK, REFERENCE_NAME));
		return this.offsetIndex.get(
			this.version,
			VirtualReferencePartitionIndexLeafPagePart.computeUniquePartId(streamId, pageSequence),
			VirtualReferencePartitionIndexLeafPagePart.class
		);
	}

	/**
	 * Returns the single root part of an emission, failing when there is none or more than one.
	 *
	 * @param parts the emission
	 * @return the root part
	 */
	@Nonnull
	private static VirtualReferencePartitionIndexStoragePart singleRoot(@Nonnull List<StoragePart> parts) {
		assertEquals(1, countRoots(parts), "The emission must hold exactly one root: " + parts);
		return parts.stream()
			.filter(VirtualReferencePartitionIndexStoragePart.class::isInstance)
			.map(VirtualReferencePartitionIndexStoragePart.class::cast)
			.findFirst()
			.orElseThrow();
	}

	/**
	 * Returns the single leaf page of an emission, failing when there is none or more than one.
	 *
	 * @param parts the emission
	 * @return the leaf page
	 */
	@Nonnull
	private static VirtualReferencePartitionIndexLeafPagePart singleLeafPage(@Nonnull List<StoragePart> parts) {
		assertEquals(1, countLeafPages(parts), "The emission must hold exactly one leaf page: " + parts);
		return parts.stream()
			.filter(VirtualReferencePartitionIndexLeafPagePart.class::isInstance)
			.map(VirtualReferencePartitionIndexLeafPagePart.class::cast)
			.findFirst()
			.orElseThrow();
	}

	/**
	 * Counts the root parts of an emission.
	 *
	 * @param parts the emission
	 * @return the number of roots
	 */
	private static long countRoots(@Nonnull List<StoragePart> parts) {
		return parts.stream().filter(VirtualReferencePartitionIndexStoragePart.class::isInstance).count();
	}

	/**
	 * Counts the leaf pages of an emission.
	 *
	 * @param parts the emission
	 * @return the number of leaf pages
	 */
	private static long countLeafPages(@Nonnull List<StoragePart> parts) {
		return parts.stream().filter(VirtualReferencePartitionIndexLeafPagePart.class::isInstance).count();
	}

	/**
	 * Counts the leaf-page removals of an emission.
	 *
	 * @param parts the emission
	 * @return the number of leaf-page removals
	 */
	private static long countLeafPageRemovals(@Nonnull List<StoragePart> parts) {
		return parts.stream().filter(VirtualReferencePartitionIndexLeafPageRemoval.class::isInstance).count();
	}

	/**
	 * Opens a write handle on the test file.
	 *
	 * @return the write handle
	 */
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

	/**
	 * Builds the Kryo the production entity collection file uses.
	 *
	 * @return the Kryo factory
	 */
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
	 * Read-only {@link StoragePartPersistenceService} over a real {@link OffsetIndex}: it forwards the two methods the
	 * loader calls and fails loudly on everything else.
	 *
	 * @param offsetIndex the real store to read from
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

		@Nonnull
		@Override
		public StoragePartPersistenceService<StorageDescriptor> createTransactionalService(
			@Nonnull UUID transactionId
		) {
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
