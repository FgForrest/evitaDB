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
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.function.Functions;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
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
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the granular (`PAGED`) persistence of {@link OwnerUniqueIndex} round-trips through the REAL Kryo +
 * {@link OffsetIndex} layer: a large standalone owner unique index is paged out as individual
 * {@link UniqueIndexLeafPagePart} leaf pages, written to a real on-disk offset index, reloaded, and reassembled
 * boundary-stable via {@link OwnerUniqueIndex#fromPersistedPages}. This is the end-to-end counterpart of
 * {@code OwnerUniqueIndexPagingTest}, which exercises the page emission at the index level only and therefore BYPASSES
 * the Kryo/OffsetIndex layer — so it could not catch a missing {@link UniqueIndexLeafPagePart} record-type byte in the
 * index storage-part registry. Driving the leaf pages through a real {@link OffsetIndex} +
 * {@link OffsetIndexRecordTypeRegistry} closes that gap. Also verifies a legacy inline (`SINGLE`) part still loads (BWC).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(ATTRIBUTE)
class OwnerUniqueIndexPagingRoundTripTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_INDEX_PK = 1;
	/**
	 * Enough distinct keys to split the 256-entry leaf block into more than one leaf (so the index is `PAGED`).
	 */
	private static final int KEY_COUNT = 400;
	/**
	 * Enough distinct keys to span several leaves (the 256-entry block splits into ~4 leaves) so a later contiguous
	 * removal can merge and free a leaf while the index STAYS paged (the `PAGED -> PAGED` freed-page scenario).
	 */
	private static final int MERGE_KEY_COUNT = 900;
	/**
	 * Inclusive start of the contiguous key run removed to force leaf merges; the survivors still span more than one leaf.
	 */
	private static final int MERGE_REMOVE_FROM = 250;
	/**
	 * Exclusive end of the contiguous removed key run.
	 */
	private static final int MERGE_REMOVE_TO = 700;
	/**
	 * Enough distinct keys to page the value tree out across several leaves (the 256-entry block splits at 128, so an
	 * ascending run of this many keys lays out as ~7 leaves) before the warm-up collapse scenario shrinks it back.
	 */
	private static final int COLLAPSE_KEY_COUNT = 900;
	/**
	 * Distinct keys kept after the collapse shrink — well within the 256-entry leaf, so the value tree collapses out of
	 * the `PAGED` shape back to the inline `SINGLE` one.
	 */
	private static final int COLLAPSE_KEEP = 40;
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
		this.targetFile = Files.createTempFile("ownerUniquePagingRoundTrip", ".kryo");
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
		this.targetFile.toFile().delete();
	}

	@Test
	@DisplayName("A multi-leaf owner unique index pages out and reloads identically through the OffsetIndex")
	void shouldRoundTripPagedOwnerUniqueIndexThroughOffsetIndex() {
		final AttributeIndexKey attributeIndexKey = new AttributeIndexKey(null, "url", Locale.ENGLISH);
		final OwnerUniqueIndex source = new OwnerUniqueIndex(ENTITY_TYPE, attributeIndexKey, String.class);
		// register enough distinct URL-slug keys (sharing a long common prefix → the front-coded leaf column win) to
		// force the value tree to span many leaves
		for (int i = 0; i < KEY_COUNT; i++) {
			source.registerUniqueKey(keyForIndex(i), i + 1);
		}
		assertTrue(source.isPaged(), "the index must span multiple leaves to exercise the paged layout");

		final Bitmap expectedRecordIds = source.getRecordIds();

		// collect the granular emission (leaf pages + paged root; no freed-page removals on a first flush)
		final TrappedChanges trappedChanges = new TrappedChanges();
		source.appendStorageParts(ENTITY_INDEX_PK, trappedChanges);
		final List<StoragePart> emittedParts = collectParts(trappedChanges);
		final long leafPageCount = emittedParts.stream().filter(UniqueIndexLeafPagePart.class::isInstance).count();
		assertTrue(leafPageCount >= 2, "a paged index must emit at least two leaf pages");
		assertTrue(
			emittedParts.stream().anyMatch(it -> it instanceof UniqueIndexStoragePart root && root.isPaged()),
			"a paged index must emit a paged root part"
		);

		// persist every emitted (non-removal) part through the real OffsetIndex + Kryo layer, then reload
		final OffsetIndexDescriptor descriptor = persist(emittedParts);
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor);
			final KeyCompressor compressor = reloaded.getReadOnlyKeyCompressor();
			final long rootPK = AttributeIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, AttributeIndexType.UNIQUE, attributeIndexKey, compressor
			);
			final UniqueIndexStoragePart root = reloaded.get(PERSISTED_VERSION, rootPK, UniqueIndexStoragePart.class);
			assertNotNull(root, "the paged root part must be readable after reload");
			assertTrue(root.isPaged(), "the reloaded root must be paged");

			// the leaf-page stream id is the compressed id of the sub-index's LeafStreamKey (the same BUCKET-kind key the
			// engine resolves store-side when computing each leaf page's primary key)
			final int streamId = compressor.getId(
				new LeafStreamKey(ENTITY_INDEX_PK, new AttributeKeyWithIndexType(attributeIndexKey, AttributeIndexType.UNIQUE))
			);
			final int[] orderedPageSequences = root.getLeafPageSequences();
			final Serializable[][] perPageValues = new Serializable[orderedPageSequences.length][];
			final int[][] perPageRecordIds = new int[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final UniqueIndexLeafPagePart leafPage = reloaded.get(
					PERSISTED_VERSION,
					AbstractLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
					UniqueIndexLeafPagePart.class
				);
				assertNotNull(leafPage, "leaf page " + orderedPageSequences[i] + " must be readable after reload");
				perPageValues[i] = leafPage.getValues();
				perPageRecordIds[i] = leafPage.getRecordIds();
			}

			final OwnerUniqueIndex restored = OwnerUniqueIndex.fromPersistedPages(
				ENTITY_TYPE, attributeIndexKey, String.class,
				orderedPageSequences, perPageValues, perPageRecordIds, root.getHighWaterPageSequence()
			);

			assertTrue(restored.isPaged(), "the reassembled index must still be paged");
			assertEquals(source.size(), restored.size(), "size must round-trip");
			assertArrayEquals(
				expectedRecordIds.getArray(), restored.getRecordIds().getArray(),
				"the record-id bitmap must round-trip identically"
			);
			// every value -> record id mapping survives the page round-trip through the real OffsetIndex
			for (int i = 0; i < KEY_COUNT; i++) {
				assertEquals(
					Integer.valueOf(i + 1), restored.getRecordIdByUniqueValue(keyForIndex(i)),
					"point lookup for record " + (i + 1) + " must round-trip"
				);
			}
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A legacy inline (SINGLE) owner unique part still loads after a real OffsetIndex round-trip")
	void shouldRoundTripLegacyInlineOwnerUniqueIndexThroughOffsetIndex() {
		final AttributeIndexKey attributeIndexKey = new AttributeIndexKey(null, "code", null);
		// construct a legacy inline (non-paged) OWNER part directly: the inline value + record-id columns in ascending
		// key order, positionally aligned
		final Serializable[] inlineValues = {"alpha", "beta", "gamma"};
		final int[] inlineRecordIds = {1, 2, 3};
		final UniqueIndexStoragePart inlinePart = new UniqueIndexStoragePart(
			ENTITY_INDEX_PK, attributeIndexKey, String.class, inlineValues, inlineRecordIds
		);

		final OffsetIndexDescriptor descriptor = persist(List.of(inlinePart));
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor);
			final KeyCompressor compressor = reloaded.getReadOnlyKeyCompressor();
			final long rootPK = AttributeIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, AttributeIndexType.UNIQUE, attributeIndexKey, compressor
			);
			final UniqueIndexStoragePart root = reloaded.get(PERSISTED_VERSION, rootPK, UniqueIndexStoragePart.class);
			assertNotNull(root, "the inline root part must be readable after reload");
			assertFalse(root.isPaged(), "a legacy inline part must round-trip as non-paged");
			assertNotNull(root.getValues(), "a SINGLE root carries the value column inline");

			final OwnerUniqueIndex restored = new OwnerUniqueIndex(
				ENTITY_TYPE, attributeIndexKey, String.class, root.getValues(), root.getRecordIds()
			);
			assertFalse(restored.isPaged(), "a small inline index reloads as SINGLE (not paged)");
			assertEquals(3, restored.size(), "the per-type record set must be rebuilt from the inline columns");
			assertArrayEquals(
				new int[] {1, 2, 3}, restored.getRecordIds().getArray(), "the record-id bitmap must round-trip"
			);
			assertEquals(Integer.valueOf(1), restored.getRecordIdByUniqueValue("alpha"), "`alpha` must resolve to record 1");
			assertEquals(Integer.valueOf(2), restored.getRecordIdByUniqueValue("beta"), "`beta` must resolve to record 2");
			assertEquals(Integer.valueOf(3), restored.getRecordIdByUniqueValue("gamma"), "`gamma` must resolve to record 3");
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A leaf merge after a paged flush frees its dropped leaf pages and removes them on the next flush")
	void shouldRemoveFreedLeafPagesWhenLeavesMergeOnReflush() {
		final AttributeIndexKey attributeIndexKey = new AttributeIndexKey(null, "url", Locale.ENGLISH);
		final OwnerUniqueIndex source = new OwnerUniqueIndex(ENTITY_TYPE, attributeIndexKey, String.class);
		// span several leaves so a later contiguous removal can merge and free a leaf while the index stays paged
		for (int i = 0; i < MERGE_KEY_COUNT; i++) {
			source.registerUniqueKey(keyForIndex(i), i + 1);
		}
		assertTrue(source.isPaged(), "the index must span multiple leaves to exercise the paged layout");

		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(attributeIndexKey, AttributeIndexType.UNIQUE);
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		OffsetIndexDescriptor secondDescriptor = null;
		int[] freedSequences = null;
		try {
			// first flush (version 1): persist every leaf page + the paged root
			final TrappedChanges firstChanges = new TrappedChanges();
			source.appendStorageParts(ENTITY_INDEX_PK, firstChanges);
			writeEmission(offsetIndex, PERSISTED_VERSION, collectAllParts(firstChanges));
			offsetIndex.flush(PERSISTED_VERSION);

			// reopen the persisted pages into a fresh index (restores the page-stream live-set baseline from disk)
			final KeyCompressor compressor = offsetIndex.getReadOnlyKeyCompressor();
			final int streamId = compressor.getId(new LeafStreamKey(ENTITY_INDEX_PK, streamKey));
			final long rootPK = AttributeIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, AttributeIndexType.UNIQUE, attributeIndexKey, compressor
			);
			final UniqueIndexStoragePart firstRoot = offsetIndex.get(PERSISTED_VERSION, rootPK, UniqueIndexStoragePart.class);
			assertNotNull(firstRoot, "the paged root part must be readable after the first flush");
			assertTrue(firstRoot.isPaged(), "the index must be paged before the merge");
			final int[] liveBeforeMerge = firstRoot.getLeafPageSequences();
			final OwnerUniqueIndex restored = loadPagedIndex(offsetIndex, PERSISTED_VERSION, attributeIndexKey, streamId, firstRoot);

			// merge: drop a long contiguous run of keys so at least one leaf empties and merges into a sibling
			for (int i = MERGE_REMOVE_FROM; i < MERGE_REMOVE_TO; i++) {
				restored.unregisterUniqueKey(keyForIndex(i), i + 1);
			}
			assertTrue(restored.isPaged(), "the shrunken index must still span multiple leaves (PAGED -> PAGED)");

			// second flush (version 2): leaf-page removals INCLUDED this time
			final TrappedChanges secondChanges = new TrappedChanges();
			restored.appendStorageParts(ENTITY_INDEX_PK, secondChanges);
			final List<StoragePart> secondEmission = collectAllParts(secondChanges);
			final List<UniqueIndexLeafPageRemoval> removals = secondEmission.stream()
				.filter(UniqueIndexLeafPageRemoval.class::isInstance)
				.map(UniqueIndexLeafPageRemoval.class::cast)
				.toList();
			final UniqueIndexStoragePart secondRoot = secondEmission.stream()
				.filter(UniqueIndexStoragePart.class::isInstance)
				.map(UniqueIndexStoragePart.class::cast)
				.findFirst()
				.orElseThrow();
			assertTrue(secondRoot.isPaged(), "the second flush must still emit a paged root");

			// (a) the merge must free at least one leaf and emit exactly one removal per freed page sequence
			freedSequences = freedSequences(liveBeforeMerge, secondRoot.getLeafPageSequences());
			assertTrue(freedSequences.length >= 1, "a leaf merge must free at least one leaf page");
			assertEquals(
				freedSequences.length, removals.size(),
				"exactly one UniqueIndexLeafPageRemoval must be emitted per freed leaf page"
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

		// (c) reopen the file and verify it now equals an index built directly from only the surviving values
		OffsetIndex reopened = null;
		try {
			reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
			final KeyCompressor compressor = reopened.getReadOnlyKeyCompressor();
			final int streamId = compressor.getId(new LeafStreamKey(ENTITY_INDEX_PK, streamKey));
			final long rootPK = AttributeIndexStoragePart.computeUniquePartId(
				ENTITY_INDEX_PK, AttributeIndexType.UNIQUE, attributeIndexKey, compressor
			);
			final UniqueIndexStoragePart finalRoot = reopened.get(SECOND_VERSION, rootPK, UniqueIndexStoragePart.class);
			assertNotNull(finalRoot, "the paged root must be readable after the second flush");

			// the freed leaf pages must be physically gone from storage, not merely unreferenced
			for (final int freedSequence : freedSequences) {
				assertNull(
					reopened.get(
						SECOND_VERSION,
						AbstractLeafPagePart.computeUniquePartId(streamId, freedSequence),
						UniqueIndexLeafPagePart.class
					),
					"freed leaf page " + freedSequence + " must be removed from storage"
				);
			}

			final OwnerUniqueIndex reloaded = loadPagedIndex(reopened, SECOND_VERSION, attributeIndexKey, streamId, finalRoot);

			// an index built directly from only the surviving values is the oracle
			final OwnerUniqueIndex expected = new OwnerUniqueIndex(ENTITY_TYPE, attributeIndexKey, String.class);
			for (int i = 0; i < MERGE_KEY_COUNT; i++) {
				if (i < MERGE_REMOVE_FROM || i >= MERGE_REMOVE_TO) {
					expected.registerUniqueKey(keyForIndex(i), i + 1);
				}
			}

			assertTrue(reloaded.isPaged(), "the reloaded survivor index must still be paged");
			assertEquals(expected.size(), reloaded.size(), "the surviving size must match the oracle");
			assertArrayEquals(
				expected.getRecordIds().getArray(), reloaded.getRecordIds().getArray(),
				"the surviving record-id bitmap must match the oracle built from only the surviving values"
			);
			assertArrayEquals(
				expected.inlineSnapshot().values(), reloaded.inlineSnapshot().values(),
				"the surviving value column must match the oracle built from only the surviving values"
			);
			assertArrayEquals(
				expected.inlineSnapshot().recordIds(), reloaded.inlineSnapshot().recordIds(),
				"the surviving record-id column must match the oracle built from only the surviving values"
			);
			// every surviving value must still resolve to its owning record after the merge round-trip
			for (int i = 0; i < MERGE_KEY_COUNT; i++) {
				if (i < MERGE_REMOVE_FROM || i >= MERGE_REMOVE_TO) {
					assertEquals(
						Integer.valueOf(i + 1), reloaded.getRecordIdByUniqueValue(keyForIndex(i)),
						"surviving point lookup for record " + (i + 1) + " must round-trip"
					);
				}
			}
		} finally {
			if (reopened != null) {
				IOUtils.closeQuietly(reopened::close);
			}
		}
	}

	@Test
	@DisplayName("Collapsing PAGED -> SINGLE across two warm-up flushes still removes every prior leaf page")
	void shouldRemovePriorLeafPagesWhenCollapsingAcrossTwoWarmUpFlushesWithoutAnIntermediateReload() {
		// every other collapse test RELOADS the index before collapsing it, and the loader seeds the page-stream live-set
		// baseline from disk — so the published set is correct there and the collapse reclaims correctly either way. A
		// WARM_UP (bulk) catalog never reloads and never reaches a commit-merge — the only place the staged page set is
		// PUBLISHED — so its collapse must reclaim against the set the previous flush STAGED, not the published one, which
		// stays empty for the whole warm-up and would silently reclaim NOTHING.
		final AttributeIndexKey attributeIndexKey = new AttributeIndexKey(null, "url", Locale.ENGLISH);
		final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, attributeIndexKey, String.class);
		for (int i = 0; i < COLLAPSE_KEY_COUNT; i++) {
			index.registerUniqueKey(keyForIndex(i), i + 1);
		}
		assertTrue(index.isPaged(), "the index must span multiple leaves to exercise the paged layout");

		// first WARM_UP flush: allocates + STAGES one leaf page per leaf and publishes nothing (publishing happens only at
		// the commit-merge, which a warm-up flush never reaches)
		final TrappedChanges firstChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, firstChanges);
		final List<StoragePart> firstEmission = collectAllParts(firstChanges);
		index.resetDirty();

		// the prior page count is taken from the EMISSION (which the flush derives by walking the tree's leaves), never
		// from the registry accessor under test — sourcing it there would make the removal assertion below `0 == 0` and
		// leave this test green with or without the fix
		final int priorLeafPageCount = (int) firstEmission.stream()
			.filter(UniqueIndexLeafPagePart.class::isInstance).count();
		assertTrue(priorLeafPageCount >= 3, "the source index must start paged across several leaf pages");
		final UniqueIndexStoragePart firstRoot = firstEmission.stream()
			.filter(UniqueIndexStoragePart.class::isInstance)
			.map(UniqueIndexStoragePart.class::cast)
			.findFirst()
			.orElseThrow();
		assertTrue(firstRoot.isPaged(), "the first warm-up flush must emit a PAGED root");
		assertEquals(
			priorLeafPageCount, firstRoot.getLeafPageSequences().length,
			"the PAGED root must list exactly the leaf pages the first warm-up flush wrote"
		);
		assertEquals(
			0, firstEmission.stream().filter(UniqueIndexLeafPageRemoval.class::isInstance).count(),
			"a first warm-up flush frees no leaf page — nothing was on disk before it"
		);

		// collapse the SAME in-memory index — NO reload in between, exactly what a warm-up catalog does: drop all but a
		// handful of keys so the survivors fit within a single leaf
		for (int i = COLLAPSE_KEEP; i < COLLAPSE_KEY_COUNT; i++) {
			index.unregisterUniqueKey(keyForIndex(i), i + 1);
		}
		assertFalse(index.isPaged(), "an index that fits a single leaf must collapse out of the PAGED shape");

		final TrappedChanges secondChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, secondChanges);
		final List<StoragePart> secondEmission = collectAllParts(secondChanges);
		final UniqueIndexStoragePart collapsedRoot = secondEmission.stream()
			.filter(UniqueIndexStoragePart.class::isInstance)
			.map(UniqueIndexStoragePart.class::cast)
			.findFirst()
			.orElseThrow();
		assertFalse(collapsedRoot.isPaged(), "the collapsed index must emit a single inline (SINGLE) root");
		assertNotNull(collapsedRoot.getValues(), "a SINGLE root carries the value column inline");
		assertEquals(
			COLLAPSE_KEEP, collapsedRoot.getValues().length, "the SINGLE root must carry every surviving value inline"
		);
		assertEquals(
			0, secondEmission.stream().filter(UniqueIndexLeafPagePart.class::isInstance).count(),
			"a collapsed index must not re-emit any leaf page"
		);
		assertEquals(
			priorLeafPageCount, (int) secondEmission.stream().filter(UniqueIndexLeafPageRemoval.class::isInstance).count(),
			"the collapse must remove every leaf page the previous warm-up flush wrote — the append-only OffsetIndex never " +
				"reclaims a record that is neither superseded nor explicitly removed, so a missed removal leaks the page forever"
		);
	}

	/*
		PRIVATE HELPERS
	 */

	/**
	 * Builds a deterministic URL-slug-like key sharing a long common prefix (the front-coding win) for the given index.
	 */
	@Nonnull
	private static String keyForIndex(int i) {
		return "https://www.example.com/products/category/item-" + String.format("%05d", i);
	}

	/**
	 * Drains the {@link TrappedChanges} into a list, keeping only the real (writable) storage parts — a first flush of a
	 * fresh index emits no {@link DeferredRemovalStoragePart} removals (this also drops any {@code UniqueIndexLeafPageRemoval},
	 * which is itself a {@link DeferredRemovalStoragePart}).
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
	 * {@link DeferredRemovalStoragePart} (the {@link UniqueIndexLeafPageRemoval} a leaf merge produces on a subsequent
	 * flush). The counterpart of {@link #collectParts(TrappedChanges)}, which drops removals.
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
	 * {@link OwnerUniqueIndex} via {@link OwnerUniqueIndex#fromPersistedPages} — the same path
	 * {@code AttributeIndexLoader} takes on a cold load. Restoring from the persisted leaf-page list also seeds the
	 * page-stream live-set baseline, so a later flush can detect (and remove) the pages a merge frees.
	 */
	@Nonnull
	private static OwnerUniqueIndex loadPagedIndex(
		@Nonnull OffsetIndex offsetIndex,
		long catalogVersion,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int streamId,
		@Nonnull UniqueIndexStoragePart root
	) {
		final int[] orderedPageSequences = root.getLeafPageSequences();
		final Serializable[][] perPageValues = new Serializable[orderedPageSequences.length][];
		final int[][] perPageRecordIds = new int[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final UniqueIndexLeafPagePart leafPage = offsetIndex.get(
				catalogVersion,
				AbstractLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
				UniqueIndexLeafPagePart.class
			);
			assertNotNull(leafPage, "leaf page " + orderedPageSequences[i] + " must be readable");
			perPageValues[i] = leafPage.getValues();
			perPageRecordIds[i] = leafPage.getRecordIds();
		}
		return OwnerUniqueIndex.fromPersistedPages(
			ENTITY_TYPE, attributeIndexKey, String.class,
			orderedPageSequences, perPageValues, perPageRecordIds, root.getHighWaterPageSequence()
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
	 * {@link #PERSISTED_VERSION}, so every record is deserialized back from disk through the real Kryo serializers
	 * (mirrors {@code OffsetIndexTest}).
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
