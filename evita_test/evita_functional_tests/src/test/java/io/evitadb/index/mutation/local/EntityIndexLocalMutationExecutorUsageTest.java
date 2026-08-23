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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.mutation.local.dataAccess.EntityStoragePartExistingDataFactory;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.local.dataAccess.ExistingDataSupplierFactory;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityUsage;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

import static io.evitadb.index.mutation.local.AttributeIndexMutator.executeAttributeUpsert;
import static io.evitadb.index.mutation.local.ReferenceIndexMutator.attributeUpdate;
import static io.evitadb.index.mutation.local.ReferenceIndexMutator.referenceInsert;
import static io.evitadb.index.mutation.local.ReferenceIndexMutator.referenceInsertGlobal;
import static io.evitadb.index.mutation.local.ReferenceIndexMutator.referenceRemovalGlobal;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_CZK;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_BASIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the update side of the schema-capability usage counters: that one entity mutation counts **one** update
 * per schema element it touches, however many physical indexes maintaining that element the write fanned out over.
 *
 * The distinction this pins is the whole reason the counting sits in {@link EntityIndexLocalMutationExecutor} rather
 * than in {@link AttributeIndexMutator} — the mutator fires once per (mutation × index), which would measure the
 * fan-out width instead of the workload.
 *
 * Every kind of element a write can file has its own reporting method and therefore its own nest below: attributes and
 * compounds go through `reportAttributeTouched` / `reportSortableCompoundTouched`, a reference's own flags through
 * `reportReferenceTouched`, and the entity's own hierarchy and price flags through `reportEntityCapabilityTouched` -
 * the one of the four deduplicating per **capability** rather than per element, because both entity flags belong to
 * the same element and are written by mutators that know nothing of each other.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EntityIndexLocalMutationExecutor — schema capability usage counting")
@Tag(INDEXING)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class EntityIndexLocalMutationExecutorUsageTest extends AbstractMutatorTestBase {
	/**
	 * Entity attribute that is both filterable and sortable, so a single write must be counted under two
	 * capabilities.
	 */
	private static final String ATTRIBUTE_ORDER = "orderInList";
	/**
	 * Entity attribute carrying no indexing flag at all — nothing about it should ever reach the registry.
	 */
	private static final String ATTRIBUTE_NOTE = "note";
	/**
	 * Compound over an entity attribute and `ean`, rebuilt whenever either of them is written.
	 */
	private static final String COMPOUND_ORDER_WITH_EAN = "orderWithEan";
	/**
	 * Reference attribute used for the reference-container assertions.
	 */
	private static final String ATTRIBUTE_BRAND_ORDER = "brandOrder";
	/**
	 * Histogram declared on the `PARAMETER` reference — the only way an update reaches `BUCKETED`.
	 */
	private static final String HISTOGRAM_PARAMETER_ORDER = "parameterOrder";
	/**
	 * What makes the histogram above a *maintained* one rather than a count-only declaration. `BUCKETED` is gated on
	 * exactly this distinction, at the reporting site and at the two registry sites alike.
	 */
	private static final Expression VALUE_EXPRESSION = ExpressionFactory.parse("$reference.referencedPrimaryKey");
	/**
	 * The price the entity-flag cases write — its identity is irrelevant, only that one indexed price is written.
	 */
	private static final PriceKey PRICE_KEY = new PriceKey(1, PRICE_LIST_BASIC, CURRENCY_CZK);

	/**
	 * Stands in for the two reduced indexes an entity attribute write fans out over. They are deliberately distinct
	 * instances — the point of every assertion below is that the executor counts the element once anyway.
	 */
	@Nonnull private final ReducedEntityIndex firstReducedIndex = reducedIndex(2, 10);
	@Nonnull private final ReducedEntityIndex secondReducedIndex = reducedIndex(3, 20);
	/**
	 * The type-level index the reference-attribute path writes into alongside the reduced index.
	 */
	@Nonnull private final ReferencedTypeEntityIndex brandTypeIndex = new ReferencedTypeEntityIndex(
		4, ENTITY_NAME, new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.DEFAULT_SCOPE, Entities.BRAND)
	);
	/**
	 * A global index in the scope the product schema declares **no** entity-level flag for.
	 */
	@Nonnull private final GlobalEntityIndex archivedProductIndex = new GlobalEntityIndex(
		5, ENTITY_NAME, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.ARCHIVED)
	);

	/**
	 * Builds a reduced entity index for the brand reference and the given referenced entity.
	 *
	 * @param indexPrimaryKey  storage primary key of the index
	 * @param referencedEntity primary key of the referenced brand
	 * @return the index
	 */
	@Nonnull
	private static ReducedEntityIndex reducedIndex(int indexPrimaryKey, int referencedEntity) {
		return new ReducedEntityIndex(
			indexPrimaryKey, ENTITY_NAME,
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.DEFAULT_SCOPE,
				new RepresentativeReferenceKey(new ReferenceKey(Entities.BRAND, referencedEntity))
			)
		);
	}

	@Override
	protected void alterCatalogSchema(@Nonnull CatalogSchemaEditor.CatalogSchemaBuilder schema) {
		// no catalog-level customization required
	}

	@Override
	protected void alterProductSchema(@Nonnull EntitySchemaEditor.EntitySchemaBuilder schema) {
		// the entity's own two flags: the sample schema leaves hierarchy off and already indexes prices in the live
		// scope through `withPriceInCurrency`, so only the first of them has to be turned on here
		schema.withHierarchy();
		schema.withAttribute(ATTRIBUTE_ORDER, Integer.class, whichIs -> whichIs.filterable().sortable());
		schema.withAttribute(ATTRIBUTE_NOTE, String.class);
		schema.withSortableAttributeCompound(
			COMPOUND_ORDER_WITH_EAN,
			new AttributeElement[]{
				AttributeElement.attributeElement(ATTRIBUTE_ORDER),
				AttributeElement.attributeElement(ATTRIBUTE_EAN)
			},
			thatIs -> thatIs.indexedInScope(Scope.LIVE)
		);
		schema.withReferenceTo(
			Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
			thatIs -> thatIs
				.indexedForFilteringAndPartitioning()
				.facetedInScope(Scope.LIVE)
				.withAttribute(
					ATTRIBUTE_BRAND_ORDER, Integer.class, whichIs -> whichIs.filterable().sortable()
				)
		);
		// a second indexed reference, carrying a maintained histogram and no faceting, so `BUCKETED` and `FACETED`
		// are reachable independently rather than only ever together
		schema.withReferenceTo(
			Entities.PARAMETER, Entities.PARAMETER, Cardinality.ZERO_OR_MORE,
			thatIs -> thatIs
				.indexedForFilteringAndPartitioning()
				.bucketedInScope(Scope.LIVE, HISTOGRAM_PARAMETER_ORDER, VALUE_EXPRESSION, null)
		);
		// and one that is not indexed at all — it builds no index, so it may cost no maintenance either
		schema.withReferenceTo(
			Entities.PARAMETER_GROUP, Entities.PARAMETER_GROUP, Cardinality.ZERO_OR_MORE,
			ReferenceSchemaEditor::nonIndexed
		);
	}

	@Nested
	@DisplayName("Attributes")
	class Attributes {

		@Test
		@DisplayName("One update when one attribute write fans out over global and reduced indexes")
		void shouldCountOneUpdateWhenAttributeWriteFansOutOverSeveralIndexes() {
			final ReferenceSchema brandSchema = brandSchema();
			final EntitySchemaAttributeAndCompoundSchemaProvider entityAttributes = entityAttributeProvider();
			final AttributeKey eanKey = new AttributeKey(ATTRIBUTE_EAN);

			// exactly the shape of `AttributeMutationFanOut`: the global index gets the write with no reference schema,
			// every reduced index gets the same entity attribute together with the reference schema owning that index
			upsertInto(globalIndex(), null, entityAttributes, eanKey, "EAN-001");
			upsertInto(firstReducedIndex(), brandSchema, entityAttributes, eanKey, "EAN-001");
			upsertInto(secondReducedIndex(), brandSchema, entityAttributes, eanKey, "EAN-001");

			applyChanges();

			assertUpdatedCount(
				1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE)
			);
			// the reference schema travelling with the reduced-index writes says which index is being maintained,
			// never whose attribute it is — an entity attribute filed under `brand` would be a silently wrong reading
			assertNotTracked(
				SchemaCapabilityKey.referenceAttribute(
					Entities.BRAND, ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
				)
			);
		}

		@Test
		@DisplayName("Each attribute counted once when one mutation writes two of them")
		void shouldCountEachAttributeOnceWhenOneMutationWritesTwo() {
			final EntitySchemaAttributeAndCompoundSchemaProvider entityAttributes = entityAttributeProvider();

			upsertInto(globalIndex(), null, entityAttributes, new AttributeKey(ATTRIBUTE_EAN), "EAN-001");
			upsertInto(globalIndex(), null, entityAttributes, new AttributeKey(ATTRIBUTE_PRIORITY), 5L);

			applyChanges();

			assertUpdatedCount(
				1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE)
			);
			assertUpdatedCount(
				1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_PRIORITY, Capability.SORTABLE, Scope.LIVE)
			);
			// each attribute is counted only under the capabilities it actually declares - the two do not bleed into
			// one another just because one mutation wrote both
			assertNotTracked(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.SORTABLE, Scope.LIVE));
			assertNotTracked(
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_PRIORITY, Capability.FILTERABLE, Scope.LIVE)
			);
		}

		@Test
		@DisplayName("A unique attribute counted under both FILTERABLE and UNIQUE")
		void shouldCountUniqueAttributeUnderFilterableAndUnique() {
			upsertInto(globalIndex(), null, entityAttributeProvider(), new AttributeKey(ATTRIBUTE_CODE), "A");

			applyChanges();

			// `unique()` implies filterability, so dropping the FILTERABLE reading would report a capability queries
			// rely on as unused
			assertUpdatedCount(
				1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTERABLE, Scope.LIVE)
			);
			assertUpdatedCount(1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.UNIQUE, Scope.LIVE));
		}

		@Test
		@DisplayName("Nothing tracked for an attribute the schema does not index")
		void shouldTrackNothingForAttributeTheSchemaDoesNotIndex() {
			upsertInto(globalIndex(), null, entityAttributeProvider(), new AttributeKey(ATTRIBUTE_NOTE), "just a note");

			applyChanges();

			assertNothingTracked("An unindexed attribute costs no maintenance and must be invisible.");
		}

	}

	@Nested
	@DisplayName("Sortable compounds")
	class Compounds {

		@Test
		@DisplayName("A compound counted once under its own key when an attribute write rebuilds it")
		void shouldCountSortableCompoundOnceWhenAttributeWriteRebuildsIt() {
			final EntitySchemaAttributeAndCompoundSchemaProvider entityAttributes = entityAttributeProvider();
			final AttributeKey orderKey = new AttributeKey(ATTRIBUTE_ORDER);

			upsertInto(globalIndex(), null, entityAttributes, orderKey, 5);
			upsertInto(firstReducedIndex(), brandSchema(), entityAttributes, orderKey, 5);

			applyChanges();

			// the compound is rebuilt in both indexes and is still one maintained element, counted under its own name
			// rather than under the attribute whose write triggered the rebuild
			assertUpdatedCount(1, SchemaCapabilityKey.sortableCompound(null, COMPOUND_ORDER_WITH_EAN, Scope.LIVE));
			// and the attribute that triggered it keeps its own two capabilities, counted separately because each can
			// be dropped from the schema on its own
			assertUpdatedCount(
				1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_ORDER, Capability.FILTERABLE, Scope.LIVE)
			);
			assertUpdatedCount(
				1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_ORDER, Capability.SORTABLE, Scope.LIVE)
			);
		}

	}

	@Nested
	@DisplayName("References")
	@Tag(REFERENCE)
	class References {

		@Test
		@DisplayName("A reference attribute counted once, under its reference")
		void shouldCountReferenceAttributeOnceUnderItsReference() {
			final ReferenceKey referenceKey = new ReferenceKey(Entities.BRAND, 10);

			insertReferenceWithItsComponents(referenceKey);
			// one reference attribute mutation, written into the type index and the reduced index alike
			updateBrandAttribute(referenceKey, 7);

			applyChanges();

			assertUpdatedCount(
				1,
				SchemaCapabilityKey.referenceAttribute(
					Entities.BRAND, ATTRIBUTE_BRAND_ORDER, Capability.FILTERABLE, Scope.LIVE
				)
			);
			assertUpdatedCount(
				1,
				SchemaCapabilityKey.referenceAttribute(
					Entities.BRAND, ATTRIBUTE_BRAND_ORDER, Capability.SORTABLE, Scope.LIVE
				)
			);
			// the reference declares it, so the entity must not
			assertNotTracked(
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_BRAND_ORDER, Capability.FILTERABLE, Scope.LIVE)
			);
		}

		@Test
		@DisplayName("A reference write counts the flags the reference declares on itself")
		void shouldCountOneUpdateForTheReferenceItself() {
			insertReferenceGlobally(brandSchema(), 10);

			applyChanges();

			// `indexed()` needs no flag test of its own - reaching the site at all means the reduced index family
			// exists - while `faceted()` is tested separately, being a cost carried on top of it
			assertUpdatedCount(1, SchemaCapabilityKey.reference(Entities.BRAND, Capability.INDEXED, Scope.LIVE));
			assertUpdatedCount(1, SchemaCapabilityKey.reference(Entities.BRAND, Capability.FACETED, Scope.LIVE));
			// the reference is the element here rather than the container, and it declares no histogram
			assertNotTracked(SchemaCapabilityKey.reference(Entities.BRAND, Capability.BUCKETED, Scope.LIVE));
		}

		@Test
		@DisplayName("A maintained histogram is what makes a reference write count BUCKETED")
		void shouldCountBucketedForAReferenceDeclaringAMaintainedHistogram() {
			insertReferenceGlobally(referenceSchema(Entities.PARAMETER), 30);

			applyChanges();

			assertUpdatedCount(1, SchemaCapabilityKey.reference(Entities.PARAMETER, Capability.INDEXED, Scope.LIVE));
			assertUpdatedCount(1, SchemaCapabilityKey.reference(Entities.PARAMETER, Capability.BUCKETED, Scope.LIVE));
			// `bucketed()` and `faceted()` are independent flags and this reference declares only one of them
			assertNotTracked(SchemaCapabilityKey.reference(Entities.PARAMETER, Capability.FACETED, Scope.LIVE));
		}

		@Test
		@DisplayName("Several references of the same name in one mutation are one update")
		void shouldCountOneUpdateForSeveralReferencesOfTheSameName() {
			// one upsert routinely writes several references of the same name, one per referenced entity, and every
			// one of them maintains the very same schema flags - counting them apart would report the cardinality of
			// the relation instead of the number of mutations that paid for it
			insertReferenceGlobally(brandSchema(), 10);
			insertReferenceGlobally(brandSchema(), 20);

			applyChanges();

			assertUpdatedCount(1, SchemaCapabilityKey.reference(Entities.BRAND, Capability.INDEXED, Scope.LIVE));
			assertUpdatedCount(1, SchemaCapabilityKey.reference(Entities.BRAND, Capability.FACETED, Scope.LIVE));
		}

		@Test
		@DisplayName("A reference removal counts the same way an insert does")
		void shouldCountAReferenceRemovalTheSameWayAsAnInsert() {
			// the insert and the removal are two separate reporting sites costing the same index maintenance: a flag
			// whose only traffic is entities losing the reference is still a flag in use. Driven against the
			// non-faceted reference on purpose - unindexing a *facet* reads the persisted reference back, which this
			// fixture's storage mock does not hold, and the reporting site being pinned is the same one either way
			removeReferenceGlobally(referenceSchema(Entities.PARAMETER), 30);

			applyChanges();

			assertUpdatedCount(1, SchemaCapabilityKey.reference(Entities.PARAMETER, Capability.INDEXED, Scope.LIVE));
			assertUpdatedCount(1, SchemaCapabilityKey.reference(Entities.PARAMETER, Capability.BUCKETED, Scope.LIVE));
		}

		@Test
		@DisplayName("Nothing tracked for a reference the schema does not index")
		void shouldTrackNothingForAReferenceTheSchemaDoesNotIndex() {
			insertReferenceGlobally(referenceSchema(Entities.PARAMETER_GROUP), 40);

			applyChanges();

			assertNothingTracked("An unindexed reference builds no index at all and must therefore be invisible.");
		}

	}

	@Nested
	@DisplayName("The flags the entity declares on itself")
	@Tag(HIERARCHY)
	@Tag(PRICE)
	class EntityOwnFlags {

		@Test
		@DisplayName("Hierarchy and prices counted separately when one mutation touches both")
		void shouldCountHierarchyAndPriceSeparatelyInOneMutation() {
			// both flags belong to the same element - the entity - so an element-level deduplication would let
			// whichever mutator ran first swallow the other. This is the case that goes red if `TouchedSchemaElement`
			// ever loses the capability it carries alongside the element's identity
			placeInHierarchy(globalIndex());
			upsertPrice(globalIndex());

			applyChanges();

			assertUpdatedCount(1, SchemaCapabilityKey.entity(entityType(), Capability.HIERARCHICAL, Scope.LIVE));
			assertUpdatedCount(1, SchemaCapabilityKey.entity(entityType(), Capability.PRICED, Scope.LIVE));
		}

		@Test
		@DisplayName("One count when one mutation moves the entity in the hierarchy twice")
		void shouldCountHierarchyOnceWhenOneMutationTouchesItTwice() {
			// the per-capability deduplication in its own right: a placement and its removal are two reporting sites
			// of one flag, and a mutation reaching both of them is still one mutation
			placeInHierarchy(globalIndex());
			HierarchyPlacementMutator.removeParent(executor(), globalIndex(), 1);

			applyChanges();

			assertUpdatedCount(1, SchemaCapabilityKey.entity(entityType(), Capability.HIERARCHICAL, Scope.LIVE));
		}

		@Test
		@DisplayName("Nothing tracked for an entity flag the schema does not declare in that scope")
		void shouldTrackNothingForAnEntityFlagTheSchemaDoesNotDeclareInThatScope() {
			// the entity's own flags are the only ones whose reporting method tests the schema itself, so an index in
			// a scope the schema leaves undeclared is the only place that test can be observed
			placeInHierarchy(archivedProductIndex());

			applyChanges();

			assertNothingTracked(
				"A scope the schema declares no hierarchy indexing for was counted as maintained."
			);
		}

		@Test
		@DisplayName("A capability no entity can carry is rejected rather than counted")
		void shouldRejectACapabilityNoEntityCanCarry() {
			// two mutators reach this method and neither can pass anything but the two entity flags, so nothing else
			// asserts the arm that keeps a contained element's flag from being filed against its container
			assertThrows(
				GenericEvitaInternalError.class,
				() -> executor().reportEntityCapabilityTouched(Capability.FILTERABLE, Scope.LIVE)
			);
		}

	}

	@Nested
	@DisplayName("The moment the work is counted")
	class Deduplication {

		@Test
		@DisplayName("The work counted before the commit-or-rollback decision, and only once")
		void shouldCountTheWorkBeforeTheCommitOrRollbackDecision() {
			upsertInto(globalIndex(), null, entityAttributeProvider(), new AttributeKey(ATTRIBUTE_EAN), "EAN-001");

			// `LocalMutationExecutorCollector#finish` calls applyChanges() before it decides between commit and
			// rollback, so a mutation the transaction later reverts has still been counted — the maintenance was
			// performed either way, exactly as for the per-index update counters
			applyChanges();
			final SchemaCapabilityKey key = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_EAN, Capability.FILTERABLE, Scope.LIVE
			);
			assertUpdatedCount(1, key);

			// the accumulator empties on flush, so a repeated finalization cannot count the same work twice
			applyChanges();
			assertUpdatedCount(1, key);
		}

	}

	/**
	 * Applies an attribute upsert to a single index, the way the fan-out applies it to each index in turn.
	 *
	 * @param targetIndex     the index receiving the write
	 * @param referenceSchema the reference owning `targetIndex`, or null for the global index — deliberately
	 *                        independent of which schema declares the attribute
	 * @param schemaProvider  the schema the attribute is declared in
	 * @param attributeKey    the attribute being written
	 * @param value           the value to write
	 */
	private void upsertInto(
		@Nonnull EntityIndex targetIndex,
		@Nullable ReferenceSchema referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider schemaProvider,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Serializable value
	) {
		targetIndex.insertPrimaryKeyIfMissing(1);
		executeAttributeUpsert(
			this.executor, referenceSchema, schemaProvider, entityAttributeValueSupplier(1),
			targetIndex, targetIndex, attributeKey, value,
			false, true
		);
	}

	/**
	 * Drives the global half of a reference insert — the one running exactly once per reference, and the site the
	 * reference's own flags are reported from.
	 *
	 * @param referenceSchema  the reference being written
	 * @param referencedEntity primary key of the referenced entity
	 */
	private void insertReferenceGlobally(@Nonnull ReferenceSchemaContract referenceSchema, int referencedEntity) {
		referenceInsertGlobal(
			1, referenceSchema, this.productIndex,
			new ReferenceKey(referenceSchema.getName(), referencedEntity), null, this.executor
		);
	}

	/**
	 * Drives the removal counterpart of {@link #insertReferenceGlobally}.
	 *
	 * @param referenceSchema  the reference being removed
	 * @param referencedEntity primary key of the referenced entity
	 */
	private void removeReferenceGlobally(@Nonnull ReferenceSchemaContract referenceSchema, int referencedEntity) {
		referenceRemovalGlobal(
			1, referenceSchema, this.productIndex,
			new ReferenceKey(referenceSchema.getName(), referencedEntity), this.executor
		);
	}

	/**
	 * Inserts one brand reference the way a full upsert does — the global half together with the per-component one
	 * that writes the type index and the reduced index.
	 *
	 * @param referenceKey the reference being written
	 */
	private void insertReferenceWithItsComponents(@Nonnull ReferenceKey referenceKey) {
		referenceInsert(
			1, this.productSchema, brandSchema(), this.executor,
			this.productIndex, this.brandTypeIndex, this.firstReducedIndex,
			referenceKey, null, supplierFactory(1)
		);
	}

	/**
	 * Writes one attribute of the brand reference into the type index and the reduced index alike.
	 *
	 * @param referenceKey the reference owning the attribute
	 * @param value        the value to write
	 */
	private void updateBrandAttribute(@Nonnull ReferenceKey referenceKey, int value) {
		attributeUpdate(
			this.executor, supplierFactory(1), this.brandTypeIndex,
			this.firstReducedIndex, this.firstReducedIndex,
			brandSchema(), referenceKey,
			new UpsertAttributeMutation(new AttributeKey(ATTRIBUTE_BRAND_ORDER), value)
		);
	}

	/**
	 * Places the mutated entity at the root of the hierarchy of the given index.
	 *
	 * @param targetIndex the index whose hierarchy is written
	 */
	private void placeInHierarchy(@Nonnull EntityIndex targetIndex) {
		HierarchyPlacementMutator.setParent(this.executor, targetIndex, 1, null);
	}

	/**
	 * Writes one brand-new indexed price into the given index.
	 *
	 * @param targetIndex the index receiving the price
	 */
	private void upsertPrice(@Nonnull EntityIndex targetIndex) {
		targetIndex.insertPrimaryKeyIfMissing(1);
		PriceIndexMutator.priceUpsert(
			this.executor, null, targetIndex, PRICE_KEY, null, null,
			BigDecimal.ONE, BigDecimal.ONE, true, null,
			PriceInnerRecordHandling.NONE,
			(priceKey, innerRecordId) -> 1
		);
	}

	/**
	 * Flushes what the mutation accumulated onto the registry's holders.
	 */
	private void applyChanges() {
		this.executor.applyChanges();
	}

	/**
	 * @return the executor under test — reached from the nested classes, which have no `this` of their own for it
	 */
	@Nonnull
	private EntityIndexLocalMutationExecutor executor() {
		return this.executor;
	}

	/**
	 * The entity type as the **schema** spells it, which is not what the fixture's indexes are named after - the two
	 * differ in case, and an entity-level key built from the index's name would never match what a write filed.
	 *
	 * @return the entity type the executor files its entity-level rows under
	 */
	@Nonnull
	private String entityType() {
		return this.productSchema.getName();
	}

	/**
	 * @return the live global index every case but one writes into
	 */
	@Nonnull
	private GlobalEntityIndex globalIndex() {
		return this.productIndex;
	}

	/**
	 * @return the global index of the scope the schema declares no entity-level flag for
	 */
	@Nonnull
	private GlobalEntityIndex archivedProductIndex() {
		return this.archivedProductIndex;
	}

	/**
	 * @return the first of the two reduced indexes an entity attribute write fans out over
	 */
	@Nonnull
	private ReducedEntityIndex firstReducedIndex() {
		return this.firstReducedIndex;
	}

	/**
	 * @return the second of the two reduced indexes an entity attribute write fans out over
	 */
	@Nonnull
	private ReducedEntityIndex secondReducedIndex() {
		return this.secondReducedIndex;
	}

	/**
	 * @return the reference the attribute and facet cases are written against
	 */
	@Nonnull
	private ReferenceSchema brandSchema() {
		return this.productSchema.getReferenceOrThrowException(Entities.BRAND);
	}

	/**
	 * Looks one of the fixture's references up by name.
	 *
	 * @param referenceName name of the reference
	 * @return its schema
	 */
	@Nonnull
	private ReferenceSchema referenceSchema(@Nonnull String referenceName) {
		return this.productSchema.getReferenceOrThrowException(referenceName);
	}

	/**
	 * @return the provider that resolves an attribute name against the entity's own schema
	 */
	@Nonnull
	private EntitySchemaAttributeAndCompoundSchemaProvider entityAttributeProvider() {
		return new EntitySchemaAttributeAndCompoundSchemaProvider(this.productSchema);
	}

	/**
	 * Supplies pre-mutation entity attribute values out of the mock storage container.
	 *
	 * @param entityPrimaryKey the primary key of the mutated entity
	 * @return the supplier
	 */
	@Nonnull
	private ExistingAttributeValueSupplier entityAttributeValueSupplier(int entityPrimaryKey) {
		return supplierFactory(entityPrimaryKey).getEntityAttributeValueSupplier();
	}

	/**
	 * Builds the existing-data factory the reference paths need — they read reference attributes out of the
	 * references storage part rather than the entity's own.
	 *
	 * @param entityPrimaryKey the primary key of the mutated entity
	 * @return the factory
	 */
	@Nonnull
	private ExistingDataSupplierFactory supplierFactory(int entityPrimaryKey) {
		return new EntityStoragePartExistingDataFactory(
			this.executor.getContainerAccessor(), this.productSchema, entityPrimaryKey, Map.of()
		);
	}

	/**
	 * Asserts that the registry holds the given capability with exactly the expected number of updates.
	 *
	 * @param expected the expected update count
	 * @param key      the capability to look up
	 */
	private void assertUpdatedCount(long expected, @Nonnull SchemaCapabilityKey key) {
		final SchemaCapabilityUsage usage = findUsage(key);
		assertNotNull(usage, "No usage entry was recorded for " + key + ".");
		assertEquals(expected, usage.getUpdatedCount(), "Unexpected update count for " + key + ".");
	}

	/**
	 * Asserts that the registry holds no entry for the given capability at all.
	 *
	 * @param key the capability that must be absent
	 */
	private void assertNotTracked(@Nonnull SchemaCapabilityKey key) {
		assertNull(findUsage(key), "Nothing should have been recorded for " + key + ".");
	}

	/**
	 * Asserts the mutation filed nothing whatsoever — the shape every "the schema does not declare this" case takes,
	 * because an unindexed element must not even mint an entry.
	 *
	 * @param message what it means if something was filed
	 */
	private void assertNothingTracked(@Nonnull String message) {
		assertEquals(0, this.usageRegistry.size(), message);
	}

	/**
	 * Looks a holder up **without** resolving it, because resolving would create the very entry an absence assertion
	 * is checking for.
	 *
	 * @param key the capability to look for
	 * @return the holder, or null when the registry holds none
	 */
	@Nullable
	private SchemaCapabilityUsage findUsage(@Nonnull SchemaCapabilityKey key) {
		for (final UsageEntry entry : this.usageRegistry.listUsages()) {
			if (entry.key().equals(key)) {
				return entry.usage();
			}
		}
		return null;
	}

}
