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

package io.evitadb.index.cardinality;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.function.Functions;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityLeafStreamKey;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.SharedIndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService.FileLocationAndWrittenBytes;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the granular (`PAGED`) persistence of {@link ReferenceTypeCardinalityIndex} round-trips through the REAL
 * Kryo + {@link OffsetIndex} layer: a large cardinality index is paged out as individual
 * {@link ReferenceTypeCardinalityIndexLeafPagePart} leaf pages, written to a real on-disk offset index, reloaded, and
 * reassembled boundary-stable via {@link ReferenceTypeCardinalityIndex#fromPersistedPages}. This is the end-to-end
 * counterpart of the index-level paging tests, which bypass the Kryo/OffsetIndex layer and therefore could not catch a
 * missing {@link ReferenceTypeCardinalityIndexLeafPagePart} record-type byte in the index storage-part registry. Driving
 * the leaf pages through a real {@link OffsetIndex} + {@link OffsetIndexRecordTypeRegistry} closes that gap. Also verifies
 * a small inline (`SINGLE`) part still loads and that a leaf merge frees (and removes) the leaf pages it drops.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(REFERENCE)
@DisplayName("Reference-type cardinality index granular paging round-trips through the real OffsetIndex")
class ReferenceTypeCardinalityIndexPagingRoundTripTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_INDEX_PK = 7;
	private static final String REFERENCE_NAME = "testReference";
	/**
	 * Distinct index primary keys for the paged scenario. Each {@code addRecord} writes TWO composed-key tree entries
	 * (the per-whole-index-PK counter and the per-tuple counter), so this many distinct index PKs yields ~800 tree keys —
	 * enough to split the 256-entry leaf block into more than one leaf (so the index is `PAGED`, spanning ≥ 3 leaves).
	 */
	private static final int KEY_COUNT = 400;
	/**
	 * Distinct index primary keys kept after the collapse scenario shrinks a PAGED index back down to a single leaf. A
	 * few dozen survivors (~80 tree keys) stay well within the 256-entry leaf, so the index collapses to the SINGLE
	 * shape (the `PAGED -> SINGLE` scenario).
	 */
	private static final int COLLAPSE_KEEP = 40;
	/**
	 * Distinct index primary keys for the merge scenario. ~1200 tree keys span several leaves so a later contiguous
	 * removal can merge and free a leaf while the index STAYS paged (the `PAGED -> PAGED` freed-page scenario).
	 */
	private static final int MERGE_KEY_COUNT = 600;
	/**
	 * Inclusive start (0-based ordinal) of the contiguous index-PK run removed to force leaf merges; survivors still span
	 * more than one leaf.
	 */
	private static final int MERGE_REMOVE_FROM = 150;
	/**
	 * Exclusive end (0-based ordinal) of the contiguous removed index-PK run.
	 */
	private static final int MERGE_REMOVE_TO = 450;
	/**
	 * The catalog version the parts are flushed at (and reopened at).
	 */
	private static final long PERSISTED_VERSION = 1L;
	/**
	 * The catalog version the second (merge) flush — leaf-page removals included — is applied and reopened at.
	 */
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
		this.targetFile = Files.createTempFile("referenceTypeCardinalityPagingRoundTrip", ".kryo");
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
		this.targetFile.toFile().delete();
	}

	@Test
	@DisplayName("A multi-leaf cardinality index pages out and reloads identically through the OffsetIndex")
	void shouldRoundTripPagedReferenceTypeCardinalityIndexThroughOffsetIndex() {
		final ReferenceTypeCardinalityIndex source = new ReferenceTypeCardinalityIndex();
		// register enough distinct (indexPK, refPK) tuples to make the composed-key tree span many leaves
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(indexPkForOrdinal(i), refPkForOrdinal(i));
		}
		assertTrue(source.isPaged(), "the index must span multiple leaves to exercise the paged layout");

		// collect the granular emission (leaf pages + paged root; no freed-page removals on a first flush)
		final TrappedChanges trappedChanges = new TrappedChanges();
		source.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, trappedChanges);
		final List<StoragePart> emittedParts = collectParts(trappedChanges);
		final long leafPageCount = emittedParts.stream()
			.filter(ReferenceTypeCardinalityIndexLeafPagePart.class::isInstance).count();
		assertTrue(leafPageCount >= 2, "a paged index must emit at least two leaf pages");
		assertTrue(
			emittedParts.stream().anyMatch(it -> it instanceof ReferenceTypeCardinalityIndexStoragePart root && root.isPaged()),
			"a paged index must emit a paged root part"
		);

		// persist every emitted (non-removal) part through the real OffsetIndex + Kryo layer, then reload
		final OffsetIndexDescriptor descriptor = persist(emittedParts);
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor);
			final KeyCompressor compressor = reloaded.getReadOnlyKeyCompressor();
			final long rootPK = ReferenceTypeCardinalityIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, REFERENCE_NAME, compressor
			);
			final ReferenceTypeCardinalityIndexStoragePart root =
				reloaded.get(PERSISTED_VERSION, rootPK, ReferenceTypeCardinalityIndexStoragePart.class);
			assertNotNull(root, "the paged root part must be readable after reload");
			assertTrue(root.isPaged(), "the reloaded root must be paged");

			// the leaf-page stream id is the compressed id of the sub-index's stream key (the same key the engine resolves
			// store-side when computing each leaf page's primary key)
			final int streamId = compressor.getId(new ReferenceTypeCardinalityLeafStreamKey(ENTITY_INDEX_PK, REFERENCE_NAME));
			final ReferenceTypeCardinalityIndex restored = loadPagedIndex(reloaded, PERSISTED_VERSION, streamId, root);

			assertTrue(restored.isPaged(), "the reassembled index must still be paged");
			assertSameCardinalityIndex(source, restored, "paged round-trip");
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A small inline (SINGLE) cardinality index still loads after a real OffsetIndex round-trip")
	void shouldRoundTripSingleReferenceTypeCardinalityIndexThroughOffsetIndex() {
		final ReferenceTypeCardinalityIndex source = new ReferenceTypeCardinalityIndex();
		// a handful of records stays well within a single leaf (the SINGLE shape)
		for (int i = 0; i < 3; i++) {
			source.addRecord(indexPkForOrdinal(i), refPkForOrdinal(i));
		}
		assertFalse(source.isPaged(), "a small index must stay within a single leaf (the SINGLE shape)");

		final TrappedChanges trappedChanges = new TrappedChanges();
		source.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, trappedChanges);
		final List<StoragePart> emittedParts = collectParts(trappedChanges);

		final OffsetIndexDescriptor descriptor = persist(emittedParts);
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor);
			final KeyCompressor compressor = reloaded.getReadOnlyKeyCompressor();
			final long rootPK = ReferenceTypeCardinalityIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, REFERENCE_NAME, compressor
			);
			final ReferenceTypeCardinalityIndexStoragePart root =
				reloaded.get(PERSISTED_VERSION, rootPK, ReferenceTypeCardinalityIndexStoragePart.class);
			assertNotNull(root, "the inline root part must be readable after reload");
			assertFalse(root.isPaged(), "a small index must round-trip as non-paged");
			assertNotNull(root.getKeys(), "a SINGLE root carries the key column inline");
			assertNotNull(root.getPayloads(), "a SINGLE root carries the count column inline");

			// rebuild from the inline (keys, payloads) columns + the inline companion map
			final Map<Long, Integer> cardinalities = inlineCardinalities(root.getKeys(), root.getPayloads());
			final ReferenceTypeCardinalityIndex restored =
				new ReferenceTypeCardinalityIndex(cardinalities, root.getReferencedPrimaryKeysIndex());
			assertFalse(restored.isPaged(), "a small inline index reloads as SINGLE (not paged)");
			assertSameCardinalityIndex(source, restored, "inline round-trip");
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A leaf merge after a paged flush frees its dropped leaf pages and removes them on the next flush")
	void shouldRemoveFreedLeafPagesWhenLeavesMergeOnReflush() {
		final ReferenceTypeCardinalityIndex source = new ReferenceTypeCardinalityIndex();
		// span several leaves so a later contiguous removal can merge and free a leaf while the index stays paged
		for (int i = 0; i < MERGE_KEY_COUNT; i++) {
			source.addRecord(indexPkForOrdinal(i), refPkForOrdinal(i));
		}
		assertTrue(source.isPaged(), "the index must span multiple leaves to exercise the paged layout");

		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		OffsetIndexDescriptor secondDescriptor = null;
		int[] freedSequences = null;
		try {
			// first flush (version 1): persist every leaf page + the paged root
			final TrappedChanges firstChanges = new TrappedChanges();
			source.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, firstChanges);
			writeEmission(offsetIndex, PERSISTED_VERSION, collectAllParts(firstChanges));
			offsetIndex.flush(PERSISTED_VERSION);

			// reopen the persisted pages into a fresh index (restores the page-stream live-set baseline from disk)
			final KeyCompressor compressor = offsetIndex.getReadOnlyKeyCompressor();
			final int streamId = compressor.getId(new ReferenceTypeCardinalityLeafStreamKey(ENTITY_INDEX_PK, REFERENCE_NAME));
			final long rootPK = ReferenceTypeCardinalityIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, REFERENCE_NAME, compressor
			);
			final ReferenceTypeCardinalityIndexStoragePart firstRoot =
				offsetIndex.get(PERSISTED_VERSION, rootPK, ReferenceTypeCardinalityIndexStoragePart.class);
			assertNotNull(firstRoot, "the paged root part must be readable after the first flush");
			assertTrue(firstRoot.isPaged(), "the index must be paged before the merge");
			final int[] liveBeforeMerge = firstRoot.getLeafPageSequences();
			final ReferenceTypeCardinalityIndex restored = loadPagedIndex(offsetIndex, PERSISTED_VERSION, streamId, firstRoot);

			// merge: drop a long contiguous run of tuples so at least one leaf empties and merges into a sibling
			for (int i = MERGE_REMOVE_FROM; i < MERGE_REMOVE_TO; i++) {
				restored.removeRecord(indexPkForOrdinal(i), refPkForOrdinal(i));
			}
			assertTrue(restored.isPaged(), "the shrunken index must still span multiple leaves (PAGED -> PAGED)");

			// second flush (version 2): leaf-page removals INCLUDED this time
			final TrappedChanges secondChanges = new TrappedChanges();
			restored.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, secondChanges);
			final List<StoragePart> secondEmission = collectAllParts(secondChanges);
			final List<ReferenceTypeCardinalityIndexLeafPageRemoval> removals = secondEmission.stream()
				.filter(ReferenceTypeCardinalityIndexLeafPageRemoval.class::isInstance)
				.map(ReferenceTypeCardinalityIndexLeafPageRemoval.class::cast)
				.toList();
			final ReferenceTypeCardinalityIndexStoragePart secondRoot = secondEmission.stream()
				.filter(ReferenceTypeCardinalityIndexStoragePart.class::isInstance)
				.map(ReferenceTypeCardinalityIndexStoragePart.class::cast)
				.findFirst()
				.orElseThrow();
			assertTrue(secondRoot.isPaged(), "the second flush must still emit a paged root");

			// (a) the merge must free at least one leaf and emit exactly one removal per freed page sequence
			freedSequences = freedSequences(liveBeforeMerge, secondRoot.getLeafPageSequences());
			assertTrue(freedSequences.length >= 1, "a leaf merge must free at least one leaf page");
			assertEquals(
				freedSequences.length, removals.size(),
				"exactly one ReferenceTypeCardinalityIndexLeafPageRemoval must be emitted per freed leaf page"
			);

			// (b) every freed page sequence must be gone from the new live page set
			final Set<Integer> liveAfterSet = toSet(secondRoot.getLeafPageSequences());
			for (final int freedSequence : freedSequences) {
				assertFalse(
					liveAfterSet.contains(freedSequence),
					"freed page " + freedSequence + " must drop out of the live page set"
				);
			}

			// apply the second emission (removals INCLUDED) to the SAME offset index, then flush
			writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
			secondDescriptor = offsetIndex.flush(SECOND_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}

		// (c) reopen the file and verify it now equals an index built directly from only the surviving tuples
		OffsetIndex reopened = null;
		try {
			reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
			final KeyCompressor compressor = reopened.getReadOnlyKeyCompressor();
			final int streamId = compressor.getId(new ReferenceTypeCardinalityLeafStreamKey(ENTITY_INDEX_PK, REFERENCE_NAME));
			final long rootPK = ReferenceTypeCardinalityIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, REFERENCE_NAME, compressor
			);
			final ReferenceTypeCardinalityIndexStoragePart finalRoot =
				reopened.get(SECOND_VERSION, rootPK, ReferenceTypeCardinalityIndexStoragePart.class);
			assertNotNull(finalRoot, "the paged root must be readable after the second flush");

			// the freed leaf pages must be physically gone from storage, not merely unreferenced
			for (final int freedSequence : freedSequences) {
				assertNull(
					reopened.get(
						SECOND_VERSION,
						ReferenceTypeCardinalityIndexLeafPagePart.computeUniquePartId(streamId, freedSequence),
						ReferenceTypeCardinalityIndexLeafPagePart.class
					),
					"freed leaf page " + freedSequence + " must be removed from storage"
				);
			}

			final ReferenceTypeCardinalityIndex reloaded = loadPagedIndex(reopened, SECOND_VERSION, streamId, finalRoot);

			// an index built directly from only the surviving tuples is the oracle
			final ReferenceTypeCardinalityIndex expected = new ReferenceTypeCardinalityIndex();
			for (int i = 0; i < MERGE_KEY_COUNT; i++) {
				if (i < MERGE_REMOVE_FROM || i >= MERGE_REMOVE_TO) {
					expected.addRecord(indexPkForOrdinal(i), refPkForOrdinal(i));
				}
			}

			assertTrue(reloaded.isPaged(), "the reloaded survivor index must still be paged");
			assertSameCardinalityIndex(expected, reloaded, "merge survivor round-trip");
		} finally {
			if (reopened != null) {
				IOUtils.closeQuietly(reopened::close);
			}
		}
	}

	@Test
	@DisplayName("A PAGED index collapsing to SINGLE removes every prior leaf page and reloads as inline on reflush")
	void shouldCollapsePagedIndexToSingleAndRemoveAllPriorLeafPagesOnReflush() {
		final ReferenceTypeCardinalityIndex source = new ReferenceTypeCardinalityIndex();
		// span several leaves so the index starts PAGED
		for (int i = 0; i < KEY_COUNT; i++) {
			source.addRecord(indexPkForOrdinal(i), refPkForOrdinal(i));
		}
		assertTrue(source.isPaged(), "the index must span multiple leaves to exercise the collapse");

		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		OffsetIndexDescriptor secondDescriptor = null;
		int[] liveBeforeCollapse = null;
		try {
			// first flush (version 1): persist every leaf page + the paged root
			final TrappedChanges firstChanges = new TrappedChanges();
			source.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, firstChanges);
			writeEmission(offsetIndex, PERSISTED_VERSION, collectAllParts(firstChanges));
			offsetIndex.flush(PERSISTED_VERSION);

			// reopen the persisted pages into a fresh index (restores the page-stream live-set baseline from disk)
			final KeyCompressor compressor = offsetIndex.getReadOnlyKeyCompressor();
			final int streamId = compressor.getId(new ReferenceTypeCardinalityLeafStreamKey(ENTITY_INDEX_PK, REFERENCE_NAME));
			final long rootPK = ReferenceTypeCardinalityIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, REFERENCE_NAME, compressor
			);
			final ReferenceTypeCardinalityIndexStoragePart firstRoot =
				offsetIndex.get(PERSISTED_VERSION, rootPK, ReferenceTypeCardinalityIndexStoragePart.class);
			assertNotNull(firstRoot, "the paged root part must be readable after the first flush");
			assertTrue(firstRoot.isPaged(), "the index must be paged before the collapse");
			liveBeforeCollapse = firstRoot.getLeafPageSequences();
			final ReferenceTypeCardinalityIndex restored = loadPagedIndex(offsetIndex, PERSISTED_VERSION, streamId, firstRoot);

			// collapse: remove enough tuples that the survivors fit within a single leaf (PAGED -> SINGLE)
			for (int i = COLLAPSE_KEEP; i < KEY_COUNT; i++) {
				restored.removeRecord(indexPkForOrdinal(i), refPkForOrdinal(i));
			}
			assertFalse(restored.isPaged(), "the shrunken index must collapse to a single leaf (PAGED -> SINGLE)");

			// second flush (version 2): a SINGLE root + one leaf-page removal per previously-live leaf page
			final TrappedChanges secondChanges = new TrappedChanges();
			restored.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, secondChanges);
			final List<StoragePart> secondEmission = collectAllParts(secondChanges);
			final List<ReferenceTypeCardinalityIndexLeafPageRemoval> removals = secondEmission.stream()
				.filter(ReferenceTypeCardinalityIndexLeafPageRemoval.class::isInstance)
				.map(ReferenceTypeCardinalityIndexLeafPageRemoval.class::cast)
				.toList();
			final ReferenceTypeCardinalityIndexStoragePart secondRoot = secondEmission.stream()
				.filter(ReferenceTypeCardinalityIndexStoragePart.class::isInstance)
				.map(ReferenceTypeCardinalityIndexStoragePart.class::cast)
				.findFirst()
				.orElseThrow();
			assertFalse(secondRoot.isPaged(), "the collapsed index must emit an inline (SINGLE) root");
			assertEquals(
				liveBeforeCollapse.length, removals.size(),
				"exactly one ReferenceTypeCardinalityIndexLeafPageRemoval must be emitted per previously-live leaf page"
			);

			// apply the second emission (removals INCLUDED) to the SAME offset index, then flush
			writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
			secondDescriptor = offsetIndex.flush(SECOND_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}

		// reopen the file and verify every prior leaf page is physically gone and the SINGLE index equals the survivors
		OffsetIndex reopened = null;
		try {
			reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
			final KeyCompressor compressor = reopened.getReadOnlyKeyCompressor();
			final int streamId = compressor.getId(new ReferenceTypeCardinalityLeafStreamKey(ENTITY_INDEX_PK, REFERENCE_NAME));
			final long rootPK = ReferenceTypeCardinalityIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, REFERENCE_NAME, compressor
			);
			final ReferenceTypeCardinalityIndexStoragePart finalRoot =
				reopened.get(SECOND_VERSION, rootPK, ReferenceTypeCardinalityIndexStoragePart.class);
			assertNotNull(finalRoot, "the inline root must be readable after the collapse flush");
			assertFalse(finalRoot.isPaged(), "the collapsed root must reload as SINGLE");
			assertNotNull(finalRoot.getKeys(), "a SINGLE root carries the key column inline");
			assertNotNull(finalRoot.getPayloads(), "a SINGLE root carries the count column inline");

			// every leaf page that was live before the collapse must be physically removed from storage
			for (final int priorSequence : liveBeforeCollapse) {
				assertNull(
					reopened.get(
						SECOND_VERSION,
						ReferenceTypeCardinalityIndexLeafPagePart.computeUniquePartId(streamId, priorSequence),
						ReferenceTypeCardinalityIndexLeafPagePart.class
					),
					"prior leaf page " + priorSequence + " must be removed from storage on collapse"
				);
			}

			// rebuild from the inline columns + the companion map and compare with the surviving-tuple oracle
			final Map<Long, Integer> cardinalities = inlineCardinalities(finalRoot.getKeys(), finalRoot.getPayloads());
			final ReferenceTypeCardinalityIndex reloaded =
				new ReferenceTypeCardinalityIndex(cardinalities, finalRoot.getReferencedPrimaryKeysIndex());
			assertFalse(reloaded.isPaged(), "the reloaded survivor index must be SINGLE");

			final ReferenceTypeCardinalityIndex expected = new ReferenceTypeCardinalityIndex();
			for (int i = 0; i < COLLAPSE_KEEP; i++) {
				expected.addRecord(indexPkForOrdinal(i), refPkForOrdinal(i));
			}
			assertSameCardinalityIndex(expected, reloaded, "collapse survivor round-trip");
		} finally {
			if (reopened != null) {
				IOUtils.closeQuietly(reopened::close);
			}
		}
	}

	/*
		PRIVATE HELPERS
	 */

	/**
	 * The (never-zero) index primary key for the given 0-based ordinal — index PKs start at 1.
	 */
	private static int indexPkForOrdinal(int ordinal) {
		return ordinal + 1;
	}

	/**
	 * A distinct referenced entity primary key for the given 0-based ordinal (offset well clear of the index-PK space).
	 */
	private static int refPkForOrdinal(int ordinal) {
		return 1_000_000 + ordinal;
	}

	/**
	 * Rebuilds the composed-key → cardinality count map from a SINGLE root's inline columns (positionally aligned).
	 */
	@Nonnull
	private static Map<Long, Integer> inlineCardinalities(@Nonnull long[] keys, @Nonnull long[] payloads) {
		final Map<Long, Integer> result = new HashMap<>(keys.length);
		for (int i = 0; i < keys.length; i++) {
			result.put(keys[i], (int) payloads[i]);
		}
		return result;
	}

	/**
	 * Asserts the two cardinality indexes carry the same composed-key → count map and the same companion
	 * `referencedEntityPrimaryKey → index-PK bitmap` map (key set and per-key index-PK arrays).
	 */
	private static void assertSameCardinalityIndex(
		@Nonnull ReferenceTypeCardinalityIndex expected,
		@Nonnull ReferenceTypeCardinalityIndex actual,
		@Nonnull String context
	) {
		assertEquals(expected.getCardinalities(), actual.getCardinalities(), context + ": cardinality map must round-trip");
		final Set<Integer> expectedRefs = new HashSet<>(expected.getReferencedPrimaryKeysIndex().keySet());
		final Set<Integer> actualRefs = new HashSet<>(actual.getReferencedPrimaryKeysIndex().keySet());
		assertEquals(expectedRefs, actualRefs, context + ": tracked referenced PK set must round-trip");
		for (final Integer referencedPk : expectedRefs) {
			assertArrayEquals(
				expected.getAllReferenceIndexes(referencedPk), actual.getAllReferenceIndexes(referencedPk),
				context + ": index-PK bitmap for referenced PK " + referencedPk + " must round-trip"
			);
		}
	}

	/**
	 * Drains the {@link TrappedChanges} into a list, keeping only the real (writable) storage parts — a first flush of a
	 * fresh index emits no {@link DeferredRemovalStoragePart} removals (this also drops any
	 * {@code ReferenceTypeCardinalityIndexLeafPageRemoval}, which is itself a {@link DeferredRemovalStoragePart}).
	 */
	@Nonnull
	private static List<StoragePart> collectParts(@Nonnull TrappedChanges trappedChanges) {
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			final StoragePart part = iterator.next();
			if (!(part instanceof DeferredRemovalStoragePart)) {
				parts.add(part);
			}
		}
		return parts;
	}

	/**
	 * Drains the {@link TrappedChanges} into a list keeping EVERY emitted part, including any
	 * {@link DeferredRemovalStoragePart} (the {@code ReferenceTypeCardinalityIndexLeafPageRemoval} a leaf merge produces
	 * on a subsequent flush). The counterpart of {@link #collectParts(TrappedChanges)}, which drops removals.
	 */
	@Nonnull
	private static List<StoragePart> collectAllParts(@Nonnull TrappedChanges trappedChanges) {
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Returns the page sequences present in {@code before} but absent from {@code after} — the leaf pages a merge freed.
	 */
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

	/**
	 * Boxes the page sequences into a {@link Set} for membership tests.
	 */
	@Nonnull
	private static Set<Integer> toSet(@Nonnull int[] sequences) {
		final Set<Integer> set = new HashSet<>(sequences.length);
		for (final int sequence : sequences) {
			set.add(sequence);
		}
		return set;
	}

	/**
	 * Reads the paged root's leaf pages back from the (open) offset index and reassembles a boundary-stable
	 * {@link ReferenceTypeCardinalityIndex} via {@link ReferenceTypeCardinalityIndex#fromPersistedPages} — the same path
	 * the loader takes on a cold load. Restoring from the persisted leaf-page list also seeds the page-stream live-set
	 * baseline, so a later flush can detect (and remove) the pages a merge frees.
	 */
	@Nonnull
	private static ReferenceTypeCardinalityIndex loadPagedIndex(
		@Nonnull OffsetIndex offsetIndex,
		long catalogVersion,
		int streamId,
		@Nonnull ReferenceTypeCardinalityIndexStoragePart root
	) {
		final int[] orderedPageSequences = root.getLeafPageSequences();
		final long[][] perPageKeys = new long[orderedPageSequences.length][];
		final long[][] perPagePayloads = new long[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final ReferenceTypeCardinalityIndexLeafPagePart leafPage = offsetIndex.get(
				catalogVersion,
				ReferenceTypeCardinalityIndexLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
				ReferenceTypeCardinalityIndexLeafPagePart.class
			);
			assertNotNull(leafPage, "leaf page " + orderedPageSequences[i] + " must be readable");
			perPageKeys[i] = leafPage.getKeys();
			perPagePayloads[i] = leafPage.getPayloads();
		}
		return ReferenceTypeCardinalityIndex.fromPersistedPages(
			"reference `test`", orderedPageSequences, perPageKeys, perPagePayloads,
			root.getHighWaterPageSequence(), root.getReferencedPrimaryKeysIndex()
		);
	}

	/**
	 * Writes a flush emission into the given (open) offset index at the given catalog version: regular parts are
	 * {@code put}, while each {@link DeferredRemovalStoragePart} resolves its store-side primary key against the live
	 * read-only key compressor and is {@code remove}d — exactly what the production flush drain does.
	 */
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

	/**
	 * Opens a fresh writable real {@link OffsetIndex} on {@link #targetFile}. The caller drives the flush lifecycle and
	 * closes it; used by the multi-flush merge scenario, which must keep one offset index open across two flushes so the
	 * second flush's removals resolve against the same key-compressor dictionary the first flush registered.
	 */
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

	/**
	 * Writes every supplied part into a fresh real {@link OffsetIndex} on {@link #targetFile} and flushes, returning the
	 * resulting on-disk descriptor. The offset index (and its write handle) are closed before return so the file can be
	 * reopened for the reload.
	 */
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

	/**
	 * Reopens the flushed {@link #targetFile} as a fresh {@link OffsetIndex} from the persisted descriptor at the
	 * {@link #PERSISTED_VERSION}, so every record is deserialized back from disk through the real Kryo serializers.
	 */
	@Nonnull
	private OffsetIndex loadOffsetIndex(@Nonnull OffsetIndexDescriptor descriptor) {
		return loadOffsetIndex(descriptor, PERSISTED_VERSION);
	}

	/**
	 * Reopens the flushed {@link #targetFile} as a fresh {@link OffsetIndex} from the persisted descriptor at the given
	 * catalog version, so records written by a later flush (the merge scenario's second, version-2 flush) are visible on
	 * reload.
	 */
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
