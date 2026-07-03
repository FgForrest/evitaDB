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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.function.Functions;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
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
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the granular (`PAGED`) persistence of an OWNER-mode {@link OwnerSortIndex} round-trips through the REAL Kryo +
 * {@link OffsetIndex} layer: a large owner value tree is paged out as individual {@link SortIndexLeafPagePart} leaf
 * pages, written to a real on-disk offset index, reloaded, and reassembled boundary-stable via
 * {@link OwnerSortIndex#fromPersistedPages} — which also reconstructs the positional `sortedRecords` façade (a PAGED owner
 * does not persist it). This is the end-to-end counterpart of the serializer-level tests, exercising the new record-type
 * byte in the index storage-part registry and the bespoke compound-value leaf serializer through a real
 * {@link OffsetIndexRecordTypeRegistry}. Also covers the inline (`SINGLE`) shape, a leaf merge that frees (and removes)
 * dropped leaf pages, a `PAGED -> SINGLE` collapse, a sortable compound owner, churn, and an owner-PAGED reload across a
 * warm-up flush.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
@Tag(ORDER)
@DisplayName("Owner-mode sort index granular paging round-trips through the real OffsetIndex")
class SortIndexOwnerPagingRoundTripTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_INDEX_PK = 7;
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "code", null);
	/**
	 * Distinct sort values for the paged scenario. Each value owns exactly one record, so this many distinct values
	 * yields this many bucket-tree entries — enough to split the 256-entry leaf block into more than one leaf (so the
	 * owner is `PAGED`, spanning ≥ 3 leaves).
	 */
	private static final int KEY_COUNT = 600;
	/**
	 * Distinct values kept after the collapse scenario shrinks a PAGED owner back to a single leaf — well within the
	 * 256-entry leaf, so the owner collapses to the SINGLE shape.
	 */
	private static final int COLLAPSE_KEEP = 40;
	/**
	 * Inclusive start (0-based ordinal) of the contiguous value run removed to force leaf merges; survivors still span
	 * more than one leaf.
	 */
	private static final int MERGE_REMOVE_FROM = 150;
	/**
	 * Exclusive end (0-based ordinal) of the contiguous removed value run.
	 */
	private static final int MERGE_REMOVE_TO = 450;
	private static final long PERSISTED_VERSION = 1L;
	private static final long SECOND_VERSION = 2L;
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
		this.targetFile = Files.createTempFile("sortIndexOwnerPagingRoundTrip", ".kryo");
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
		this.targetFile.toFile().delete();
	}

	@Test
	@DisplayName("A multi-leaf owner sort index pages out and reloads identically through the OffsetIndex")
	void shouldRoundTripPagedOwnerThroughOffsetIndex() {
		final OwnerSortIndex source = scalarOwner();
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
		}

		final List<StoragePart> emittedParts = emit(source);
		assertTrue(leafPages(emittedParts).size() >= 2, "a paged owner must emit at least two leaf pages");
		final SortIndexStoragePart root = root(emittedParts);
		assertTrue(root.isPaged(), "a paged owner must emit a paged root part");

		final OffsetIndexDescriptor descriptor = persist(stripRemovals(emittedParts));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
			final SortIndexStoragePart reloadedRoot = readRoot(reloaded, PERSISTED_VERSION);
			assertNotNull(reloadedRoot, "the paged root part must be readable after reload");
			assertTrue(reloadedRoot.isPaged(), "the reloaded root must be paged");

			final OwnerSortIndex restored = loadPagedOwner(reloaded, PERSISTED_VERSION, reloadedRoot);
			// reconstruction fidelity: the reconstructed positional sortedRecords must equal the live source's array
			assertArrayEquals(
				source.getSortedRecords(), restored.getSortedRecords(),
				"the reconstructed sortedRecords must be byte-for-byte the live array"
			);
			assertSameSortIndex(source, restored, "paged round-trip");
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A small inline (SINGLE) owner sort index still loads after a real OffsetIndex round-trip")
	void shouldRoundTripSingleOwnerThroughOffsetIndex() {
		final OwnerSortIndex source = scalarOwner();
		for (int i = 0; i < 3; i++) {
			source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
		}

		final List<StoragePart> emittedParts = emit(source);
		final SortIndexStoragePart root = root(emittedParts);
		assertFalse(root.isPaged(), "a small owner must stay within a single leaf (the SINGLE shape)");
		assertTrue(leafPages(emittedParts).isEmpty(), "a SINGLE owner emits no leaf pages");

		final OffsetIndexDescriptor descriptor = persist(stripRemovals(emittedParts));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
			final SortIndexStoragePart reloadedRoot = readRoot(reloaded, PERSISTED_VERSION);
			assertNotNull(reloadedRoot, "the inline root part must be readable after reload");
			assertFalse(reloadedRoot.isPaged(), "a small owner must round-trip as non-paged");

			final OwnerSortIndex restored = loadSingleOwner(reloadedRoot);
			assertSameSortIndex(source, restored, "inline round-trip");
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A leaf merge after a paged flush frees its dropped leaf pages and removes them on the next flush")
	void shouldRemoveFreedLeafPagesWhenLeavesMergeOnReflush() {
		final OwnerSortIndex source = scalarOwner();
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
		}

		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		OffsetIndexDescriptor secondDescriptor = null;
		int[] freedSequences = null;
		try {
			// first flush (version 1): persist every leaf page + the paged root
			writeEmission(offsetIndex, PERSISTED_VERSION, emit(source));
			offsetIndex.flush(PERSISTED_VERSION);

			final SortIndexStoragePart firstRoot = readRoot(offsetIndex, PERSISTED_VERSION);
			assertNotNull(firstRoot, "the paged root part must be readable after the first flush");
			assertTrue(firstRoot.isPaged(), "the owner must be paged before the merge");
			final int[] liveBeforeMerge = firstRoot.getLeafPageSequences();
			// reopen the persisted pages into a fresh owner (restores the page-stream live-set baseline from disk)
			final OwnerSortIndex restored = loadPagedOwner(offsetIndex, PERSISTED_VERSION, firstRoot);

			// merge: drop a long contiguous run of values so at least one leaf empties and merges into a sibling
			for (int i = MERGE_REMOVE_FROM; i < MERGE_REMOVE_TO; i++) {
				restored.removeRecord(valueForOrdinal(i), recordForOrdinal(i));
			}

			// second flush (version 2): leaf-page removals INCLUDED this time
			final List<StoragePart> secondEmission = emit(restored);
			final List<SortIndexLeafPageRemoval> removals = removals(secondEmission);
			final SortIndexStoragePart secondRoot = root(secondEmission);
			assertTrue(secondRoot.isPaged(), "the shrunken owner must still be paged (PAGED -> PAGED)");

			// the merge must free at least one leaf and emit exactly one removal per freed page sequence
			freedSequences = freedSequences(liveBeforeMerge, secondRoot.getLeafPageSequences());
			assertTrue(freedSequences.length >= 1, "a leaf merge must free at least one leaf page");
			assertEquals(
				freedSequences.length, removals.size(),
				"exactly one SortIndexLeafPageRemoval must be emitted per freed leaf page"
			);
			final Set<Integer> liveAfterSet = toSet(secondRoot.getLeafPageSequences());
			for (final int freedSequence : freedSequences) {
				assertFalse(liveAfterSet.contains(freedSequence), "freed page " + freedSequence + " must leave the live set");
			}

			writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
			secondDescriptor = offsetIndex.flush(SECOND_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}

		// reopen the file and verify it now equals an owner built directly from only the surviving values
		OffsetIndex reopened = null;
		try {
			reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
			final int streamId = streamId(reopened);
			final SortIndexStoragePart finalRoot = readRoot(reopened, SECOND_VERSION);
			assertNotNull(finalRoot, "the paged root must be readable after the second flush");

			for (final int freedSequence : freedSequences) {
				assertNull(
					reopened.get(
						SECOND_VERSION, AbstractLeafPagePart.computeUniquePartId(streamId, freedSequence),
						SortIndexLeafPagePart.class
					),
					"freed leaf page " + freedSequence + " must be removed from storage"
				);
			}

			final OwnerSortIndex reloaded = loadPagedOwner(reopened, SECOND_VERSION, finalRoot);
			final OwnerSortIndex expected = scalarOwner();
			for (int i = 0; i < KEY_COUNT; i++) {
				if (i < MERGE_REMOVE_FROM || i >= MERGE_REMOVE_TO) {
					expected.addRecord(valueForOrdinal(i), recordForOrdinal(i));
				}
			}
			assertSameSortIndex(expected, reloaded, "merge survivor round-trip");
		} finally {
			if (reopened != null) {
				IOUtils.closeQuietly(reopened::close);
			}
		}
	}

	@Test
	@DisplayName("A PAGED owner collapsing to SINGLE removes every prior leaf page and reloads as inline on reflush")
	void shouldCollapsePagedOwnerToSingleAndRemoveAllPriorLeafPagesOnReflush() {
		final OwnerSortIndex source = scalarOwner();
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
		}

		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		OffsetIndexDescriptor secondDescriptor = null;
		int[] liveBeforeCollapse = null;
		try {
			writeEmission(offsetIndex, PERSISTED_VERSION, emit(source));
			offsetIndex.flush(PERSISTED_VERSION);

			final SortIndexStoragePart firstRoot = readRoot(offsetIndex, PERSISTED_VERSION);
			assertNotNull(firstRoot, "the paged root part must be readable after the first flush");
			assertTrue(firstRoot.isPaged(), "the owner must be paged before the collapse");
			liveBeforeCollapse = firstRoot.getLeafPageSequences();
			final OwnerSortIndex restored = loadPagedOwner(offsetIndex, PERSISTED_VERSION, firstRoot);

			// collapse: remove enough values that the survivors fit within a single leaf (PAGED -> SINGLE)
			for (int i = COLLAPSE_KEEP; i < KEY_COUNT; i++) {
				restored.removeRecord(valueForOrdinal(i), recordForOrdinal(i));
			}

			final List<StoragePart> secondEmission = emit(restored);
			final List<SortIndexLeafPageRemoval> removals = removals(secondEmission);
			final SortIndexStoragePart secondRoot = root(secondEmission);
			assertFalse(secondRoot.isPaged(), "the collapsed owner must emit an inline (SINGLE) root");
			assertEquals(
				liveBeforeCollapse.length, removals.size(),
				"exactly one SortIndexLeafPageRemoval must be emitted per previously-live leaf page"
			);

			writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
			secondDescriptor = offsetIndex.flush(SECOND_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}

		OffsetIndex reopened = null;
		try {
			reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
			final int streamId = streamId(reopened);
			final SortIndexStoragePart finalRoot = readRoot(reopened, SECOND_VERSION);
			assertNotNull(finalRoot, "the inline root must be readable after the collapse flush");
			assertFalse(finalRoot.isPaged(), "the collapsed root must reload as SINGLE");

			for (final int priorSequence : liveBeforeCollapse) {
				assertNull(
					reopened.get(
						SECOND_VERSION, AbstractLeafPagePart.computeUniquePartId(streamId, priorSequence),
						SortIndexLeafPagePart.class
					),
					"prior leaf page " + priorSequence + " must be removed from storage on collapse"
				);
			}

			final OwnerSortIndex reloaded = loadSingleOwner(finalRoot);
			final OwnerSortIndex expected = scalarOwner();
			for (int i = 0; i < COLLAPSE_KEEP; i++) {
				expected.addRecord(valueForOrdinal(i), recordForOrdinal(i));
			}
			assertSameSortIndex(expected, reloaded, "collapse survivor round-trip");
		} finally {
			if (reopened != null) {
				IOUtils.closeQuietly(reopened::close);
			}
		}
	}

	@Test
	@DisplayName("A sortable compound owner pages out and reloads identically (compound value leaf serializer)")
	void shouldRoundTripCompoundPagedOwnerThroughOffsetIndex() {
		final OwnerSortIndex source = compoundOwner();
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(compoundForOrdinal(i), recordForOrdinal(i));
		}

		final List<StoragePart> emittedParts = emit(source);
		final SortIndexStoragePart root = root(emittedParts);
		assertTrue(root.isPaged(), "a compound owner with many distinct values must be paged");
		assertTrue(leafPages(emittedParts).size() >= 2, "a paged compound owner must emit at least two leaf pages");

		final OffsetIndexDescriptor descriptor = persist(stripRemovals(emittedParts));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
			final SortIndexStoragePart reloadedRoot = readRoot(reloaded, PERSISTED_VERSION);
			assertNotNull(reloadedRoot, "the paged compound root must be readable after reload");
			final OwnerSortIndex restored = loadPagedOwner(reloaded, PERSISTED_VERSION, reloadedRoot);
			assertArrayEquals(
				source.getSortedRecords(), restored.getSortedRecords(),
				"the reconstructed compound sortedRecords must equal the live array"
			);
			assertSameSortIndex(source, restored, "compound paged round-trip");
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A paged owner survives repeated in-memory churn before a single flush + reload")
	void shouldSurviveRepeatedChurnBeforeFlush() {
		final OwnerSortIndex source = scalarOwner();
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
		}
		// several rounds of remove + re-add on the SAME in-memory owner before any flush (stresses tree mutation)
		for (int round = 0; round < 3; round++) {
			for (int i = round; i < KEY_COUNT; i += 50) {
				source.removeRecord(valueForOrdinal(i), recordForOrdinal(i));
			}
			for (int i = round; i < KEY_COUNT; i += 50) {
				source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
			}
		}

		final List<StoragePart> emittedParts = emit(source);
		final SortIndexStoragePart root = root(emittedParts);
		assertTrue(root.isPaged(), "the churned owner must still be paged");

		final OffsetIndexDescriptor descriptor = persist(stripRemovals(emittedParts));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
			final SortIndexStoragePart reloadedRoot = readRoot(reloaded, PERSISTED_VERSION);
			final OwnerSortIndex restored = loadPagedOwner(reloaded, PERSISTED_VERSION, reloadedRoot);

			final OwnerSortIndex expected = scalarOwner();
			for (int i = 0; i < KEY_COUNT; i++) {
				expected.addRecord(valueForOrdinal(i), recordForOrdinal(i));
			}
			assertSameSortIndex(expected, restored, "churn round-trip");
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A paged owner reloads identically across a warm-up flush, then an incremental flush + reload")
	void shouldReloadPagedOwnerAcrossWarmUpFlushAndIncrementalFlush() {
		final OwnerSortIndex source = scalarOwner();
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
		}

		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		OffsetIndexDescriptor thirdDescriptor = null;
		try {
			// warm-up flush (version 1): persist the paged owner
			writeEmission(offsetIndex, PERSISTED_VERSION, emit(source));
			offsetIndex.flush(PERSISTED_VERSION);

			// reload after the warm-up flush: the reloaded owner must be byte-for-byte the source
			final SortIndexStoragePart firstRoot = readRoot(offsetIndex, PERSISTED_VERSION);
			final OwnerSortIndex afterWarmUp = loadPagedOwner(offsetIndex, PERSISTED_VERSION, firstRoot);
			assertArrayEquals(
				source.getSortedRecords(), afterWarmUp.getSortedRecords(),
				"the owner reloaded after the warm-up flush must equal the source"
			);
			assertSameSortIndex(source, afterWarmUp, "warm-up reload");

			// incremental change on the reloaded owner: remove a few values, add a few brand-new ones (rides the same
			// stage -> publish page-stream handshake as the first flush, now starting from the disk-restored baseline)
			for (int i = 0; i < 20; i++) {
				afterWarmUp.removeRecord(valueForOrdinal(i), recordForOrdinal(i));
			}
			for (int i = KEY_COUNT; i < KEY_COUNT + 20; i++) {
				afterWarmUp.addRecord(valueForOrdinal(i), recordForOrdinal(i));
			}

			// second flush (version 2)
			final List<StoragePart> secondEmission = emit(afterWarmUp);
			assertTrue(root(secondEmission).isPaged(), "the owner must stay paged after the incremental change");
			writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
			thirdDescriptor = offsetIndex.flush(SECOND_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}

		// reopen the file and verify it equals an owner built from the full mutation sequence
		OffsetIndex reopened = null;
		try {
			reopened = loadOffsetIndex(thirdDescriptor, SECOND_VERSION);
			final SortIndexStoragePart finalRoot = readRoot(reopened, SECOND_VERSION);
			assertNotNull(finalRoot, "the paged root must be readable after the incremental flush");
			final OwnerSortIndex reloaded = loadPagedOwner(reopened, SECOND_VERSION, finalRoot);

			final OwnerSortIndex expected = scalarOwner();
			for (int i = 20; i < KEY_COUNT + 20; i++) {
				expected.addRecord(valueForOrdinal(i), recordForOrdinal(i));
			}
			assertSameSortIndex(expected, reloaded, "incremental reload");
		} finally {
			if (reopened != null) {
				IOUtils.closeQuietly(reopened::close);
			}
		}
	}

	@Test
	@DisplayName("A reloaded paged owner re-flushed without any change emits nothing (clean reload is not dirty)")
	void shouldEmitNothingWhenReloadedPagedOwnerIsReflushedWithoutChanges() {
		final OwnerSortIndex source = scalarOwner();
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
		}

		final OffsetIndexDescriptor descriptor = persist(stripRemovals(emit(source)));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
			final SortIndexStoragePart reloadedRoot = readRoot(reloaded, PERSISTED_VERSION);
			assertTrue(reloadedRoot.isPaged(), "the reloaded root must be paged");
			final OwnerSortIndex restored = loadPagedOwner(reloaded, PERSISTED_VERSION, reloadedRoot);

			// a clean reload restored the page-stream baseline from disk, so the untouched owner is not dirty: re-emitting
			// it must yield nothing - no leaf page, no removal, no root (guards baseline staleness on reload)
			final List<StoragePart> reEmission = emit(restored);
			assertTrue(
				reEmission.isEmpty(),
				"an untouched reloaded paged owner must emit nothing on reflush, emitted " + reEmission.size() + " parts"
			);
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A reloaded paged owner mutated in one leaf re-emits only that leaf, not the whole tree")
	void shouldReEmitOnlyTheChangedLeafWhenReloadedPagedOwnerIsMutatedLocally() {
		final OwnerSortIndex source = scalarOwner();
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(valueForOrdinal(i), recordForOrdinal(i));
		}

		final OffsetIndexDescriptor descriptor = persist(stripRemovals(emit(source)));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
			final SortIndexStoragePart reloadedRoot = readRoot(reloaded, PERSISTED_VERSION);
			assertTrue(reloadedRoot.isPaged(), "the reloaded root must be paged");
			final int totalLeafCount = reloadedRoot.getLeafPageSequences().length;
			final OwnerSortIndex restored = loadPagedOwner(reloaded, PERSISTED_VERSION, reloadedRoot);

			// grow ONE already-present value's cardinality from 1 to 2 with a brand-new record id (no split), so only the
			// single leaf holding that value changes
			restored.addRecord(valueForOrdinal(0), KEY_COUNT + 1);

			final List<StoragePart> reEmission = emit(restored);
			assertTrue(removals(reEmission).isEmpty(), "a non-splitting in-place add frees no leaf page");
			// the value's bucket grew in place — no leaf split or merge, so the live page list is byte-identical to the
			// persisted root: the redundant PAGED root re-emit is skipped (steady-state O(1)). A reload still
			// resolves the unchanged root from its prior version, so only the touched leaf page needs to be written.
			assertTrue(
				reEmission.stream().noneMatch(SortIndexStoragePart.class::isInstance),
				"a content-only in-place add leaves the page list unchanged, so the PAGED root must not be re-emitted"
			);
			final int changedLeafCount = leafPages(reEmission).size();
			assertTrue(
				changedLeafCount >= 1 && changedLeafCount < totalLeafCount,
				"only the mutated leaf must be re-emitted, not the whole tree (re-emitted " + changedLeafCount +
					" of " + totalLeafCount + " leaves)"
			);
			assertEquals(
				1, changedLeafCount,
				"exactly one leaf changed, so exactly one leaf page must be re-emitted"
			);
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A legacy non-ascending owner block reloads canonical-ascending once it regrows to paged")
	void shouldCanonicalizeAscendingWhenLegacyNonAscendingOwnerRegrowsToPagedAndReloads() {
		// a legacy SINGLE owner persisted with a deliberately non-ascending record block for one value: the positional
		// sortedRecords façade keeps the bytes verbatim, but the owned tree stores the bucket canonically (ascending ids)
		final ComparatorSource[] base = {
			new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
		final OwnerSortIndex source = new OwnerSortIndex(
			base, null, ATTRIBUTE_KEY, 0,
			new int[]{3, 1, 2}, new Serializable[]{"v00000"}, Map.of("v00000", 3)
		);
		assertArrayEquals(
			new int[]{3, 1, 2}, source.getSortedRecords(),
			"the legacy positional façade must keep the non-ascending block verbatim"
		);

		// regrow well past a single leaf so the owner pages out; the new values sort after "v00000", keeping its block first
		for (int i = 1; i <= KEY_COUNT; i++) {
			source.addRecord(valueForOrdinal(i), 1000 + i);
		}

		final OffsetIndexDescriptor descriptor = persist(stripRemovals(emit(source)));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
			final SortIndexStoragePart reloadedRoot = readRoot(reloaded, PERSISTED_VERSION);
			assertTrue(reloadedRoot.isPaged(), "the regrown owner must page out");
			final OwnerSortIndex restored = loadPagedOwner(reloaded, PERSISTED_VERSION, reloadedRoot);

			// a PAGED owner does not persist the positional sortedRecords: it is reconstructed from the owned tree's buckets,
			// so the "v00000" block comes back canonical-ascending {1,2,3}, not the legacy verbatim {3,1,2}
			final int[] firstBlock = Arrays.copyOf(restored.getSortedRecords(), 3);
			assertArrayEquals(
				new int[]{1, 2, 3}, firstBlock,
				"the PAGED reload must reconstruct the v00000 block canonical-ascending from the owned tree"
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

	@Nonnull
	private static OwnerSortIndex scalarOwner() {
		return new OwnerSortIndex(String.class, ATTRIBUTE_KEY);
	}

	@Nonnull
	private static OwnerSortIndex compoundOwner() {
		final ComparatorSource[] base = {
			new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
			new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
		};
		return new OwnerSortIndex(base, ATTRIBUTE_KEY);
	}

	/**
	 * A distinct, lexicographically-ordered sort value for the given ordinal (so value order coincides with record order).
	 */
	@Nonnull
	private static String valueForOrdinal(int ordinal) {
		return String.format("v%05d", ordinal);
	}

	/**
	 * A distinct two-component sort value (a lexicographic String plus an Integer) for the given ordinal.
	 */
	@Nonnull
	private static Serializable[] compoundForOrdinal(int ordinal) {
		return new Serializable[]{String.format("v%05d", ordinal), ordinal};
	}

	/**
	 * The (never-zero) record id for the given 0-based ordinal — record ids start at 1.
	 */
	private static int recordForOrdinal(int ordinal) {
		return ordinal + 1;
	}

	/**
	 * Drains the {@link OwnerSortIndex} commit emission into a list keeping EVERY part, including any
	 * {@link DeferredRemovalStoragePart}.
	 */
	@Nonnull
	private static List<StoragePart> emit(@Nonnull OwnerSortIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Keeps only the real (writable) storage parts, dropping every {@link DeferredRemovalStoragePart}.
	 */
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
	private static List<SortIndexLeafPagePart> leafPages(@Nonnull List<StoragePart> parts) {
		final List<SortIndexLeafPagePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexLeafPagePart leafPage) {
				result.add(leafPage);
			}
		}
		return result;
	}

	@Nonnull
	private static List<SortIndexLeafPageRemoval> removals(@Nonnull List<StoragePart> parts) {
		final List<SortIndexLeafPageRemoval> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexLeafPageRemoval removal) {
				result.add(removal);
			}
		}
		return result;
	}

	@Nonnull
	private static SortIndexStoragePart root(@Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexStoragePart rootPart) {
				return rootPart;
			}
		}
		throw new IllegalStateException("The emission carries no SortIndexStoragePart root!");
	}

	/**
	 * Asserts the two owner sort indexes carry the same positional sortedRecords array, the same ordered distinct values
	 * and the same total record count.
	 */
	private static void assertSameSortIndex(
		@Nonnull OwnerSortIndex expected, @Nonnull OwnerSortIndex actual, @Nonnull String context
	) {
		assertArrayEquals(
			expected.getSortedRecords(), actual.getSortedRecords(), context + ": sortedRecords must round-trip"
		);
		assertArrayEquals(
			expected.getSortedRecordValues(), actual.getSortedRecordValues(),
			context + ": ordered distinct values must round-trip"
		);
		assertEquals(expected.size(), actual.size(), context + ": total record count must round-trip");
	}

	@Nonnull
	private static SortIndexStoragePart readRoot(@Nonnull OffsetIndex offsetIndex, long catalogVersion) {
		final long rootPK = AttributeIndexStoragePart.computeUniquePartId(
			ENTITY_INDEX_PK, AttributeIndexType.SORT, ATTRIBUTE_KEY, offsetIndex.getReadOnlyKeyCompressor()
		);
		return offsetIndex.get(catalogVersion, rootPK, SortIndexStoragePart.class);
	}

	private static int streamId(@Nonnull OffsetIndex offsetIndex) {
		return offsetIndex.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(ENTITY_INDEX_PK, new AttributeKeyWithIndexType(ATTRIBUTE_KEY, AttributeIndexType.SORT))
		);
	}

	/**
	 * Reads a paged root's leaf pages back from the (open) offset index and reassembles a boundary-stable
	 * {@link OwnerSortIndex} via {@link OwnerSortIndex#fromPersistedPages} — the same path the loader takes on a cold load.
	 */
	@Nonnull
	private static OwnerSortIndex loadPagedOwner(
		@Nonnull OffsetIndex offsetIndex, long catalogVersion, @Nonnull SortIndexStoragePart root
	) {
		final int streamId = streamId(offsetIndex);
		final int[] orderedPageSequences = root.getLeafPageSequences();
		final ValueToRecordBitmap[][] perPageBuckets = new ValueToRecordBitmap[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final SortIndexLeafPagePart leafPage = offsetIndex.get(
				catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
				SortIndexLeafPagePart.class
			);
			assertNotNull(leafPage, "leaf page " + orderedPageSequences[i] + " must be readable");
			perPageBuckets[i] = leafPage.getBuckets();
		}
		return OwnerSortIndex.fromPersistedPages(
			root.getComparatorBase(), null, root.getAttributeIndexKey(), root.getIndexedDecimalPlaces(),
			orderedPageSequences, perPageBuckets, root.getHighWaterPageSequence()
		);
	}

	/**
	 * Rebuilds an inline (SINGLE) owner from its root's persisted flat columns — the loader's SINGLE adopt-direct path.
	 */
	@Nonnull
	private static OwnerSortIndex loadSingleOwner(@Nonnull SortIndexStoragePart root) {
		return new OwnerSortIndex(
			root.getComparatorBase(), null, root.getAttributeIndexKey(), root.getIndexedDecimalPlaces(),
			root.getSortedRecords(), root.getSortedRecordsValues(), root.getValueCardinalities()
		);
	}

	@Nonnull
	private static int[] freedSequences(@Nonnull int[] before, @Nonnull int[] after) {
		final Set<Integer> liveAfter = toSet(after);
		final int[] freed = new int[before.length];
		int count = 0;
		for (final int sequence : before) {
			if (!liveAfter.contains(sequence)) {
				freed[count++] = sequence;
			}
		}
		return Arrays.copyOf(freed, count);
	}

	@Nonnull
	private static Set<Integer> toSet(@Nonnull int[] sequences) {
		final Set<Integer> set = new HashSet<>(sequences.length);
		for (final int sequence : sequences) {
			set.add(sequence);
		}
		return set;
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
}
