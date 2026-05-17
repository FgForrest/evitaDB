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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the observable post-state of a shared `ReducedGroupEntityIndex` (RGEI) when multiple
 * references on a single entity resolve to the same group with identical representative
 * attribute values, so all references share one RGEI instance. The invariants this matrix
 * pins are:
 *
 * 1. `createStoragePart` includes CARDINALITY entries in the manifest, so cardinality data
 *    is not orphaned on reload.
 * 2. `insertPrimaryKeyIfMissing(int, int)` / `removePrimaryKey(int, int)` return
 *    `BOUNDARY_CROSSED` only on the 0↔1 transition for the entity PK — that flag is the only
 *    reliable "entity entered/left this RGEI" signal.
 * 3. Entity-level work fans out exactly once per shared RGEI (gated by the 0↔1 flag),
 *    reference-level work always fires per-ref.
 * 4. `EntityIndexLocalMutationExecutor.applyAttributeMutation` and `applyPriceMutation` dedup
 *    by index identity so N sibling fanouts collapse to one effective mutation.
 *
 * Each "sibling reference contributing X" is encoded as a call with a distinct
 * `referencedEntityPrimaryKey` argument, faithfully simulating what `ReferenceIndexMutator`
 * does when fanning out to a shared RGEI.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Shared ReducedGroupEntityIndex mutation matrix")
@Tag(INDEXING)
@Tag(REFERENCE)
class SharedRgeiMutationMatrixTest {

	/** The owning entity PK used across all scenarios. */
	private static final int ENTITY_PK = 100;
	/** Reference name carried by all three sibling references. */
	private static final String REFERENCE_NAME = "category";
	/** Group PK that all three sibling references resolve to. */
	private static final int GROUP_PK = 500;
	/** Referenced entity PKs for the three sibling references R1, R2, R3. */
	private static final int R1_REFERENCED_PK = 11;
	private static final int R2_REFERENCED_PK = 22;
	private static final int R3_REFERENCED_PK = 33;
	/** Entity type used for the catalog attachment mock. */
	private static final String ENTITY_TYPE = "product";
	/** Index PK assigned to the RGEI under test. */
	private static final int INDEX_PK = 1;
	/** Price list and currency used by all price scenarios. */
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final PriceIndexKey BASIC_CZK_KEY = new PriceIndexKey(
		PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE
	);

	/** The RGEI under test — shared by R1, R2, R3 (all references point at the same group). */
	private ReducedGroupEntityIndex sharedRgei;
	/** Per-test super index, used to back ref-index price lookups. */
	private PriceSuperIndex priceSuperIndex;
	/** Sequence for assigning unique internal price ids to entity-level price additions. */
	private int nextInternalPriceId;
	/**
	 * Mocked reference schema passed into `addPrice` / `priceRemove` on the shared RGEI. The
	 * `AbstractReducedEntityIndex.assertPartitioningIndex` precondition requires a non-null schema
	 * configured with `FOR_FILTERING_AND_PARTITIONING` index type — without this the very first
	 * price mutation would explode before the test can observe anything.
	 */
	@Nonnull private final ReferenceSchemaContract sharedRefSchema =
		mock(ReferenceSchemaContract.class);

	@BeforeEach
	void setUp() {
		when(this.sharedRefSchema.getReferenceIndexType(ArgumentMatchers.any(Scope.class)))
			.thenReturn(ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);
		this.priceSuperIndex = new PriceSuperIndex();
		this.nextInternalPriceId = 1;
		this.sharedRgei = createSharedRgei();
		attachToMockCatalog(this.sharedRgei, this.priceSuperIndex);
	}

	@Nested
	@DisplayName("1 — Add references progressively")
	class AddReferenceProgressivelyTest {

		@Test
		@DisplayName("Should mark entity present in shared RGEI after first reference is added")
		void shouldMarkEntityPresentAfterFirstReferenceAdded() {
			// given a fresh RGEI with no entries
			assertTrue(SharedRgeiMutationMatrixTest.this.sharedRgei.isEmpty());

			// when R1 is added
			final CardinalityChange firstAdd = SharedRgeiMutationMatrixTest.this.sharedRgei
				.insertPrimaryKeyIfMissing(ENTITY_PK, R1_REFERENCED_PK);

			// then the 0->1 boundary fires and entity becomes visible
			assertEquals(
				CardinalityChange.BOUNDARY_CROSSED, firstAdd,
				"First add must signal 0->1 transition"
			);
			final Bitmap visible = SharedRgeiMutationMatrixTest.this.sharedRgei.getAllPrimaryKeys();
			assertEquals(1, visible.size());
			assertTrue(visible.contains(ENTITY_PK));
		}

		@Test
		@DisplayName("Should keep entity present exactly once after R2 and R3 are added")
		void shouldKeepEntityPresentExactlyOnceAfterAllSiblingsAdded() {
			// given R1 already added (entity-level work done once)
			SharedRgeiMutationMatrixTest.this.sharedRgei
				.insertPrimaryKeyIfMissing(ENTITY_PK, R1_REFERENCED_PK);

			// when sibling references R2 and R3 are added
			final CardinalityChange secondAdd = SharedRgeiMutationMatrixTest.this.sharedRgei
				.insertPrimaryKeyIfMissing(ENTITY_PK, R2_REFERENCED_PK);
			final CardinalityChange thirdAdd = SharedRgeiMutationMatrixTest.this.sharedRgei
				.insertPrimaryKeyIfMissing(ENTITY_PK, R3_REFERENCED_PK);

			// then sibling adds must signal NO_BOUNDARY_CROSSING so callers skip duplicate entity-level work
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, secondAdd,
				"Sibling add must signal NO transition (entity already present)");
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, thirdAdd,
				"Sibling add must signal NO transition (entity already present)");

			// and the entity appears exactly once in the visible bitmap
			final Bitmap visible = SharedRgeiMutationMatrixTest.this.sharedRgei.getAllPrimaryKeys();
			assertEquals(1, visible.size());
			assertTrue(visible.contains(ENTITY_PK));

			// referencingPksIndex tracks each sibling reference separately — each must hold the entity
			final Bitmap r1Owners = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getOwnerPKsForReferencedEntity(R1_REFERENCED_PK);
			final Bitmap r2Owners = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getOwnerPKsForReferencedEntity(R2_REFERENCED_PK);
			final Bitmap r3Owners = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getOwnerPKsForReferencedEntity(R3_REFERENCED_PK);
			assertNotNull(r1Owners);
			assertNotNull(r2Owners);
			assertNotNull(r3Owners);
			assertTrue(r1Owners.contains(ENTITY_PK));
			assertTrue(r2Owners.contains(ENTITY_PK));
			assertTrue(r3Owners.contains(ENTITY_PK));
		}
	}

	@Nested
	@DisplayName("2 — Partial reference removal")
	class PartialRemovalTest {

		@Test
		@DisplayName("Should keep entity in shared RGEI when only one of three siblings is removed")
		void shouldKeepEntityWhenOneOfThreeSiblingsRemoved() {
			// given all three siblings registered
			SharedRgeiMutationMatrixTest.this.seedAllSiblings();

			// when R1 is removed
			final CardinalityChange firstRemove = SharedRgeiMutationMatrixTest.this.sharedRgei
				.removePrimaryKey(ENTITY_PK, R1_REFERENCED_PK);

			// then NO transition fires (R2, R3 keep it alive); entity stays visible
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, firstRemove,
				"Partial remove must signal NO transition (siblings still hold it)");
			final Bitmap visible = SharedRgeiMutationMatrixTest.this.sharedRgei.getAllPrimaryKeys();
			assertEquals(1, visible.size());
			assertTrue(visible.contains(ENTITY_PK));

			// R1's per-reference tracking is fully evicted — the bitmap was empty and got removed
			assertNull(
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.getOwnerPKsForReferencedEntity(R1_REFERENCED_PK),
				"R1's owner-PK bitmap must be evicted when its only contributor leaves"
			);
			// R2 and R3 still hold the entity
			final Bitmap r2Owners = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getOwnerPKsForReferencedEntity(R2_REFERENCED_PK);
			final Bitmap r3Owners = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getOwnerPKsForReferencedEntity(R3_REFERENCED_PK);
			assertNotNull(r2Owners);
			assertNotNull(r3Owners);
			assertTrue(r2Owners.contains(ENTITY_PK));
			assertTrue(r3Owners.contains(ENTITY_PK));
		}
	}

	@Nested
	@DisplayName("3 — Full reference removal")
	class FullRemovalTest {

		@Test
		@DisplayName("Should remove entity from shared RGEI only after last sibling is removed")
		void shouldRemoveEntityOnlyAfterLastSiblingRemoved() {
			// given all three siblings registered
			SharedRgeiMutationMatrixTest.this.seedAllSiblings();

			// when references are removed one by one
			final CardinalityChange removeR1 = SharedRgeiMutationMatrixTest.this.sharedRgei
				.removePrimaryKey(ENTITY_PK, R1_REFERENCED_PK);
			final CardinalityChange removeR2 = SharedRgeiMutationMatrixTest.this.sharedRgei
				.removePrimaryKey(ENTITY_PK, R2_REFERENCED_PK);
			final CardinalityChange removeR3 = SharedRgeiMutationMatrixTest.this.sharedRgei
				.removePrimaryKey(ENTITY_PK, R3_REFERENCED_PK);

			// then only the LAST removal fires the 1->0 transition
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, removeR1,
				"Intermediate remove must not signal transition");
			assertEquals(CardinalityChange.NO_BOUNDARY_CROSSING, removeR2,
				"Intermediate remove must not signal transition");
			assertEquals(CardinalityChange.BOUNDARY_CROSSED, removeR3,
				"Last remove must signal 1->0 transition");

			// and the entity is gone from the visible bitmap
			assertTrue(
				SharedRgeiMutationMatrixTest.this.sharedRgei.getAllPrimaryKeys().isEmpty(),
				"Primary key bitmap must be empty after the last sibling is removed"
			);
			// and the per-reference tracking is fully drained
			assertTrue(
				SharedRgeiMutationMatrixTest.this.sharedRgei.getReferencedEntityPrimaryKeys().isEmpty(),
				"referencingPksIndex must be empty after the last sibling is removed"
			);
		}
	}

	@Nested
	@DisplayName("4 — Entity-level attribute change")
	@Tag(ATTRIBUTE)
	class EntityLevelAttributeChangeTest {

		@Test
		@DisplayName("Should apply exactly one decrement and one increment when entity attribute changes")
		void shouldApplyExactlyOneDecrementAndOneIncrementWhenEntityAttributeChanges() {
			// given three siblings registered + entity-level attribute indexed ONCE
			SharedRgeiMutationMatrixTest.this.seedAllSiblings();

			final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract entityAttrSchema = createFilterableAttribute(
				"status", String.class
			);
			final Set<Locale> noLocales = Collections.emptySet();
			SharedRgeiMutationMatrixTest.this.sharedRgei.insertFilterAttribute(
				referenceSchema, entityAttrSchema, noLocales, null, "ACTIVE", ENTITY_PK
			);

			// when the entity attribute value transitions ACTIVE -> ARCHIVED
			// — the dedup in applyAttributeMutation must reduce three sibling fanouts to one
			//   remove + one insert against this shared RGEI
			SharedRgeiMutationMatrixTest.this.sharedRgei.removeFilterAttribute(
				referenceSchema, entityAttrSchema, noLocales, null, "ACTIVE", ENTITY_PK
			);
			SharedRgeiMutationMatrixTest.this.sharedRgei.insertFilterAttribute(
				referenceSchema, entityAttrSchema, noLocales, null, "ARCHIVED", ENTITY_PK
			);

			// then the filter index reflects only ARCHIVED for the entity — and exactly one record entry
			final Bitmap archivedRecords = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getFilterIndex(new AttributeIndexKey(null, "status", null))
				.getRecordsEqualTo("ARCHIVED");
			assertEquals(1, archivedRecords.size(), "ARCHIVED must hold the entity exactly once");
			assertTrue(archivedRecords.contains(ENTITY_PK));
			// the ACTIVE bucket is empty (cardinality dropped to zero on the single remove)
			final Bitmap activeRecords = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getFilterIndex(new AttributeIndexKey(null, "status", null))
				.getRecordsEqualTo("ACTIVE");
			assertTrue(activeRecords.isEmpty(), "ACTIVE must be gone from the filter index");
		}

		@Test
		@DisplayName("Should underflow if cardinality is decremented more times than incremented")
		void shouldUnderflowWhenCardinalityIsDecrementedMoreTimesThanIncremented() {
			// This test pins the contract that motivated the executor dedup: WITHOUT dedup, three
			// sibling fanouts would call removeFilterAttribute three times against the shared RGEI
			// for a single entity-attribute mutation, and the third call would fail because the
			// AttributeCardinalityIndex would already be empty. We verify this failure mode here so
			// that any future refactor that breaks the dedup is caught by the executor-driven tests
			// and not just hidden in long-running generational stress.
			SharedRgeiMutationMatrixTest.this.sharedRgei
				.insertPrimaryKeyIfMissing(ENTITY_PK, R1_REFERENCED_PK);

			final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract entityAttrSchema = createFilterableAttribute(
				"status", String.class
			);
			final Set<Locale> noLocales = Collections.emptySet();
			SharedRgeiMutationMatrixTest.this.sharedRgei.insertFilterAttribute(
				referenceSchema, entityAttrSchema, noLocales, null, "ACTIVE", ENTITY_PK
			);
			// first remove drops cardinality 1 -> 0 and dismantles the filter index entry
			SharedRgeiMutationMatrixTest.this.sharedRgei.removeFilterAttribute(
				referenceSchema, entityAttrSchema, noLocales, null, "ACTIVE", ENTITY_PK
			);
			// second remove (simulating un-deduped sibling fanout) must explode — the cardinality
			// index is already empty so its underflow check fires
			assertThrows(
				GenericEvitaInternalError.class,
				() -> SharedRgeiMutationMatrixTest.this.sharedRgei.removeFilterAttribute(
					referenceSchema, entityAttrSchema, noLocales, null, "ACTIVE", ENTITY_PK
				),
				"Second remove of the same (entity, value) on a shared RGEI must fail — this is the " +
					"failure mode that applyAttributeMutation's IdentityHashMap dedup prevents."
			);
		}
	}

	@Nested
	@DisplayName("5 — Reference-level attribute change")
	@Tag(ATTRIBUTE)
	class ReferenceLevelAttributeChangeTest {

		@Test
		@DisplayName("Should not affect sibling tracking when only R1 reference attribute changes")
		void shouldNotAffectSiblingTrackingWhenOnlyR1ReferenceAttributeChanges() {
			// given three siblings registered
			SharedRgeiMutationMatrixTest.this.seedAllSiblings();

			// when R1's reference-attribute "rank" mutates from 1 to 2 (per-reference fanout, no dedup)
			// — only R1 contributes; R2 and R3 keep their unrelated reference-level state intact
			final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract refAttrSchema = createFilterableAttribute(
				"rank", Integer.class
			);
			final Set<Locale> noLocales = Collections.emptySet();
			SharedRgeiMutationMatrixTest.this.sharedRgei.insertFilterAttribute(
				referenceSchema, refAttrSchema, noLocales, null, 1, ENTITY_PK
			);
			SharedRgeiMutationMatrixTest.this.sharedRgei.removeFilterAttribute(
				referenceSchema, refAttrSchema, noLocales, null, 1, ENTITY_PK
			);
			SharedRgeiMutationMatrixTest.this.sharedRgei.insertFilterAttribute(
				referenceSchema, refAttrSchema, noLocales, null, 2, ENTITY_PK
			);

			// then the filter index reflects the new value, the old value is gone, and the
			// per-reference owner tracking is untouched
			final AttributeIndexKey rankKey = new AttributeIndexKey(null, "rank", null);
			final Bitmap recordsForRank2 = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getFilterIndex(rankKey)
				.getRecordsEqualTo(2);
			assertEquals(1, recordsForRank2.size());
			assertTrue(recordsForRank2.contains(ENTITY_PK));
			final Bitmap recordsForRank1 = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getFilterIndex(rankKey)
				.getRecordsEqualTo(1);
			assertTrue(recordsForRank1.isEmpty());
			final Bitmap r2Owners = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getOwnerPKsForReferencedEntity(R2_REFERENCED_PK);
			final Bitmap r3Owners = SharedRgeiMutationMatrixTest.this.sharedRgei
				.getOwnerPKsForReferencedEntity(R3_REFERENCED_PK);
			assertNotNull(r2Owners);
			assertNotNull(r3Owners);
			assertTrue(r2Owners.contains(ENTITY_PK));
			assertTrue(r3Owners.contains(ENTITY_PK));
		}
	}

	@Nested
	@DisplayName("6 — Add price")
	@Tag(PRICE)
	class AddPriceTest {

		@Test
		@DisplayName("Should expose price exactly once in shared RGEI after one add (set semantics)")
		void shouldExposePriceExactlyOnceAfterOneAdd() {
			// given three siblings registered + one entity-level price added (dedup means: one call)
			SharedRgeiMutationMatrixTest.this.seedAllSiblings();

			final int internalPriceId = addEntityPriceToSuperAndShared(7);

			// then the RGEI exposes the price exactly once via its price ref index
			final PriceListAndCurrencyPriceRefIndex priceLeaf =
				(PriceListAndCurrencyPriceRefIndex)
					SharedRgeiMutationMatrixTest.this.sharedRgei.getPriceIndex(BASIC_CZK_KEY);
			assertNotNull(priceLeaf, "Price leaf for basic/CZK must exist after addPrice");
			assertTrue(
				priceLeaf.getIndexedPriceEntityIds().contains(ENTITY_PK),
				"Entity PK must be visible in the price leaf bitmap"
			);
			// internal price id appears exactly once
			final int[] indexedPriceIds = priceLeaf.getIndexedPriceIds();
			assertEquals(1, indexedPriceIds.length, "Exactly one internal price id must be present");
			assertEquals(internalPriceId, indexedPriceIds[0]);
		}
	}

	@Nested
	@DisplayName("7 — Remove price")
	@Tag(PRICE)
	class RemovePriceTest {

		@Test
		@DisplayName("Should remove price bucket exactly once on remove (no double-destruction)")
		void shouldRemovePriceBucketExactlyOnce() {
			// given three siblings registered + one price added
			SharedRgeiMutationMatrixTest.this.seedAllSiblings();
			final int internalPriceId = addEntityPriceToSuperAndShared(7);

			// when the price is removed (single deduped call, like the executor does)
			removeEntityPriceFromShared(internalPriceId, 7);

			// then the price leaf is gone (set-semantic removal — empty bucket is dropped)
			final PriceListAndCurrencyPriceRefIndex priceLeaf =
				(PriceListAndCurrencyPriceRefIndex)
					SharedRgeiMutationMatrixTest.this.sharedRgei.getPriceIndex(BASIC_CZK_KEY);
			assertNull(priceLeaf, "Empty price bucket must be removed from the RGEI after last remove");
		}

		@Test
		@DisplayName("Should throw if executor dedup is bypassed and remove is called twice")
		void shouldThrowIfDedupBypassedAndRemoveCalledTwice() {
			// pins the failure mode that applyPriceMutation's IdentityHashMap dedup prevents — if
			// three sibling fanouts each removed the same price from this shared RGEI, the second
			// call would find an already-destroyed bucket and throw.
			SharedRgeiMutationMatrixTest.this.sharedRgei
				.insertPrimaryKeyIfMissing(ENTITY_PK, R1_REFERENCED_PK);
			final int internalPriceId = addEntityPriceToSuperAndShared(7);
			removeEntityPriceFromShared(internalPriceId, 7);

			// second remove against an already-emptied bucket throws: the price-leaf lookup hits
			// the `Price index for price list X and currency Y not found!` precondition
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> removeEntityPriceFromShared(internalPriceId, 7),
				"Second remove against an emptied bucket must fail — this is the failure mode " +
					"applyPriceMutation's index-identity dedup prevents."
			);
		}
	}

	@Nested
	@DisplayName("8 — Locale change")
	class LocaleChangeTest {

		@Test
		@DisplayName("Should track entity in a locale exactly once even after duplicate upserts")
		void shouldTrackEntityInLocaleExactlyOnce() {
			// given three siblings registered
			SharedRgeiMutationMatrixTest.this.seedAllSiblings();

			// when the entity-level locale ENGLISH is added once (dedup) and the call is repeated
			// idempotently — upsertLanguage is set-semantic
			SharedRgeiMutationMatrixTest.this.sharedRgei.upsertLanguage(
				Locale.ENGLISH, ENTITY_PK, buildLocaleAwareEntitySchema()
			);
			final boolean repeated = SharedRgeiMutationMatrixTest.this.sharedRgei.upsertLanguage(
				Locale.ENGLISH, ENTITY_PK, buildLocaleAwareEntitySchema()
			);

			// then the locale tracking shows entity once and the duplicate upsert reports "not added"
			assertFalse(repeated, "Duplicate upsertLanguage must report no-op");
			assertTrue(
				SharedRgeiMutationMatrixTest.this.sharedRgei.getLanguages().contains(Locale.ENGLISH),
				"ENGLISH locale tracking must exist"
			);
			// the entity must be present in the ENGLISH locale's formula result
			assertTrue(
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.getRecordsWithLanguageFormula(Locale.ENGLISH)
					.compute()
					.contains(ENTITY_PK),
				"Entity PK must be visible in ENGLISH locale tracking"
			);
		}

		@Test
		@DisplayName("Should clean up locale tracking after entity is removed from locale")
		void shouldCleanUpLocaleTrackingAfterEntityRemovedFromLocale() {
			// given an entity tracked in ENGLISH within this RGEI
			SharedRgeiMutationMatrixTest.this.sharedRgei
				.insertPrimaryKeyIfMissing(ENTITY_PK, R1_REFERENCED_PK);
			SharedRgeiMutationMatrixTest.this.sharedRgei.upsertLanguage(
				Locale.ENGLISH, ENTITY_PK, buildLocaleAwareEntitySchema()
			);

			// when the locale is removed (one deduped call from the executor)
			SharedRgeiMutationMatrixTest.this.sharedRgei.removeLanguage(Locale.ENGLISH, ENTITY_PK);

			// then the locale bucket is fully drained — the locale key is removed entirely
			assertFalse(
				SharedRgeiMutationMatrixTest.this.sharedRgei.getLanguages().contains(Locale.ENGLISH),
				"Empty locale bucket must be removed entirely"
			);
		}
	}

	@Nested
	@DisplayName("9 — Mixed sequence (cheap deterministic replay)")
	@Tag(ATTRIBUTE)
	@Tag(PRICE)
	class MixedSequenceTest {

		@Test
		@DisplayName("Should leave shared RGEI in a clean state after representative mixed sequence")
		void shouldLeaveSharedRgeiInCleanStateAfterMixedSequence() {
			// Cheap deterministic surrogate for the long-running generational test. Interleaves
			// add ref -> change attr -> add price -> remove ref -> remove price -> remove ref ->
			// remove ref. The end state must be exactly empty.
			final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract codeSchema = createFilterableAttribute("code", String.class);
			final Set<Locale> noLocales = Collections.emptySet();

			// 1) add R1 — entity enters RGEI
			assertEquals(
				CardinalityChange.BOUNDARY_CROSSED,
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.insertPrimaryKeyIfMissing(ENTITY_PK, R1_REFERENCED_PK),
				"R1 add must be the 0->1 transition"
			);
			SharedRgeiMutationMatrixTest.this.sharedRgei.insertFilterAttribute(
				referenceSchema, codeSchema, noLocales, null, "A", ENTITY_PK
			);

			// 2) add R2 — entity already present (no transition)
			assertEquals(
				CardinalityChange.NO_BOUNDARY_CROSSING,
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.insertPrimaryKeyIfMissing(ENTITY_PK, R2_REFERENCED_PK),
				"R2 add must NOT signal a transition"
			);

			// 3) entity-level attribute change A -> B (deduped: one remove + one insert per RGEI)
			SharedRgeiMutationMatrixTest.this.sharedRgei.removeFilterAttribute(
				referenceSchema, codeSchema, noLocales, null, "A", ENTITY_PK
			);
			SharedRgeiMutationMatrixTest.this.sharedRgei.insertFilterAttribute(
				referenceSchema, codeSchema, noLocales, null, "B", ENTITY_PK
			);

			// 4) add an entity-level price
			final int internalPriceId = addEntityPriceToSuperAndShared(7);

			// 5) add R3 — still no transition
			assertEquals(
				CardinalityChange.NO_BOUNDARY_CROSSING,
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.insertPrimaryKeyIfMissing(ENTITY_PK, R3_REFERENCED_PK),
				"R3 add must NOT signal a transition"
			);

			// 6) remove R1 — still no transition (R2, R3 hold it)
			assertEquals(
				CardinalityChange.NO_BOUNDARY_CROSSING,
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.removePrimaryKey(ENTITY_PK, R1_REFERENCED_PK),
				"R1 remove must NOT signal a transition"
			);

			// 7) remove the price (deduped: one remove per shared RGEI)
			removeEntityPriceFromShared(internalPriceId, 7);

			// 8) remove R2 — still no transition (R3 still holds it)
			assertEquals(
				CardinalityChange.NO_BOUNDARY_CROSSING,
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.removePrimaryKey(ENTITY_PK, R2_REFERENCED_PK),
				"R2 remove must NOT signal a transition"
			);

			// 9) entity-level attribute removal as part of the final eviction
			SharedRgeiMutationMatrixTest.this.sharedRgei.removeFilterAttribute(
				referenceSchema, codeSchema, noLocales, null, "B", ENTITY_PK
			);

			// 10) remove R3 — finally the 1->0 transition fires
			assertEquals(
				CardinalityChange.BOUNDARY_CROSSED,
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.removePrimaryKey(ENTITY_PK, R3_REFERENCED_PK),
				"R3 remove (last sibling) must signal 1->0 transition"
			);

			// then the RGEI's observable state is fully drained
			assertTrue(
				SharedRgeiMutationMatrixTest.this.sharedRgei.getAllPrimaryKeys().isEmpty(),
				"Primary key bitmap must be empty"
			);
			assertTrue(
				SharedRgeiMutationMatrixTest.this.sharedRgei.getReferencedEntityPrimaryKeys().isEmpty(),
				"referencingPksIndex must be empty"
			);
			assertNull(
				SharedRgeiMutationMatrixTest.this.sharedRgei.getPriceIndex(BASIC_CZK_KEY),
				"Price leaf must be dropped"
			);
			assertNull(
				SharedRgeiMutationMatrixTest.this.sharedRgei
					.getFilterIndex(new AttributeIndexKey(null, "code", null)),
				"Filter index entry for `code` must be gone"
			);
			// the RGEI itself reports empty
			assertTrue(SharedRgeiMutationMatrixTest.this.sharedRgei.isEmpty());
		}
	}

	// --- shared fixture builders -----------------------------------------------------------------

	/**
	 * Builds a fresh {@link ReducedGroupEntityIndex} whose discriminator carries the shared
	 * representative-attribute values so that all three sibling references R1/R2/R3 would resolve
	 * to this exact instance via `getOrCreateReferencedGroupEntityIndex`.
	 *
	 * @return a fresh empty RGEI
	 */
	@Nonnull
	private static ReducedGroupEntityIndex createSharedRgei() {
		final RepresentativeReferenceKey groupKey = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, GROUP_PK)
		);
		final EntityIndexKey indexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, groupKey
		);
		return new ReducedGroupEntityIndex(INDEX_PK, ENTITY_TYPE, indexKey);
	}

	/**
	 * Registers all three sibling references (R1, R2, R3) of the same entity on the shared RGEI.
	 * Used by scenarios that need a fully-populated index before exercising a mutation.
	 */
	private void seedAllSiblings() {
		this.sharedRgei.insertPrimaryKeyIfMissing(ENTITY_PK, R1_REFERENCED_PK);
		this.sharedRgei.insertPrimaryKeyIfMissing(ENTITY_PK, R2_REFERENCED_PK);
		this.sharedRgei.insertPrimaryKeyIfMissing(ENTITY_PK, R3_REFERENCED_PK);
	}

	/**
	 * Attaches the RGEI to a mock catalog so the price ref index can resolve shared price records
	 * via the super index. Mirrors the wiring used by {@link io.evitadb.core.catalog.Catalog} in
	 * production.
	 *
	 * @param target RGEI to attach
	 * @param superIndex backing super index for price record lookups
	 */
	private static void attachToMockCatalog(
		@Nonnull ReducedGroupEntityIndex target,
		@Nonnull PriceSuperIndex superIndex
	) {
		final GlobalEntityIndex mockGlobalIndex = mock(GlobalEntityIndex.class);
		when(mockGlobalIndex.getPriceIndex(ArgumentMatchers.any(PriceIndexKey.class)))
			.thenAnswer(invocation -> superIndex.getPriceIndex(invocation.getArgument(0)));

		final Catalog mockCatalog = mock(Catalog.class);
		when(mockCatalog.getEntityIndexIfExists(
			ArgumentMatchers.eq(ENTITY_TYPE),
			ArgumentMatchers.eq(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)),
			ArgumentMatchers.eq(GlobalEntityIndex.class)
		)).thenReturn(Optional.of(mockGlobalIndex));

		target.attachToCatalog(ENTITY_TYPE, mockCatalog);
	}

	/**
	 * Creates a non-localized, filterable {@link AttributeSchemaContract} for use in attribute
	 * scenarios. Mirrors the helper used by `ReducedGroupEntityIndexTest`.
	 *
	 * @param name attribute name
	 * @param type attribute value type
	 * @return a freshly built attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract createFilterableAttribute(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type
	) {
		return AttributeSchema._internalBuild(
			name, null, new Scope[]{Scope.LIVE}, null,
			false, false, false, type, null
		);
	}

	/**
	 * Builds a minimal entity-schema-shaped mock that satisfies the locale validation in
	 * {@link io.evitadb.index.EntityIndex#upsertLanguage}: the schema must claim that ENGLISH is
	 * an allowed locale.
	 *
	 * @return mock schema that accepts ENGLISH
	 */
	@Nonnull
	private static EntitySchemaContract buildLocaleAwareEntitySchema() {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getLocales()).thenReturn(Set.of(Locale.ENGLISH));
		when(schema.getEvolutionMode()).thenReturn(Collections.emptySet());
		return schema;
	}

	/**
	 * Adds a price to the backing super index and then to the shared RGEI exactly once, mirroring
	 * the dedup contract of `applyPriceMutation`: a single addPrice call per unique reduced index.
	 *
	 * @param priceId external price id
	 * @return the assigned internal price id
	 */
	private int addEntityPriceToSuperAndShared(int priceId) {
		final int internalPriceId = this.nextInternalPriceId++;
		// PriceSuperIndex does not validate the reference schema — null is fine for the super index
		this.priceSuperIndex.addPrice(
			null, ENTITY_PK, internalPriceId,
			new PriceKey(priceId, PRICE_LIST_BASIC, CURRENCY_CZK),
			PriceInnerRecordHandling.NONE,
			null, null, 10000, 12100
		);
		// ReducedGroupEntityIndex.addPrice enforces assertPartitioningIndex(referenceSchema) and
		// rejects null — pass the test-wide shared schema mock instead
		this.sharedRgei.addPrice(
			this.sharedRefSchema, ENTITY_PK, internalPriceId,
			new PriceKey(priceId, PRICE_LIST_BASIC, CURRENCY_CZK),
			PriceInnerRecordHandling.NONE,
			null, null, 10000, 12100
		);
		return internalPriceId;
	}

	/**
	 * Removes a previously added price from the shared RGEI exactly once, mirroring the dedup
	 * contract of `applyPriceMutation`. Does NOT remove from the super index — the test only
	 * cares about the RGEI's observable post-state.
	 *
	 * @param internalPriceId the internal id assigned at add time
	 * @param priceId         the original external price id
	 */
	private void removeEntityPriceFromShared(int internalPriceId, int priceId) {
		// ReducedGroupEntityIndex.priceRemove enforces assertPartitioningIndex(referenceSchema) too
		this.sharedRgei.priceRemove(
			this.sharedRefSchema, ENTITY_PK, internalPriceId,
			new PriceKey(priceId, PRICE_LIST_BASIC, CURRENCY_CZK),
			PriceInnerRecordHandling.NONE,
			null, null, 10000, 12100
		);
	}

}
