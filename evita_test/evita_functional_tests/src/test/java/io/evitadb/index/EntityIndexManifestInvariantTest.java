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

package io.evitadb.index;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the manifest invariant: for every concrete `EntityIndex` subclass, the set of
 * `AttributeIndexStorageKey` entries written into the `EntityIndexStoragePart` manifest by
 * `getModifiedStorageParts(...)` must exactly mirror the live sub-index state — including
 * subclass-only collections such as `cardinalityIndexes`. Any divergence orphans sub-index
 * data on reload (the manifest gates which storage parts are rehydrated).
 *
 * The test populates each subclass with a representative non-empty payload spanning every
 * sub-index type it can carry, extracts the actual manifest by intercepting the
 * `TrappedChanges` passed to `getModifiedStorageParts`, derives the expected manifest from
 * the same public getters used by the subclass override, and asserts set equality. It also
 * verifies the degenerate case: a freshly created (empty) index emits no
 * `EntityIndexStoragePart` at all, ensuring an empty instance does not slip stale or
 * spurious keys into the manifest.
 *
 * Future sub-index types added to any subclass must also be added to the expected-manifest
 * derivation here; otherwise this test will fail and surface the omission before it becomes
 * another orphan-on-disk regression.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EntityIndex storage-part manifest invariant")
@Tag(INDEXING)
@Tag(MANAGEMENT)
class EntityIndexManifestInvariantTest {

	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCE_NAME = "CATEGORY";
	private static final int INDEX_PK = 1;
	private static final int GROUP_PK = 100;
	private static final int REFERENCED_PK = 1;

	/**
	 * Builds a non-localized, non-unique `AttributeSchemaContract` of the given type. Mirrors
	 * the helper used in `ReducedGroupEntityIndexTest`: filterable in `Scope.LIVE`, no other
	 * flags set. Reference attributes (not `EntityAttributeSchemaContract`) bypass the
	 * partitioning assertion in reduced indexes, keeping fixture wiring minimal.
	 *
	 * @param name the attribute name
	 * @param type the attribute value type
	 * @return a plain attribute schema usable on reduced and global indexes alike
	 */
	@Nonnull
	private static AttributeSchemaContract createReferenceAttributeSchema(
		@Nonnull String name, @Nonnull Class<? extends Serializable> type
	) {
		return AttributeSchema._internalBuild(
			name,
			null,
			new Scope[]{Scope.LIVE},
			new Scope[]{Scope.LIVE},
			false, false, false,
			type, null,
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Extracts the `EntityIndexStoragePart` change from a freshly trapped collection of
	 * modifications, or returns empty when none was emitted (the index was clean and
	 * had nothing to persist). Test code relies on this both to assert manifest contents
	 * and to confirm the degenerate empty-index case emits no manifest at all.
	 *
	 * @param trappedChanges the trapped changes returned by `getModifiedStorageParts`
	 * @return the single `EntityIndexStoragePart` change, or empty when absent
	 */
	@Nonnull
	private static Optional<EntityIndexStoragePart> findEntityIndexStoragePart(
		@Nonnull TrappedChanges trappedChanges
	) {
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		EntityIndexStoragePart found = null;
		while (iterator.hasNext()) {
			final StoragePart part = iterator.next();
			if (part instanceof EntityIndexStoragePart manifest) {
				// a duplicate manifest would itself be a bug — pin that down explicitly
				assertNull(found, "More than one EntityIndexStoragePart emitted by getModifiedStorageParts");
				found = manifest;
			}
		}
		return Optional.ofNullable(found);
	}

	/**
	 * Synthesizes the expected attribute-index storage keys for the four sub-index types
	 * exposed by `AttributeIndex`. This mirrors the loop in
	 * `AttributeIndex.collectKeys()` (invoked through `AttributeIndexComponent` during
	 * manifest collection) and is reused by every subclass-specific derivation below.
	 *
	 * @param indexKey the entity index key used to compose the storage keys
	 * @param uniqueKeys set of unique attribute keys held by the instance
	 * @param filterKeys set of filter attribute keys held by the instance
	 * @param sortKeys set of sort attribute keys held by the instance
	 * @param chainKeys set of chain attribute keys held by the instance
	 * @return the set of expected attribute-index storage keys for the four base sub-indexes
	 */
	@Nonnull
	private static Set<AttributeIndexStorageKey> baseExpectedKeys(
		@Nonnull EntityIndexKey indexKey,
		@Nonnull Set<AttributeIndexKey> uniqueKeys,
		@Nonnull Set<AttributeIndexKey> filterKeys,
		@Nonnull Set<AttributeIndexKey> sortKeys,
		@Nonnull Set<AttributeIndexKey> chainKeys
	) {
		final Set<AttributeIndexStorageKey> expected = new HashSet<>(
			uniqueKeys.size() + filterKeys.size() + sortKeys.size() + chainKeys.size()
		);
		for (final AttributeIndexKey key : uniqueKeys) {
			expected.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.UNIQUE, key));
		}
		for (final AttributeIndexKey key : filterKeys) {
			expected.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.FILTER, key));
		}
		for (final AttributeIndexKey key : sortKeys) {
			expected.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.SORT, key));
		}
		for (final AttributeIndexKey key : chainKeys) {
			expected.add(new AttributeIndexStorageKey(indexKey, AttributeIndexType.CHAIN, key));
		}
		return expected;
	}

	/**
	 * Pulls the manifest from the given populated index and asserts:
	 *
	 * 1. The `EntityIndexStoragePart` was emitted (the index is dirty / has live data).
	 * 2. The manifest's `attributeIndexes` set equals the supplied expected set —
	 *    exact equality, no missing keys (the bug guarded against) and no stale keys.
	 *
	 * @param index the populated `EntityIndex` instance under test
	 * @param expectedAttributeKeys the expected set derived from the live sub-indexes
	 */
	private static void assertManifestMatches(
		@Nonnull EntityIndex index,
		@Nonnull Set<AttributeIndexStorageKey> expectedAttributeKeys
	) {
		final TrappedChanges trapped = new TrappedChanges();
		index.getModifiedStorageParts(trapped);

		final EntityIndexStoragePart manifest = findEntityIndexStoragePart(trapped)
			.orElseThrow(() -> new AssertionError(
				"Expected EntityIndexStoragePart in the trapped manifest for populated index "
					+ index.getClass().getSimpleName() + " — got none"
			));

		assertEquals(
			expectedAttributeKeys,
			manifest.getAttributeIndexes(),
			"Manifest attribute-index set diverges from live sub-index walk for "
				+ index.getClass().getSimpleName()
				+ ". Missing keys orphan sub-index data on reload; stale keys advertise"
				+ " sub-indexes that no longer exist."
		);
	}

	/**
	 * Reads the private `cardinalityIndexes` field from a `ReducedGroupEntityIndex` or
	 * `ReferencedTypeEntityIndex` via reflection. Both subclasses store cardinality
	 * data in a private `TransactionalMap<AttributeIndexKey, AttributeCardinalityIndex>`
	 * named `cardinalityIndexes`, and there is no public accessor for the key set.
	 * Reflection is intentional: this test must walk the **actual** private state so a
	 * future change that adds a new sub-index collection without exposing it cannot
	 * sneak past by passing a hand-curated expected set.
	 *
	 * @param subclassInstance the RGEI or RTEI instance whose cardinality keys are needed
	 * @return the set of `AttributeIndexKey` entries currently held in `cardinalityIndexes`
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static Set<AttributeIndexKey> readCardinalityIndexKeys(@Nonnull Object subclassInstance) {
		try {
			final Field field = subclassInstance.getClass().getDeclaredField("cardinalityIndexes");
			field.setAccessible(true);
			final Map<AttributeIndexKey, ?> map = (Map<AttributeIndexKey, ?>) field.get(subclassInstance);
			return Set.copyOf(map.keySet());
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new AssertionError(
				"Failed to read 'cardinalityIndexes' on " + subclassInstance.getClass().getSimpleName()
					+ " — has the field been renamed or removed? Update this test to reflect the new shape.",
				e
			);
		}
	}

	/**
	 * Verifies that a freshly created index emits no `EntityIndexStoragePart` at all.
	 * An empty manifest part containing stale keys would be just as broken as a missing
	 * key on a populated index — both indicate the originalAttributeIndexes / live-set
	 * comparison drifted. Asserting absence catches that drift without needing to force
	 * a synthetic dirty bit.
	 *
	 * @param index the freshly-created empty index instance
	 */
	private static void assertNoManifestEmittedForEmpty(@Nonnull EntityIndex index) {
		final TrappedChanges trapped = new TrappedChanges();
		index.getModifiedStorageParts(trapped);

		assertTrue(
			findEntityIndexStoragePart(trapped).isEmpty(),
			"Empty index " + index.getClass().getSimpleName()
				+ " unexpectedly emitted an EntityIndexStoragePart — manifest must be silent"
				+ " when there is no live sub-index data to advertise"
		);
	}

	/**
	 * Manifest invariant checks for `GlobalEntityIndex`. GEI carries the four base
	 * attribute sub-index types (UNIQUE / FILTER / SORT / CHAIN) — no cardinality and no
	 * histogram support. It is the simplest manifest shape and serves as the baseline
	 * that every more-specialized subclass must extend without dropping keys.
	 */
	@Nested
	@DisplayName("GlobalEntityIndex")
	class GlobalEntityIndexManifestTest {

		private GlobalEntityIndex index;

		@BeforeEach
		void setUp() {
			this.index = new GlobalEntityIndex(
				INDEX_PK,
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
			);
		}

		@Test
		@DisplayName("should not emit any manifest when index is empty")
		void shouldNotEmitManifestWhenEmpty() {
			assertNoManifestEmittedForEmpty(this.index);
		}

		@Test
		@DisplayName("should list every UNIQUE/FILTER/SORT/CHAIN sub-index in manifest")
		void shouldListAllAttributeSubIndexesInManifest() {
			final Set<Locale> noLocales = Collections.emptySet();
			final AttributeSchemaContract codeSchema = createReferenceAttributeSchema(
				"code", String.class
			);
			final AttributeSchemaContract prioritySchema = createReferenceAttributeSchema(
				"priority", Integer.class
			);
			final AttributeSchemaContract orderSchema = createReferenceAttributeSchema(
				"order", Predecessor.class
			);

			// PK insertion makes the index non-empty and ensures the manifest emits
			this.index.insertPrimaryKeyIfMissing(10);

			// one entry per attribute sub-index type — the folded-unique "code" contributes both its UNIQUE key and,
			// via the FILTER shadow the mutator always writes for a folded-unique attribute, its FILTER key
			this.index.insertUniqueAttribute(
				null, codeSchema, noLocales, Scope.LIVE, null, "UNIQUE-VAL", 10
			);
			// shadow the folded-unique "code" into the FILTER index so its shared tree exists (a unique view with no
			// shared tree is an impossible state in real operation and would not be advertised in the manifest)
			this.index.insertFilterAttribute(
				null, codeSchema, noLocales, null, "UNIQUE-VAL", 10, true
			);
			this.index.insertSortAttribute(
				null, prioritySchema, noLocales, null, 42, 10
			);
			this.index.insertSortAttribute(
				null, orderSchema, noLocales, null, Predecessor.HEAD, 10
			);

			// derive the expected manifest by walking the live sub-indexes — the same
			// public getters AttributeIndex.collectKeys() uses internally
			final Set<AttributeIndexStorageKey> expected = baseExpectedKeys(
				this.index.getIndexKey(),
				this.index.getUniqueIndexes(),
				this.index.getFilterIndexes(),
				this.index.getSortIndexes(),
				this.index.getChainIndexes()
			);

			// sanity guard — we must actually have populated all four sub-indexes; an
			// empty expected set would render the equality check vacuous
			assertEquals(
				4, expected.size(),
				"Test fixture must populate all four base attribute sub-indexes — got " + expected
			);

			assertManifestMatches(this.index, expected);
		}

		@Test
		@DisplayName("should emit an empty attribute manifest when only PKs are present")
		void shouldEmitEmptyAttributeManifestWhenOnlyPksPresent() {
			// the manifest is forced to emit by the PK insertion (dirty flag is set), yet
			// no sub-indexes were populated — the attribute-keys field must therefore be
			// empty, not slip in any spurious keys
			this.index.insertPrimaryKeyIfMissing(10);

			assertManifestMatches(this.index, Collections.emptySet());
		}

		@Test
		@DisplayName("a large PAGED chain is listed as a SINGLE CHAIN manifest key (its leaf pages never leak into the manifest)")
		void shouldListPagedChainAsOneChainKeyAndNotLeakLeafPagesIntoManifest() {
			final Set<Locale> noLocales = Collections.emptySet();
			final AttributeSchemaContract orderSchema = createReferenceAttributeSchema("order", Predecessor.class);

			// build one consistent chain 1 -> 2 -> ... -> 3200 so the chain pages out (leaf capacity 1024)
			for (int pk = 1; pk <= 3200; pk++) {
				this.index.insertPrimaryKeyIfMissing(pk);
				this.index.insertSortAttribute(
					null, orderSchema, noLocales, null,
					pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk
				);
			}

			// flush once and inspect the emitted parts: the chain must actually be PAGED (leaf pages + a paged root),
			// yet the manifest must still advertise the chain as exactly ONE CHAIN sub-index key
			final TrappedChanges trapped = new TrappedChanges();
			this.index.getModifiedStorageParts(trapped);
			int leafPageCount = 0;
			boolean pagedRoot = false;
			EntityIndexStoragePart manifest = null;
			final Iterator<StoragePart> iterator = trapped.getTrappedChangesIterator();
			while (iterator.hasNext()) {
				final StoragePart part = iterator.next();
				if (part instanceof ChainIndexLeafPagePart) {
					leafPageCount++;
				} else if (part instanceof ChainIndexStoragePart root) {
					pagedRoot = root.isPaged();
				} else if (part instanceof EntityIndexStoragePart entityIndexManifest) {
					manifest = entityIndexManifest;
				}
			}
			assertTrue(leafPageCount >= 3, "the chain must page out into at least three leaf pages, got " + leafPageCount);
			assertTrue(pagedRoot, "the chain root part must be PAGED");

			assertNotNull(manifest, "a populated index must emit an EntityIndexStoragePart manifest");
			// the manifest advertises sub-indexes, never leaf pages: exactly one CHAIN key for the whole paged chain
			final Set<AttributeIndexStorageKey> chainKeys = new HashSet<>();
			for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
				if (key.indexType() == AttributeIndexType.CHAIN) {
					chainKeys.add(key);
				}
			}
			assertEquals(
				Set.of(new AttributeIndexStorageKey(this.index.getIndexKey(), AttributeIndexType.CHAIN,
					this.index.getChainIndexes().iterator().next())),
				chainKeys,
				"a PAGED chain must be advertised by exactly one CHAIN manifest key; its leaf pages must not leak in"
			);
		}
	}

	/**
	 * Manifest invariant checks for `ReducedEntityIndex`. REI inherits the base
	 * `EntityIndex.createStoragePart` implementation (no override), so the manifest
	 * shape is identical to `GlobalEntityIndex`: UNIQUE / FILTER / SORT / CHAIN with no
	 * cardinality. The test exists to pin that REI never silently grows a sub-index
	 * collection without updating the manifest override.
	 */
	@Nested
	@DisplayName("ReducedEntityIndex")
	class ReducedEntityIndexManifestTest {

		private ReducedEntityIndex index;
		private ReferenceSchemaContract referenceSchema;

		@BeforeEach
		void setUp() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, REFERENCED_PK)
			);
			this.index = new ReducedEntityIndex(
				INDEX_PK,
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk)
			);
			// reference attribute insertions on the reduced index require a reference
			// schema (the partitioning assertion checks it is non-null) — but since we
			// pass plain AttributeSchema (not EntityAttributeSchemaContract) the strict
			// FOR_FILTERING_AND_PARTITIONING check is bypassed, keeping the mock minimal
			this.referenceSchema = mock(ReferenceSchemaContract.class);
		}

		@Test
		@DisplayName("should not emit any manifest when index is empty")
		void shouldNotEmitManifestWhenEmpty() {
			assertNoManifestEmittedForEmpty(this.index);
		}

		@Test
		@DisplayName("should list every UNIQUE/FILTER/SORT/CHAIN sub-index in manifest")
		void shouldListAllAttributeSubIndexesInManifest() {
			final Set<Locale> noLocales = Collections.emptySet();
			final AttributeSchemaContract codeSchema = createReferenceAttributeSchema(
				"code", String.class
			);
			final AttributeSchemaContract prioritySchema = createReferenceAttributeSchema(
				"priority", Integer.class
			);
			final AttributeSchemaContract orderSchema = createReferenceAttributeSchema(
				"order", Predecessor.class
			);

			this.index.insertPrimaryKeyIfMissing(10);

			this.index.insertUniqueAttribute(
				this.referenceSchema, codeSchema, noLocales, Scope.LIVE, null, "UNIQUE-VAL", 10
			);
			// shadow the folded-unique "code" into the FILTER index so its shared tree exists (a unique view with no
			// shared tree is an impossible state in real operation and would not be advertised in the manifest)
			this.index.insertFilterAttribute(
				this.referenceSchema, codeSchema, noLocales, null, "UNIQUE-VAL", 10, true
			);
			this.index.insertSortAttribute(
				this.referenceSchema, prioritySchema, noLocales, null, 42, 10
			);
			this.index.insertSortAttribute(
				this.referenceSchema, orderSchema, noLocales, null, Predecessor.HEAD, 10
			);

			final Set<AttributeIndexStorageKey> expected = baseExpectedKeys(
				this.index.getIndexKey(),
				this.index.getUniqueIndexes(),
				this.index.getFilterIndexes(),
				this.index.getSortIndexes(),
				this.index.getChainIndexes()
			);

			assertEquals(
				4, expected.size(),
				"Test fixture must populate all four base attribute sub-indexes — got " + expected
			);

			assertManifestMatches(this.index, expected);
		}

		@Test
		@DisplayName("should emit an empty attribute manifest when only PKs are present")
		void shouldEmitEmptyAttributeManifestWhenOnlyPksPresent() {
			this.index.insertPrimaryKeyIfMissing(10);

			assertManifestMatches(this.index, Collections.emptySet());
		}
	}

	/**
	 * Manifest invariant checks for `ReducedGroupEntityIndex`. RGEI overrides
	 * `createStoragePart` to add `CARDINALITY` storage keys synthesized from its
	 * subclass-only `cardinalityIndexes` map. RGEI also exposes FILTER sub-indexes — its
	 * `insertFilterAttribute` routes through the cardinality index but, on a 0→1 transition,
	 * also delegates to `super.insertFilterAttribute`, populating the parent FILTER index.
	 * Both keys (CARDINALITY + FILTER) must therefore appear in the manifest after a single
	 * insert. RGEI does **not** maintain UNIQUE / SORT / CHAIN sub-indexes (they are no-ops),
	 * so the manifest shape is strictly {CARDINALITY, FILTER}.
	 */
	@Nested
	@DisplayName("ReducedGroupEntityIndex")
	class ReducedGroupEntityIndexManifestTest {

		private ReducedGroupEntityIndex index;
		private ReferenceSchemaContract referenceSchema;

		@BeforeEach
		void setUp() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, GROUP_PK)
			);
			this.index = new ReducedGroupEntityIndex(
				INDEX_PK,
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, rrk)
			);
			this.referenceSchema = mock(ReferenceSchemaContract.class);
		}

		@Test
		@DisplayName("should not emit any manifest when index is empty")
		void shouldNotEmitManifestWhenEmpty() {
			assertNoManifestEmittedForEmpty(this.index);
		}

		@Test
		@DisplayName("should list CARDINALITY and FILTER keys in manifest after insertFilterAttribute")
		void shouldListCardinalityAndFilterKeysInManifest() {
			final Set<Locale> noLocales = Collections.emptySet();
			final AttributeSchemaContract codeSchema = createReferenceAttributeSchema(
				"code", String.class
			);
			final AttributeSchemaContract tagsSchema = createReferenceAttributeSchema(
				"tags", String.class
			);

			// PK insertion via the two-arg cardinality-aware method (single-arg is unsupported)
			this.index.insertPrimaryKeyIfMissing(10, REFERENCED_PK);

			// two distinct filter attributes — each gets its own cardinality index entry AND
			// (on the 0→1 transition) its own FILTER index entry on the base AttributeIndex
			this.index.insertFilterAttribute(
				this.referenceSchema, codeSchema, noLocales, null, "ABC", 10, false
			);
			this.index.insertFilterAttribute(
				this.referenceSchema, tagsSchema, noLocales, null, "T1", 10, false
			);

			// derive the expected manifest by walking every public sub-index getter that the
			// subclass exposes — base UNIQUE/FILTER/SORT/CHAIN plus subclass-owned CARDINALITY
			final EntityIndexKey indexKey = this.index.getIndexKey();
			final Set<AttributeIndexStorageKey> expected = baseExpectedKeys(
				indexKey,
				this.index.getUniqueIndexes(),
				this.index.getFilterIndexes(),
				this.index.getSortIndexes(),
				this.index.getChainIndexes()
			);
			// synthesize CARDINALITY keys from the subclass-only map exactly as the override does;
			// reflection is used because the map has no public accessor — see helper docstring
			for (final AttributeIndexKey key : readCardinalityIndexKeys(this.index)) {
				expected.add(new AttributeIndexStorageKey(
					indexKey, AttributeIndexType.CARDINALITY, key
				));
			}

			// sanity guard — we must have populated two FILTER entries and two CARDINALITY
			// entries for a total of four storage keys
			assertEquals(
				4, expected.size(),
				"Test fixture must populate two filter + two cardinality sub-indexes — got " + expected
			);

			assertManifestMatches(this.index, expected);
		}

		@Test
		@DisplayName("should emit an empty attribute manifest when only PKs are present")
		void shouldEmitEmptyAttributeManifestWhenOnlyPksPresent() {
			// PK-only path: forces the manifest to emit (cardinality dirty flag set) but
			// populates no attribute sub-indexes; the attribute-keys field must be empty
			this.index.insertPrimaryKeyIfMissing(10, REFERENCED_PK);

			assertManifestMatches(this.index, Collections.emptySet());
		}
	}

	/**
	 * Manifest invariant checks for `ReferencedTypeEntityIndex`. RTEI overrides
	 * `createStoragePart` identically to `ReducedGroupEntityIndex` — folding
	 * `CARDINALITY` keys from its subclass-only `cardinalityIndexes` map into the
	 * base manifest. Like RGEI, RTEI does not maintain UNIQUE / SORT / CHAIN sub-indexes
	 * (they are no-ops), and `insertFilterAttribute` populates both `cardinalityIndexes`
	 * and the parent FILTER index on a 0→1 cardinality transition.
	 */
	@Nested
	@DisplayName("ReferencedTypeEntityIndex")
	class ReferencedTypeEntityIndexManifestTest {

		private ReferencedTypeEntityIndex index;
		private ReferenceSchemaContract referenceSchema;

		@BeforeEach
		void setUp() {
			this.index = new ReferencedTypeEntityIndex(
				INDEX_PK,
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
			);
			this.referenceSchema = mock(ReferenceSchemaContract.class);
		}

		@Test
		@DisplayName("should not emit any manifest when index is empty")
		void shouldNotEmitManifestWhenEmpty() {
			assertNoManifestEmittedForEmpty(this.index);
		}

		@Test
		@DisplayName("should list CARDINALITY and FILTER keys in manifest after insertFilterAttribute")
		void shouldListCardinalityAndFilterKeysInManifest() {
			final Set<Locale> noLocales = Collections.emptySet();
			final AttributeSchemaContract codeSchema = createReferenceAttributeSchema(
				"code", String.class
			);
			final AttributeSchemaContract tagsSchema = createReferenceAttributeSchema(
				"tags", String.class
			);

			// PK insertion via the two-arg form (single-arg is unsupported on RTEI)
			this.index.insertPrimaryKeyIfMissing(10, REFERENCED_PK);

			// two filter attributes — each populates one cardinality entry and one FILTER entry
			this.index.insertFilterAttribute(
				this.referenceSchema, codeSchema, noLocales, null, "ABC", 10, false
			);
			this.index.insertFilterAttribute(
				this.referenceSchema, tagsSchema, noLocales, null, "T1", 10, false
			);

			final EntityIndexKey indexKey = this.index.getIndexKey();
			final Set<AttributeIndexStorageKey> expected = baseExpectedKeys(
				indexKey,
				this.index.getUniqueIndexes(),
				this.index.getFilterIndexes(),
				this.index.getSortIndexes(),
				this.index.getChainIndexes()
			);
			for (final AttributeIndexKey key : readCardinalityIndexKeys(this.index)) {
				expected.add(new AttributeIndexStorageKey(
					indexKey, AttributeIndexType.CARDINALITY, key
				));
			}

			assertEquals(
				4, expected.size(),
				"Test fixture must populate two filter + two cardinality sub-indexes — got " + expected
			);

			assertManifestMatches(this.index, expected);
		}

		@Test
		@DisplayName("should emit an empty attribute manifest when only PKs are present")
		void shouldEmitEmptyAttributeManifestWhenOnlyPksPresent() {
			// the two-arg PK insertion delegates to super.insertPrimaryKeyIfMissing(int)
			// which sets the base dirty flag — the manifest is therefore emitted, and its
			// attribute-keys field must be empty since no sub-indexes were populated
			this.index.insertPrimaryKeyIfMissing(10, REFERENCED_PK);

			assertManifestMatches(this.index, Collections.emptySet());
		}
	}

}
