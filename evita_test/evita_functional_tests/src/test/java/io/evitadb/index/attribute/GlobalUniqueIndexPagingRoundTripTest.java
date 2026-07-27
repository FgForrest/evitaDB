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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.function.Functions;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueLeafStreamKey;
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
import io.evitadb.test.Entities;
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
 * Verifies the granular (`PAGED`) persistence of {@link GlobalUniqueIndex} round-trips through the REAL Kryo +
 * {@link OffsetIndex} layer: a large index is paged out as individual {@link GlobalUniqueIndexLeafPagePart} leaf pages,
 * written to a real on-disk offset index, reloaded, and reassembled boundary-stable via
 * {@link GlobalUniqueIndex#fromPersistedPages}. Also verifies a small inline (`SINGLE`) index round-trips its embedded
 * value/payload columns.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(ATTRIBUTE)
class GlobalUniqueIndexPagingRoundTripTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = Entities.PRODUCT;
	private static final int PRODUCT_TYPE_PK = 1;
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
	 * Inclusive start of the contiguous key run removed to force leaf merges. Kept above the first key so the first
	 * registered locale survives and the surviving payload column stays byte-identical to the oracle.
	 */
	private static final int MERGE_REMOVE_FROM = 250;
	/**
	 * Exclusive end of the contiguous removed key run; the survivors still span more than one leaf.
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

	private final Catalog catalog = Mockito.mock(Catalog.class);
	private final EntityTypeClassifierResolver classifierResolver = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return GlobalUniqueIndexPagingRoundTripTest.this.catalog
				.getCollectionForEntityOrThrowException(entityType).getEntityTypePrimaryKey();
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return GlobalUniqueIndexPagingRoundTripTest.this.catalog
				.getCollectionForEntityPrimaryKeyOrThrowException(entityTypePrimaryKey).getEntityType();
		}
	};
	private final OffsetIndexRecordTypeRegistry recordRegistry = new OffsetIndexRecordTypeRegistry();
	private final StorageSettings storageSettings = new StorageSettings(
		StorageOptions.temporary(), TransactionOptions.builder().build()
	);
	private ObservableOutputKeeper observableOutputKeeper;
	private Path targetFile;

	@BeforeEach
	void setUp() throws Exception {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(PRODUCT_TYPE_PK);
		Mockito.when(productCollection.getEntityType()).thenReturn(ENTITY_TYPE);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(PRODUCT_TYPE_PK)).thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(ENTITY_TYPE)).thenReturn(productCollection);
		this.observableOutputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
		this.targetFile = Files.createTempFile("globalUniquePagingRoundTrip", ".kryo");
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
		this.targetFile.toFile().delete();
	}

	@Test
	@DisplayName("A multi-leaf global unique index pages out and reloads identically through the OffsetIndex")
	void shouldRoundTripPagedGlobalUniqueIndexThroughOffsetIndex() {
		final AttributeKey attributeKey = new AttributeKey("url", Locale.ENGLISH);
		final GlobalUniqueIndex source = new GlobalUniqueIndex(Scope.LIVE, attributeKey, String.class);
		// register enough distinct URL-slug keys (alternating two locales) to force the value tree to span many leaves
		for (int i = 0; i < KEY_COUNT; i++) {
			final Locale locale = (i % 2 == 0) ? Locale.ENGLISH : Locale.FRENCH;
			source.registerUniqueKey(keyForIndex(i), ENTITY_TYPE, locale, i + 1, this.classifierResolver);
		}
		assertTrue(source.isPaged(), "the index must span multiple leaves to exercise the paged layout");

		final GlobalUniqueIndex.InlineSnapshot expectedSnapshot = source.inlineSnapshot();
		final Bitmap expectedRecordIds = source.getRecordIds(ENTITY_TYPE, this.classifierResolver);

		// collect the granular emission (leaf pages + paged root; no freed-page removals on a first flush)
		final TrappedChanges trappedChanges = new TrappedChanges();
		source.appendStorageParts(attributeKey, trappedChanges);
		final List<StoragePart> emittedParts = collectParts(trappedChanges);
		final long leafPageCount = emittedParts.stream().filter(GlobalUniqueIndexLeafPagePart.class::isInstance).count();
		assertTrue(leafPageCount >= 2, "a paged index must emit at least two leaf pages");
		assertTrue(
			emittedParts.stream().anyMatch(it -> it instanceof GlobalUniqueIndexStoragePart root && root.isPaged()),
			"a paged index must emit a paged root part"
		);

		// persist every emitted (non-removal) part through the real OffsetIndex + Kryo layer, then reload
		final OffsetIndexDescriptor descriptor = persist(emittedParts);
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor);
			final KeyCompressor compressor = reloaded.getReadOnlyKeyCompressor();
			final long rootPK = GlobalUniqueIndexStoragePart.computeUniquePartId(Scope.LIVE, attributeKey, compressor);
			final GlobalUniqueIndexStoragePart root = reloaded.get(PERSISTED_VERSION, rootPK, GlobalUniqueIndexStoragePart.class);
			assertNotNull(root, "the paged root part must be readable after reload");
			assertTrue(root.isPaged(), "the reloaded root must be paged");

			final int streamId = compressor.getId(new GlobalUniqueLeafStreamKey(Scope.LIVE, attributeKey));
			final int[] orderedPageSequences = root.getLeafPageSequences();
			final Serializable[][] perPageValues = new Serializable[orderedPageSequences.length][];
			final long[][] perPagePayloads = new long[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final GlobalUniqueIndexLeafPagePart leafPage = reloaded.get(
					PERSISTED_VERSION,
					GlobalUniqueIndexLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
					GlobalUniqueIndexLeafPagePart.class
				);
				assertNotNull(leafPage, "leaf page " + orderedPageSequences[i] + " must be readable after reload");
				perPageValues[i] = leafPage.getValues();
				perPagePayloads[i] = leafPage.getPayloads();
			}

			final GlobalUniqueIndex restored = GlobalUniqueIndex.fromPersistedPages(
				Scope.LIVE, attributeKey, String.class,
				orderedPageSequences, perPageValues, perPagePayloads,
				root.getHighWaterPageSequence(), root.getLocaleIndex()
			);

			// every value -> packed (entityType, pk, locale) payload survives the page round-trip, in identical key order
			final GlobalUniqueIndex.InlineSnapshot restoredSnapshot = restored.inlineSnapshot();
			assertArrayEquals(expectedSnapshot.values(), restoredSnapshot.values(), "value column must round-trip identically");
			assertArrayEquals(expectedSnapshot.payloads(), restoredSnapshot.payloads(), "payload column must round-trip identically");
			// the per-entity-type record set (rebuilt by unpacking every payload) matches
			assertEquals(
				expectedRecordIds.getArray().length, restored.getRecordIds(ENTITY_TYPE, this.classifierResolver).getArray().length,
				"per-type record cardinality must round-trip"
			);
			assertTrue(
				restored.getRecordIds(ENTITY_TYPE, this.classifierResolver).contains(1) && restored.getRecordIds(ENTITY_TYPE, this.classifierResolver).contains(KEY_COUNT),
				"per-type record set must contain the boundary primary keys"
			);
			// a spot-check that a localized lookup resolves to the expected entity reference
			final EntityReferenceWithLocale resolved =
				restored.getEntityReferenceByUniqueValue(keyForIndex(0), Locale.ENGLISH, this.classifierResolver).orElseThrow();
			assertEquals(ENTITY_TYPE, resolved.getType());
			assertEquals(1, resolved.getPrimaryKey());
			assertEquals(Locale.ENGLISH, resolved.locale());
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A small (SINGLE) global unique index round-trips inline through the OffsetIndex")
	void shouldRoundTripSingleGlobalUniqueIndexThroughOffsetIndex() {
		final AttributeKey attributeKey = new AttributeKey("code");
		// a small index stays in the inline SINGLE shape (a single embedded leaf): register a handful of non-localized keys
		final GlobalUniqueIndex source = new GlobalUniqueIndex(Scope.LIVE, attributeKey, String.class);
		source.registerUniqueKey("alpha", ENTITY_TYPE, null, 1, this.classifierResolver);
		source.registerUniqueKey("beta", ENTITY_TYPE, null, 2, this.classifierResolver);
		source.registerUniqueKey("gamma", ENTITY_TYPE, null, 3, this.classifierResolver);
		assertFalse(source.isPaged(), "a small index must stay in the inline SINGLE shape");

		final GlobalUniqueIndex.InlineSnapshot expectedSnapshot = source.inlineSnapshot();

		// the real production write path emits an inline (non-paged) root carrying the value/payload columns
		final TrappedChanges trappedChanges = new TrappedChanges();
		source.appendStorageParts(attributeKey, trappedChanges);
		final List<StoragePart> emittedParts = collectParts(trappedChanges);
		assertTrue(
			emittedParts.stream().anyMatch(it -> it instanceof GlobalUniqueIndexStoragePart root && !root.isPaged()),
			"a small index must emit an inline (non-paged) root part"
		);

		final OffsetIndexDescriptor descriptor = persist(emittedParts);
		OffsetIndex reloaded = null;
		try {
			reloaded = loadOffsetIndex(descriptor);
			final KeyCompressor compressor = reloaded.getReadOnlyKeyCompressor();
			final long rootPK = GlobalUniqueIndexStoragePart.computeUniquePartId(Scope.LIVE, attributeKey, compressor);
			final GlobalUniqueIndexStoragePart root = reloaded.get(PERSISTED_VERSION, rootPK, GlobalUniqueIndexStoragePart.class);
			assertNotNull(root, "the inline root part must be readable after reload");
			assertFalse(root.isPaged(), "a small index must round-trip as non-paged");
			assertNotNull(root.getValues(), "a SINGLE root carries the inline value column");
			assertNotNull(root.getPayloads(), "a SINGLE root carries the inline payload column");

			final GlobalUniqueIndex restored = new GlobalUniqueIndex(
				Scope.LIVE, attributeKey, String.class, root.getValues(), root.getPayloads(), root.getLocaleIndex()
			);

			final GlobalUniqueIndex.InlineSnapshot restoredSnapshot = restored.inlineSnapshot();
			assertArrayEquals(expectedSnapshot.values(), restoredSnapshot.values(), "inline value column must round-trip identically");
			assertArrayEquals(expectedSnapshot.payloads(), restoredSnapshot.payloads(), "inline payload column must round-trip identically");
			assertEquals(3, restored.getRecordIds(ENTITY_TYPE, this.classifierResolver).getArray().length, "per-type record set must be rebuilt from the inline columns");
		} finally {
			if (reloaded != null) {
				IOUtils.closeQuietly(reloaded::close);
			}
		}
	}

	@Test
	@DisplayName("A leaf merge after a paged flush frees its dropped leaf pages and removes them on the next flush")
	void shouldRemoveFreedLeafPagesWhenLeavesMergeOnReflush() {
		final AttributeKey attributeKey = new AttributeKey("url", Locale.ENGLISH);
		// a single locale keeps the packed payload column deterministic, so the surviving payloads can be asserted
		// byte-identical to the directly-built oracle below
		final GlobalUniqueIndex source = new GlobalUniqueIndex(Scope.LIVE, attributeKey, String.class);
		for (int i = 0; i < MERGE_KEY_COUNT; i++) {
			source.registerUniqueKey(keyForIndex(i), ENTITY_TYPE, Locale.ENGLISH, i + 1, this.classifierResolver);
		}
		assertTrue(source.isPaged(), "the index must span multiple leaves to exercise the paged layout");

		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		OffsetIndexDescriptor secondDescriptor = null;
		int[] freedSequences = null;
		try {
			// first flush (version 1): persist every leaf page + the paged root
			final TrappedChanges firstChanges = new TrappedChanges();
			source.appendStorageParts(attributeKey, firstChanges);
			writeEmission(offsetIndex, PERSISTED_VERSION, collectAllParts(firstChanges));
			offsetIndex.flush(PERSISTED_VERSION);

			// reopen the persisted pages into a fresh index (restores the page-stream live-set baseline from disk)
			final KeyCompressor compressor = offsetIndex.getReadOnlyKeyCompressor();
			final int streamId = compressor.getId(new GlobalUniqueLeafStreamKey(Scope.LIVE, attributeKey));
			final long rootPK = GlobalUniqueIndexStoragePart.computeUniquePartId(Scope.LIVE, attributeKey, compressor);
			final GlobalUniqueIndexStoragePart firstRoot =
				offsetIndex.get(PERSISTED_VERSION, rootPK, GlobalUniqueIndexStoragePart.class);
			assertNotNull(firstRoot, "the paged root part must be readable after the first flush");
			assertTrue(firstRoot.isPaged(), "the index must be paged before the merge");
			final int[] liveBeforeMerge = firstRoot.getLeafPageSequences();
			final GlobalUniqueIndex restored = loadPagedIndex(offsetIndex, PERSISTED_VERSION, attributeKey, streamId, firstRoot);

			// merge: drop a long contiguous run of keys so at least one leaf empties and merges into a sibling
			for (int i = MERGE_REMOVE_FROM; i < MERGE_REMOVE_TO; i++) {
				restored.unregisterUniqueKey(keyForIndex(i), ENTITY_TYPE, Locale.ENGLISH, i + 1, this.classifierResolver);
			}
			assertTrue(restored.isPaged(), "the shrunken index must still span multiple leaves (PAGED -> PAGED)");

			// second flush (version 2): leaf-page removals INCLUDED this time
			final TrappedChanges secondChanges = new TrappedChanges();
			restored.appendStorageParts(attributeKey, secondChanges);
			final List<StoragePart> secondEmission = collectAllParts(secondChanges);
			final List<GlobalUniqueIndexLeafPageRemoval> removals = secondEmission.stream()
				.filter(GlobalUniqueIndexLeafPageRemoval.class::isInstance)
				.map(GlobalUniqueIndexLeafPageRemoval.class::cast)
				.toList();
			final GlobalUniqueIndexStoragePart secondRoot = secondEmission.stream()
				.filter(GlobalUniqueIndexStoragePart.class::isInstance)
				.map(GlobalUniqueIndexStoragePart.class::cast)
				.findFirst()
				.orElseThrow();
			assertTrue(secondRoot.isPaged(), "the second flush must still emit a paged root");

			// (a) the merge must free at least one leaf and emit exactly one removal per freed page sequence
			freedSequences = freedSequences(liveBeforeMerge, secondRoot.getLeafPageSequences());
			assertTrue(freedSequences.length >= 1, "a leaf merge must free at least one leaf page");
			assertEquals(
				freedSequences.length, removals.size(),
				"exactly one GlobalUniqueIndexLeafPageRemoval must be emitted per freed leaf page"
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
			final int streamId = compressor.getId(new GlobalUniqueLeafStreamKey(Scope.LIVE, attributeKey));
			final long rootPK = GlobalUniqueIndexStoragePart.computeUniquePartId(Scope.LIVE, attributeKey, compressor);
			final GlobalUniqueIndexStoragePart finalRoot =
				reopened.get(SECOND_VERSION, rootPK, GlobalUniqueIndexStoragePart.class);
			assertNotNull(finalRoot, "the paged root must be readable after the second flush");

			// the freed leaf pages must be physically gone from storage, not merely unreferenced
			for (final int freedSequence : freedSequences) {
				assertNull(
					reopened.get(
						SECOND_VERSION,
						GlobalUniqueIndexLeafPagePart.computeUniquePartId(streamId, freedSequence),
						GlobalUniqueIndexLeafPagePart.class
					),
					"freed leaf page " + freedSequence + " must be removed from storage"
				);
			}

			final GlobalUniqueIndex reloaded = loadPagedIndex(reopened, SECOND_VERSION, attributeKey, streamId, finalRoot);

			// an index built directly from only the surviving values is the oracle
			final GlobalUniqueIndex expected = new GlobalUniqueIndex(Scope.LIVE, attributeKey, String.class);
			for (int i = 0; i < MERGE_KEY_COUNT; i++) {
				if (i < MERGE_REMOVE_FROM || i >= MERGE_REMOVE_TO) {
					expected.registerUniqueKey(keyForIndex(i), ENTITY_TYPE, Locale.ENGLISH, i + 1, this.classifierResolver);
				}
			}

			final GlobalUniqueIndex.InlineSnapshot expectedSnapshot = expected.inlineSnapshot();
			final GlobalUniqueIndex.InlineSnapshot reloadedSnapshot = reloaded.inlineSnapshot();
			assertArrayEquals(
				expectedSnapshot.values(), reloadedSnapshot.values(),
				"the surviving value column must match the oracle built from only the surviving values"
			);
			assertArrayEquals(
				expectedSnapshot.payloads(), reloadedSnapshot.payloads(),
				"the surviving payload column must match the oracle built from only the surviving values"
			);
			assertArrayEquals(
				expected.getRecordIds(ENTITY_TYPE, this.classifierResolver).getArray(),
				reloaded.getRecordIds(ENTITY_TYPE, this.classifierResolver).getArray(),
				"the surviving per-type record set must match the oracle"
			);
		} finally {
			if (reopened != null) {
				IOUtils.closeQuietly(reopened::close);
			}
		}
	}

	@Test
	@DisplayName("Collapsing PAGED -> SINGLE across two warm-up flushes still removes every prior leaf page")
	void shouldRemovePriorLeafPagesWhenCollapsingAcrossTwoWarmUpFlushesWithoutAnIntermediateReload() {
		// the sibling merge test above mutates a RELOADED index, whose page-stream live-set baseline the loader restored
		// from disk. A WARM_UP (bulk) catalog never reloads and never reaches a commit-merge — the only place the staged
		// page set is PUBLISHED — so its collapse must reclaim against the set the previous flush STAGED, not the
		// published one, which stays empty for the whole warm-up and would silently reclaim NOTHING.
		final AttributeKey attributeKey = new AttributeKey("url", Locale.ENGLISH);
		final GlobalUniqueIndex index = new GlobalUniqueIndex(Scope.LIVE, attributeKey, String.class);
		for (int i = 0; i < COLLAPSE_KEY_COUNT; i++) {
			index.registerUniqueKey(keyForIndex(i), ENTITY_TYPE, Locale.ENGLISH, i + 1, this.classifierResolver);
		}
		assertTrue(index.isPaged(), "the index must span multiple leaves to exercise the paged layout");

		// first WARM_UP flush: allocates + STAGES one leaf page per leaf and publishes nothing (publishing happens only at
		// the commit-merge, which a warm-up flush never reaches)
		final TrappedChanges firstChanges = new TrappedChanges();
		index.appendStorageParts(attributeKey, firstChanges);
		final List<StoragePart> firstEmission = collectAllParts(firstChanges);
		index.resetDirty();

		// the prior page count is taken from the EMISSION (which the flush derives by walking the tree's leaves), never
		// from the registry accessor under test — sourcing it there would make the removal assertion below `0 == 0` and
		// leave this test green with or without the fix
		final int priorLeafPageCount = (int) firstEmission.stream()
			.filter(GlobalUniqueIndexLeafPagePart.class::isInstance).count();
		assertTrue(priorLeafPageCount >= 3, "the source index must start paged across several leaf pages");
		final GlobalUniqueIndexStoragePart firstRoot = firstEmission.stream()
			.filter(GlobalUniqueIndexStoragePart.class::isInstance)
			.map(GlobalUniqueIndexStoragePart.class::cast)
			.findFirst()
			.orElseThrow();
		assertTrue(firstRoot.isPaged(), "the first warm-up flush must emit a PAGED root");
		assertEquals(
			priorLeafPageCount, firstRoot.getLeafPageSequences().length,
			"the PAGED root must list exactly the leaf pages the first warm-up flush wrote"
		);
		assertEquals(
			0, firstEmission.stream().filter(GlobalUniqueIndexLeafPageRemoval.class::isInstance).count(),
			"a first warm-up flush frees no leaf page — nothing was on disk before it"
		);

		// collapse the SAME in-memory index — NO reload in between, exactly what a warm-up catalog does: drop all but a
		// handful of keys so the survivors fit within a single leaf
		for (int i = COLLAPSE_KEEP; i < COLLAPSE_KEY_COUNT; i++) {
			index.unregisterUniqueKey(keyForIndex(i), ENTITY_TYPE, Locale.ENGLISH, i + 1, this.classifierResolver);
		}
		assertFalse(index.isPaged(), "an index that fits a single leaf must collapse out of the PAGED shape");

		final TrappedChanges secondChanges = new TrappedChanges();
		index.appendStorageParts(attributeKey, secondChanges);
		final List<StoragePart> secondEmission = collectAllParts(secondChanges);
		final GlobalUniqueIndexStoragePart collapsedRoot = secondEmission.stream()
			.filter(GlobalUniqueIndexStoragePart.class::isInstance)
			.map(GlobalUniqueIndexStoragePart.class::cast)
			.findFirst()
			.orElseThrow();
		assertFalse(collapsedRoot.isPaged(), "the collapsed index must emit a single inline (SINGLE) root");
		assertNotNull(collapsedRoot.getValues(), "a SINGLE root carries the value column inline");
		assertEquals(
			COLLAPSE_KEEP, collapsedRoot.getValues().length, "the SINGLE root must carry every surviving value inline"
		);
		assertEquals(
			0, secondEmission.stream().filter(GlobalUniqueIndexLeafPagePart.class::isInstance).count(),
			"a collapsed index must not re-emit any leaf page"
		);
		assertEquals(
			priorLeafPageCount,
			(int) secondEmission.stream().filter(GlobalUniqueIndexLeafPageRemoval.class::isInstance).count(),
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
	 * fresh index emits no {@link DeferredRemovalStoragePart} removals.
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
	 * {@link DeferredRemovalStoragePart} (the {@link GlobalUniqueIndexLeafPageRemoval} a leaf merge produces on a
	 * subsequent flush). The counterpart of {@link #collectParts(TrappedChanges)}, which drops removals.
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
	 * {@link GlobalUniqueIndex} via {@link GlobalUniqueIndex#fromPersistedPages} — the same path
	 * {@code AttributeIndexLoader} takes on a cold load. Restoring from the persisted leaf-page list also seeds the
	 * page-stream live-set baseline, so a later flush can detect (and remove) the pages a merge frees.
	 */
	@Nonnull
	private static GlobalUniqueIndex loadPagedIndex(
		@Nonnull OffsetIndex offsetIndex,
		long catalogVersion,
		@Nonnull AttributeKey attributeKey,
		int streamId,
		@Nonnull GlobalUniqueIndexStoragePart root
	) {
		final int[] orderedPageSequences = root.getLeafPageSequences();
		final Serializable[][] perPageValues = new Serializable[orderedPageSequences.length][];
		final long[][] perPagePayloads = new long[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final GlobalUniqueIndexLeafPagePart leafPage = offsetIndex.get(
				catalogVersion,
				GlobalUniqueIndexLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
				GlobalUniqueIndexLeafPagePart.class
			);
			assertNotNull(leafPage, "leaf page " + orderedPageSequences[i] + " must be readable");
			perPageValues[i] = leafPage.getValues();
			perPagePayloads[i] = leafPage.getPayloads();
		}
		return GlobalUniqueIndex.fromPersistedPages(
			Scope.LIVE, attributeKey, String.class,
			orderedPageSequences, perPageValues, perPagePayloads,
			root.getHighWaterPageSequence(), root.getLocaleIndex()
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
