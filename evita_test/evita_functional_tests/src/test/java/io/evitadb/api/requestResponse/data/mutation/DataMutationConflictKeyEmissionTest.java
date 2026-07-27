/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.data.mutation;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.mutation.conflict.AssociatedDataConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.EntityConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.EntityResidualConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.HierarchyConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.PriceConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.PriceInnerRecordHandlingStrategyConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ReferenceAttributeConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ReferenceAttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ReferenceConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that every data-mutation `collectConflictKeys` entry point wires the correct, fully-qualified
 * conflict key through a real {@link ConflictGenerationContext}. Where {@link ConflictEmissionGapTest} guards
 * the two historically missing emission gaps, this class exercises the whole per-mutation emission matrix:
 * each local mutation's predicate-true branch (the exact key it must contribute) and its predicate-false /
 * empty branch, plus the coarse entity/collection fallback assembled by
 * {@link EntityMutation#getConflictKeyStream} and the whole-entity key the scope mutation emits.
 *
 * Assertions compare against full key instances (type, entity type, primary key, and every element
 * coordinate) rather than mere key-class membership, so a regression that emits a key of the right shape but
 * the wrong identity is caught.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Data mutation conflict-key emission wiring")
@Tag(CONTRACT)
@Tag(TRANSACTION)
class DataMutationConflictKeyEmissionTest {
	private static final String ENTITY = "Product";
	private static final int PK = 1;
	private static final String ATTRIBUTE = "code";
	private static final String ASSOCIATED_DATA = "labels";
	private static final String REFERENCE = "brand";
	private static final int REFERENCE_PK = 42;
	private static final Currency EUR = Currency.getInstance("EUR");

	/**
	 * Builds a global-backed context whose fixed resolution is {@link ConflictPolicy#ENTITY} refined by the
	 * given granularity refinements — the setup under which the sub-entity {@code shouldEmit*} predicates
	 * return true.
	 *
	 * @param refinements the granular refinements to activate on top of the {@code ENTITY} coarse policy
	 * @return a global-backed context yielding an emit-enabling resolution
	 */
	@Nonnull
	private static ConflictGenerationContext entityGranular(@Nonnull GranularConflictPolicy... refinements) {
		return new ConflictGenerationContext(
			new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.copyOf(List.of(refinements)))
		);
	}

	/**
	 * Builds a global-backed context whose fixed resolution is the given coarse policy with no granular
	 * refinement — the setup under which every sub-entity {@code shouldEmit*} predicate returns false.
	 *
	 * @param policy the coarse conflict policy to fix
	 * @return a global-backed context yielding a suppressing resolution
	 */
	@Nonnull
	private static ConflictGenerationContext coarse(@Nonnull ConflictPolicy policy) {
		return new ConflictGenerationContext(new ConflictResolution(policy));
	}

	/**
	 * Builds a schema-aware context whose catalog resolves to {@code catalogPolicy} and whose sole entity
	 * type resolves to {@code entityPolicy}, mirroring the mock wiring used by the context's own unit tests.
	 * Used to prove that an entry point resolves its coarse policy at the entity level (honouring a per-entity
	 * pinned resolution) rather than at the catalog level.
	 *
	 * @param catalogPolicy the coarse policy pinned on the catalog schema
	 * @param entityPolicy  the coarse policy pinned on the single entity schema
	 * @return a schema-aware context with the described precedence
	 */
	@Nonnull
	private static ConflictGenerationContext schemaAware(
		@Nonnull ConflictPolicy catalogPolicy,
		@Nonnull ConflictPolicy entityPolicy
	) {
		final CatalogSchemaContract catalog = mock(CatalogSchemaContract.class);
		when(catalog.getConflictResolution())
			.thenReturn(Optional.of(new ConflictResolution(catalogPolicy)));
		final EntitySchemaContract entity = mock(EntitySchemaContract.class);
		when(entity.getConflictResolution())
			.thenReturn(Optional.of(new ConflictResolution(entityPolicy)));
		return new ConflictGenerationContext(
			new ConflictResolution(ConflictPolicy.NONE), catalog, entityType -> entity
		);
	}

	/**
	 * Collects the conflict keys produced by the given local mutation within an entity scope of the supplied
	 * context, materialised into a list for assertion.
	 *
	 * @param context  the context to drive the emission through
	 * @param mutation the local mutation whose keys are collected
	 * @param pk       the entity primary key to establish in scope, or null when not yet assigned
	 * @return the emitted conflict keys, in emission order
	 */
	@Nonnull
	private static List<ConflictKey> keysWithin(
		@Nonnull ConflictGenerationContext context,
		@Nonnull LocalMutation<?, ?> mutation,
		@Nonnull Integer pk
	) {
		return context.withEntityType(ENTITY, pk, ctx -> mutation.collectConflictKeys(ctx).toList());
	}

	/**
	 * Builds an indexed EUR price upsert for a single, fully specified price coordinate.
	 *
	 * @param priceId   the price identifier
	 * @param priceList the price list name
	 * @return a fully specified {@link UpsertPriceMutation}
	 */
	@Nonnull
	private static UpsertPriceMutation upsertPrice(int priceId, @Nonnull String priceList) {
		return new UpsertPriceMutation(
			new PriceKey(priceId, priceList, EUR),
			null,
			BigDecimal.ONE,
			BigDecimal.ZERO,
			BigDecimal.ONE,
			null,
			true
		);
	}

	@Nested
	@DisplayName("Entity upsert coarse fallback")
	class EntityUpsertCoarseFallback {

		@Test
		@DisplayName("A non-granular mutation triggers the coarse residual key, not the full entity key, under ENTITY policy")
		void shouldEmitResidualKeyWhenAtLeastOneKeyMissing() {
			// a plain attribute upsert produces no granular key when ENTITY_ATTRIBUTE is not refined, so the
			// coarse fallback is triggered by the missing key; since this is not a forced creation, the
			// fallback is the finer residual key (the entity's shared surface), not the full entity key -
			// this is the carve-out fix itself: a coarse writer must no longer contain a carved-out sibling
			final EntityUpsertMutation mutation = new EntityUpsertMutation(
				ENTITY, PK, EntityExistence.MAY_EXIST,
				new UpsertAttributeMutation(ATTRIBUTE, "foo")
			);
			final List<ConflictKey> keys = mutation.collectConflictKeys(coarse(ConflictPolicy.ENTITY)).toList();
			assertEquals(List.of(new EntityResidualConflictKey(ENTITY, PK)), keys);
		}

		@Test
		@DisplayName("Forced creation emits the full entity key alongside a granular key, never the residual key")
		void shouldEmitFullEntityKeyAlongsideGranularKeyForForcedCreation() {
			// mixed mutations: the attribute is carved out (its own key is produced), the associated data is
			// not (ASSOCIATED_DATA is not refined) - so a missing granular key is present too. Forced creation
			// must still win with the full entity key, never the residual, since the full key already
			// bidirectionally contains the residual
			final ConflictGenerationContext context = entityGranular(GranularConflictPolicy.ENTITY_ATTRIBUTE);
			final List<? extends LocalMutation<?, ?>> mutations = List.of(
				new UpsertAttributeMutation(ATTRIBUTE, "foo"),
				new UpsertAssociatedDataMutation(ASSOCIATED_DATA, "bar")
			);
			final List<ConflictKey> keys = context.withEntityType(
				ENTITY, PK,
				ctx -> EntityMutation.getConflictKeyStream(ENTITY, PK, mutations, EntityExistence.MUST_NOT_EXIST, ctx).toList()
			);
			assertTrue(keys.contains(new AttributeConflictKey(ENTITY, PK, ATTRIBUTE)));
			assertTrue(keys.contains(new EntityConflictKey(ENTITY, PK)));
			assertFalse(keys.contains(new EntityResidualConflictKey(ENTITY, PK)));
		}

		@Test
		@DisplayName("Forced creation under COLLECTION policy emits the collection key")
		void shouldEmitCollectionKeyForForcedCreationUnderCollectionPolicy() {
			final EntityUpsertMutation mutation = new EntityUpsertMutation(
				ENTITY, PK, EntityExistence.MUST_NOT_EXIST,
				new UpsertAttributeMutation(ATTRIBUTE, "foo")
			);
			final List<ConflictKey> keys = mutation.collectConflictKeys(coarse(ConflictPolicy.COLLECTION)).toList();
			assertEquals(List.of(new CollectionConflictKey(ENTITY)), keys);
		}

		@Test
		@DisplayName("A null primary key under COLLECTION policy emits the collection key")
		void shouldEmitCollectionKeyWhenPrimaryKeyIsNullUnderCollectionPolicy() {
			final EntityUpsertMutation mutation = new EntityUpsertMutation(
				ENTITY, null, EntityExistence.MAY_EXIST,
				new UpsertAttributeMutation(ATTRIBUTE, "foo")
			);
			final List<ConflictKey> keys = mutation.collectConflictKeys(coarse(ConflictPolicy.COLLECTION)).toList();
			assertEquals(List.of(new CollectionConflictKey(ENTITY)), keys);
		}

		@Test
		@DisplayName("Forced creation under NONE policy emits nothing")
		void shouldEmitNothingForForcedCreationUnderNonePolicy() {
			final EntityUpsertMutation mutation = new EntityUpsertMutation(
				ENTITY, PK, EntityExistence.MUST_NOT_EXIST,
				new UpsertAttributeMutation(ATTRIBUTE, "foo")
			);
			final List<ConflictKey> keys = mutation.collectConflictKeys(coarse(ConflictPolicy.NONE)).toList();
			assertTrue(keys.isEmpty());
		}
	}

	@Nested
	@DisplayName("Coarse fallback interplay with carved-out items")
	class CoarseFallbackInterplayWithGranularItems {

		@Test
		@DisplayName("A carved-out associated data alongside a non-granular attribute emits the granular key plus the residual, never the full entity key")
		void shouldEmitGranularKeyAndResidualForMixedCarveOut() {
			// only ASSOCIATED_DATA is refined: the associated data mutation is carved out and produces its own
			// key, while the attribute mutation is not and produces none - triggering the residual fallback.
			// The two keys are disjoint siblings, not a whole-entity key
			final ConflictGenerationContext context = entityGranular(GranularConflictPolicy.ASSOCIATED_DATA);
			final List<? extends LocalMutation<?, ?>> mutations = List.of(
				new UpsertAttributeMutation(ATTRIBUTE, "foo"),
				new UpsertAssociatedDataMutation(ASSOCIATED_DATA, "bar")
			);
			final List<ConflictKey> keys = context.withEntityType(
				ENTITY, PK,
				ctx -> EntityMutation.getConflictKeyStream(ENTITY, PK, mutations, EntityExistence.MAY_EXIST, ctx).toList()
			);
			assertEquals(
				List.of(
					new AssociatedDataConflictKey(ENTITY, PK, ASSOCIATED_DATA),
					new EntityResidualConflictKey(ENTITY, PK)
				),
				keys
			);
		}

		@Test
		@DisplayName("Fully granular mutations emit only their own keys, with no entity-level key at all")
		void shouldEmitOnlyGranularKeysWhenNothingIsMissing() {
			// every mutation produces its own granular key (both refinements are active), so the coarse
			// fallback is never triggered: no residual and no full entity key appears alongside them
			final ConflictGenerationContext context = entityGranular(
				GranularConflictPolicy.ENTITY_ATTRIBUTE, GranularConflictPolicy.ASSOCIATED_DATA
			);
			final List<? extends LocalMutation<?, ?>> mutations = List.of(
				new UpsertAttributeMutation(ATTRIBUTE, "foo"),
				new UpsertAssociatedDataMutation(ASSOCIATED_DATA, "bar")
			);
			final List<ConflictKey> keys = context.withEntityType(
				ENTITY, PK,
				ctx -> EntityMutation.getConflictKeyStream(ENTITY, PK, mutations, EntityExistence.MAY_EXIST, ctx).toList()
			);
			assertEquals(
				List.of(
					new AttributeConflictKey(ENTITY, PK, ATTRIBUTE),
					new AssociatedDataConflictKey(ENTITY, PK, ASSOCIATED_DATA)
				),
				keys
			);
		}
	}

	@Nested
	@DisplayName("Entity remove coarse branches")
	class EntityRemoveCoarseBranches {

		@Test
		@DisplayName("ENTITY policy removes emit the entity key")
		void shouldEmitEntityKeyUnderEntityPolicy() {
			final List<ConflictKey> keys = new EntityRemoveMutation(ENTITY, PK)
				.collectConflictKeys(coarse(ConflictPolicy.ENTITY))
				.toList();
			assertEquals(List.of(new EntityConflictKey(ENTITY, PK)), keys);
		}

		@Test
		@DisplayName("COLLECTION policy removes emit the collection key")
		void shouldEmitCollectionKeyUnderCollectionPolicy() {
			final List<ConflictKey> keys = new EntityRemoveMutation(ENTITY, PK)
				.collectConflictKeys(coarse(ConflictPolicy.COLLECTION))
				.toList();
			assertEquals(List.of(new CollectionConflictKey(ENTITY)), keys);
		}

		@Test
		@DisplayName("NONE policy removes emit nothing")
		void shouldEmitNothingUnderNonePolicy() {
			final List<ConflictKey> keys = new EntityRemoveMutation(ENTITY, PK)
				.collectConflictKeys(coarse(ConflictPolicy.NONE))
				.toList();
			assertTrue(keys.isEmpty());
		}

		/**
		 * Verifies that {@link EntityRemoveMutation#collectConflictKeys} resolves the coarse policy within the
		 * per-entity scope, so an entity's pinned {@link ConflictPolicy#ENTITY} resolution wins over the
		 * catalog-level {@link ConflictPolicy#COLLECTION}. This mirrors
		 * {@link EntityUpsertMutation#collectConflictKeys}, ensuring a concurrent upsert and remove of the same
		 * pinned entity emit matching keys and therefore conflict.
		 */
		@Test
		@DisplayName("Entity remove resolves the coarse policy per entity, honouring a pinned entity resolution")
		void shouldResolveCoarsePolicyPerEntityForEntityRemove() {
			final ConflictGenerationContext context = schemaAware(ConflictPolicy.COLLECTION, ConflictPolicy.ENTITY);
			final List<ConflictKey> keys = new EntityRemoveMutation(ENTITY, PK)
				.collectConflictKeys(context)
				.toList();
			assertEquals(List.of(new EntityConflictKey(ENTITY, PK)), keys);
		}
	}

	@Nested
	@DisplayName("Attribute mutation")
	class AttributeMutationEmission {

		@Test
		@DisplayName("Emits the attribute key when the entity-attribute refinement is active")
		void shouldEmitAttributeKeyWhenRefinementActive() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.ENTITY_ATTRIBUTE),
				new UpsertAttributeMutation(ATTRIBUTE, "foo"),
				PK
			);
			assertEquals(List.of(new AttributeConflictKey(ENTITY, PK, ATTRIBUTE)), keys);
		}

		@Test
		@DisplayName("Emits nothing when the entity-attribute refinement is inactive")
		void shouldEmitNothingWhenRefinementInactive() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new UpsertAttributeMutation(ATTRIBUTE, "foo"),
				PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits nothing when the primary key is not yet assigned")
		void shouldEmitNothingWhenPrimaryKeyIsNull() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.ENTITY_ATTRIBUTE),
				new UpsertAttributeMutation(ATTRIBUTE, "foo"),
				null
			);
			assertTrue(keys.isEmpty());
		}
	}

	@Nested
	@DisplayName("Associated data mutation")
	class AssociatedDataMutationEmission {

		@Test
		@DisplayName("Emits the associated-data key when the refinement is active")
		void shouldEmitAssociatedDataKeyWhenRefinementActive() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.ASSOCIATED_DATA),
				new UpsertAssociatedDataMutation(ASSOCIATED_DATA, "foo"),
				PK
			);
			assertEquals(List.of(new AssociatedDataConflictKey(ENTITY, PK, ASSOCIATED_DATA)), keys);
		}

		@Test
		@DisplayName("Emits nothing when the associated-data refinement is inactive")
		void shouldEmitNothingWhenRefinementInactive() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new UpsertAssociatedDataMutation(ASSOCIATED_DATA, "foo"),
				PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits nothing when the primary key is not yet assigned")
		void shouldEmitNothingWhenPrimaryKeyIsNull() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.ASSOCIATED_DATA),
				new UpsertAssociatedDataMutation(ASSOCIATED_DATA, "foo"),
				null
			);
			assertTrue(keys.isEmpty());
		}
	}

	@Nested
	@DisplayName("Reference mutation")
	class ReferenceMutationEmission {

		@Test
		@DisplayName("Emits the reference key when the reference refinement is active")
		void shouldEmitReferenceKeyWhenRefinementActive() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.REFERENCE),
				new InsertReferenceMutation(new ReferenceKey(REFERENCE, REFERENCE_PK)),
				PK
			);
			assertEquals(List.of(new ReferenceConflictKey(ENTITY, PK, REFERENCE, REFERENCE_PK)), keys);
		}

		@Test
		@DisplayName("Emits nothing when the reference refinement is inactive")
		void shouldEmitNothingWhenRefinementInactive() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new InsertReferenceMutation(new ReferenceKey(REFERENCE, REFERENCE_PK)),
				PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits nothing when the primary key is not yet assigned")
		void shouldEmitNothingWhenPrimaryKeyIsNull() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.REFERENCE),
				new InsertReferenceMutation(new ReferenceKey(REFERENCE, REFERENCE_PK)),
				null
			);
			assertTrue(keys.isEmpty());
		}
	}

	@Nested
	@DisplayName("Parent mutation")
	class ParentMutationEmission {

		@Test
		@DisplayName("Emits the hierarchy key when the hierarchy refinement is active")
		void shouldEmitHierarchyKeyWhenRefinementActive() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.HIERARCHY),
				new SetParentMutation(10),
				PK
			);
			assertEquals(List.of(new HierarchyConflictKey(ENTITY, PK)), keys);
		}

		@Test
		@DisplayName("Emits nothing when the hierarchy refinement is inactive")
		void shouldEmitNothingWhenRefinementInactive() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new SetParentMutation(10),
				PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits nothing when the primary key is not yet assigned")
		void shouldEmitNothingWhenPrimaryKeyIsNull() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.HIERARCHY),
				new SetParentMutation(10),
				null
			);
			assertTrue(keys.isEmpty());
		}
	}

	@Nested
	@DisplayName("Price mutation")
	class PriceMutationEmission {

		@Test
		@DisplayName("Emits the price key when the price refinement is active")
		void shouldEmitPriceKeyWhenRefinementActive() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.PRICE),
				upsertPrice(7, "basic"),
				PK
			);
			assertEquals(List.of(new PriceConflictKey(ENTITY, PK, 7, EUR, "basic")), keys);
		}

		@Test
		@DisplayName("Emits nothing when the price refinement is inactive")
		void shouldEmitNothingWhenRefinementInactive() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				upsertPrice(7, "basic"),
				PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits nothing when the primary key is not yet assigned")
		void shouldEmitNothingWhenPrimaryKeyIsNull() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.PRICE),
				upsertPrice(7, "basic"),
				null
			);
			assertTrue(keys.isEmpty());
		}
	}

	@Nested
	@DisplayName("Price inner-record-handling mutation")
	class PriceInnerRecordHandlingMutationEmission {

		@Test
		@DisplayName("Emits the inner-record-handling key when the price refinement is active")
		void shouldEmitInnerRecordHandlingKeyWhenRefinementActive() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.PRICE),
				new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.SUM),
				PK
			);
			assertEquals(List.of(new PriceInnerRecordHandlingStrategyConflictKey(ENTITY, PK)), keys);
		}

		@Test
		@DisplayName("Emits nothing when the price refinement is inactive")
		void shouldEmitNothingWhenRefinementInactive() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.SUM),
				PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits nothing when the primary key is not yet assigned")
		void shouldEmitNothingWhenPrimaryKeyIsNull() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.PRICE),
				new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.SUM),
				null
			);
			assertTrue(keys.isEmpty());
		}
	}

	@Nested
	@DisplayName("Apply-delta attribute mutation")
	class ApplyDeltaAttributeMutationEmission {

		@Test
		@DisplayName("Emits the attribute-delta key when the entity-attribute refinement is active")
		void shouldEmitAttributeDeltaKeyWhenRefinementActive() {
			// exercises the shouldEmitEntityAttributeKey predicate-true branch on an unconstrained delta,
			// complementing the range-constrained branch guarded by ConflictEmissionGapTest
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.ENTITY_ATTRIBUTE),
				new ApplyDeltaAttributeMutation<>(ATTRIBUTE, 5),
				PK
			);
			assertEquals(
				List.of(new AttributeDeltaConflictKey(ENTITY, PK, new AttributeKey(ATTRIBUTE), 5, null, false)),
				keys
			);
		}

		@Test
		@DisplayName("Emits nothing when the delta is unconstrained and the refinement is inactive")
		void shouldEmitNothingWhenUnconstrainedAndRefinementInactive() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new ApplyDeltaAttributeMutation<>(ATTRIBUTE, 5),
				PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("A range-constrained delta on a non-carved-out attribute is marked shared-surface, parented by the residual key")
		void shouldEmitSharedSurfaceDeltaKeyForNonGranularRangeConstrainedDelta() {
			// the range guard forces emission even though ENTITY_ATTRIBUTE is not refined; since the attribute
			// was not carved out, the key must route through the entity's shared surface, not the absolute
			// attribute key, so it still conflicts with a coarse writer of an unrelated non-carved-out field
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new ApplyDeltaAttributeMutation<>(ATTRIBUTE, 5, IntegerNumberRange.between(0, 100)),
				PK
			);
			assertEquals(1, keys.size());
			final AttributeDeltaConflictKey key = (AttributeDeltaConflictKey) keys.get(0);
			assertTrue(key.sharedSurface());
			assertEquals(new EntityResidualConflictKey(ENTITY, PK), key.parentConflictKey());
		}

		@Test
		@DisplayName("A range-constrained delta on a carved-out attribute is not marked shared-surface, parented by the absolute attribute key")
		void shouldEmitCarvedOutDeltaKeyForGranularRangeConstrainedDelta() {
			// the attribute is carved out (ENTITY_ATTRIBUTE refined), so even though the range guard alone
			// would force emission, the key still routes through the absolute attribute key it can conflict
			// with, not the shared surface
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.ENTITY_ATTRIBUTE),
				new ApplyDeltaAttributeMutation<>(ATTRIBUTE, 5, IntegerNumberRange.between(0, 100)),
				PK
			);
			assertEquals(1, keys.size());
			final AttributeDeltaConflictKey key = (AttributeDeltaConflictKey) keys.get(0);
			assertFalse(key.sharedSurface());
			assertEquals(new AttributeConflictKey(ENTITY, PK, ATTRIBUTE), key.parentConflictKey());
		}
	}

	@Nested
	@DisplayName("Reference attribute mutation")
	class ReferenceAttributeMutationEmission {

		@Test
		@DisplayName("Emits the reference-attribute key for a plain wrapped attribute when the refinement is active")
		void shouldEmitReferenceAttributeKeyWhenRefinementActive() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.REFERENCE_ATTRIBUTE),
				new ReferenceAttributeMutation(
					new ReferenceKey(REFERENCE, REFERENCE_PK),
					new UpsertAttributeMutation(ATTRIBUTE, "foo")
				),
				PK
			);
			assertEquals(
				List.of(new ReferenceAttributeConflictKey(ENTITY, PK, REFERENCE, REFERENCE_PK, ATTRIBUTE)),
				keys
			);
		}

		@Test
		@DisplayName("Emits nothing for a plain wrapped attribute when the refinement is inactive")
		void shouldEmitNothingForPlainAttributeWhenRefinementInactive() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new ReferenceAttributeMutation(
					new ReferenceKey(REFERENCE, REFERENCE_PK),
					new UpsertAttributeMutation(ATTRIBUTE, "foo")
				),
				PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits nothing for a plain wrapped attribute when the primary key is not yet assigned")
		void shouldEmitNothingForPlainAttributeWhenPrimaryKeyIsNull() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.REFERENCE_ATTRIBUTE),
				new ReferenceAttributeMutation(
					new ReferenceKey(REFERENCE, REFERENCE_PK),
					new UpsertAttributeMutation(ATTRIBUTE, "foo")
				),
				null
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits the reference-attribute-delta key for a wrapped delta when the refinement is active")
		void shouldEmitReferenceAttributeDeltaKeyWhenRefinementActive() {
			final List<ConflictKey> keys = keysWithin(
				entityGranular(GranularConflictPolicy.REFERENCE_ATTRIBUTE),
				new ReferenceAttributeMutation(
					new ReferenceKey(REFERENCE, REFERENCE_PK),
					new ApplyDeltaAttributeMutation<>(ATTRIBUTE, 5)
				),
				PK
			);
			assertEquals(
				List.of(new ReferenceAttributeDeltaConflictKey(
					ENTITY, PK, new ReferenceKey(REFERENCE, REFERENCE_PK), new AttributeKey(ATTRIBUTE), 5, null, false
				)),
				keys
			);
		}

		@Test
		@DisplayName("A range-constrained wrapped delta emits its key even when the refinement is inactive, marked shared-surface")
		void shouldEmitReferenceAttributeDeltaKeyForConstrainedDeltaUnderNonePolicy() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.NONE),
				new ReferenceAttributeMutation(
					new ReferenceKey(REFERENCE, REFERENCE_PK),
					new ApplyDeltaAttributeMutation<>(ATTRIBUTE, 5, IntegerNumberRange.between(0, 100))
				),
				PK
			);
			assertEquals(
				List.of(new ReferenceAttributeDeltaConflictKey(
					ENTITY, PK, new ReferenceKey(REFERENCE, REFERENCE_PK), new AttributeKey(ATTRIBUTE), 5,
					IntegerNumberRange.between(0, 100), true
				)),
				keys
			);
		}

		@Test
		@DisplayName("A range-constrained delta on a non-carved-out reference attribute is marked shared-surface, parented by the residual key")
		void shouldEmitSharedSurfaceReferenceAttributeDeltaKeyForNonGranularRangeConstrainedDelta() {
			// REFERENCE_ATTRIBUTE is not refined, so the reference attribute is not carved out; the range
			// guard alone forces emission, and the key must route through the entity's shared surface
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY),
				new ReferenceAttributeMutation(
					new ReferenceKey(REFERENCE, REFERENCE_PK),
					new ApplyDeltaAttributeMutation<>(ATTRIBUTE, 5, IntegerNumberRange.between(0, 100))
				),
				PK
			);
			assertEquals(1, keys.size());
			final ReferenceAttributeDeltaConflictKey key = (ReferenceAttributeDeltaConflictKey) keys.get(0);
			assertTrue(key.sharedSurface());
			assertEquals(new EntityResidualConflictKey(ENTITY, PK), key.parentConflictKey());
		}
	}

	@Nested
	@DisplayName("Set entity scope mutation")
	class SetEntityScopeMutationEmission {

		@Test
		@DisplayName("Emits the full entity key under ENTITY policy, even where every granular refinement is active")
		void shouldEmitFullEntityKeyUnderEntityPolicy() {
			// a scope change is a whole-entity operation, so it must conflict with every carved-out item too -
			// it can no longer free-ride on the coarse fallback, which now only covers the shared surface
			final ConflictGenerationContext context = entityGranular(
				GranularConflictPolicy.ENTITY_ATTRIBUTE,
				GranularConflictPolicy.ASSOCIATED_DATA,
				GranularConflictPolicy.REFERENCE,
				GranularConflictPolicy.REFERENCE_ATTRIBUTE,
				GranularConflictPolicy.PRICE,
				GranularConflictPolicy.HIERARCHY
			);
			final List<ConflictKey> keys = keysWithin(context, new SetEntityScopeMutation(Scope.ARCHIVED), PK);
			assertEquals(List.of(new EntityConflictKey(ENTITY, PK)), keys);
		}

		@Test
		@DisplayName("Emits the collection key under COLLECTION policy")
		void shouldEmitCollectionKeyUnderCollectionPolicy() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.COLLECTION), new SetEntityScopeMutation(Scope.ARCHIVED), PK
			);
			assertEquals(List.of(new CollectionConflictKey(ENTITY)), keys);
		}

		@Test
		@DisplayName("Emits nothing under NONE policy")
		void shouldEmitNothingUnderNonePolicy() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.NONE), new SetEntityScopeMutation(Scope.ARCHIVED), PK
			);
			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName("Emits nothing under ENTITY policy when the primary key is not yet assigned")
		void shouldEmitNothingWhenPrimaryKeyIsNullUnderEntityPolicy() {
			final List<ConflictKey> keys = keysWithin(
				coarse(ConflictPolicy.ENTITY), new SetEntityScopeMutation(Scope.ARCHIVED), null
			);
			assertTrue(keys.isEmpty());
		}
	}
}
