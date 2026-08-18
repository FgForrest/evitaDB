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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.mutation.local.dataAccess.EntityStoragePartExistingDataFactory;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.index.mutation.local.dataAccess.ExistingDataSupplierFactory;
import io.evitadb.index.usage.SchemaCapabilityKey;
import io.evitadb.index.usage.SchemaCapabilityKey.Capability;
import io.evitadb.index.usage.SchemaCapabilityUsage;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;

import static io.evitadb.index.mutation.local.AttributeIndexMutator.executeAttributeUpsert;
import static io.evitadb.index.mutation.local.ReferenceIndexMutator.attributeUpdate;
import static io.evitadb.index.mutation.local.ReferenceIndexMutator.referenceInsert;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the update side of the schema-capability usage counters: that one entity mutation counts **one** update
 * per schema element it touches, however many physical indexes maintaining that element the write fanned out over.
 *
 * The distinction this pins is the whole reason the counting sits in {@link EntityIndexLocalMutationExecutor} rather
 * than in {@link AttributeIndexMutator} — the mutator fires once per (mutation × index), which would measure the
 * fan-out width instead of the workload.
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
				.withAttribute(
					ATTRIBUTE_BRAND_ORDER, Integer.class, whichIs -> whichIs.filterable().sortable()
				)
		);
	}

	@Test
	@DisplayName("Should count one update when one attribute write fans out over global and reduced indexes")
	void shouldCountOneUpdateWhenAttributeWriteFansOutOverSeveralIndexes() {
		final ReferenceSchema brandSchema = this.productSchema.getReferenceOrThrowException(Entities.BRAND);
		final EntitySchemaAttributeAndCompoundSchemaProvider entityAttributes =
			new EntitySchemaAttributeAndCompoundSchemaProvider(this.productSchema);
		final AttributeKey eanKey = new AttributeKey(ATTRIBUTE_EAN);

		// exactly the shape of `AttributeMutationFanOut`: the global index gets the write with no reference schema,
		// every reduced index gets the same entity attribute together with the reference schema owning that index
		upsertInto(this.productIndex, null, entityAttributes, eanKey, "EAN-001");
		upsertInto(this.firstReducedIndex, brandSchema, entityAttributes, eanKey, "EAN-001");
		upsertInto(this.secondReducedIndex, brandSchema, entityAttributes, eanKey, "EAN-001");

		this.executor.applyChanges();

		assertUpdatedCount(1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE));
		// the reference schema travelling with the reduced-index writes says which index is being maintained, never
		// whose attribute it is — an entity attribute filed under `brand` would be a silently wrong reading
		assertNotTracked(
			SchemaCapabilityKey.referenceAttribute(Entities.BRAND, ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE)
		);
	}

	@Test
	@DisplayName("Should count each attribute once when one mutation writes two of them")
	void shouldCountEachAttributeOnceWhenOneMutationWritesTwo() {
		final EntitySchemaAttributeAndCompoundSchemaProvider entityAttributes =
			new EntitySchemaAttributeAndCompoundSchemaProvider(this.productSchema);

		upsertInto(this.productIndex, null, entityAttributes, new AttributeKey(ATTRIBUTE_EAN), "EAN-001");
		upsertInto(this.productIndex, null, entityAttributes, new AttributeKey(ATTRIBUTE_PRIORITY), 5L);

		this.executor.applyChanges();

		assertUpdatedCount(1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE));
		assertUpdatedCount(1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_PRIORITY, Capability.SORT, Scope.LIVE));
		// each attribute is counted only under the capabilities it actually declares - the two do not bleed into
		// one another just because one mutation wrote both
		assertNotTracked(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_EAN, Capability.SORT, Scope.LIVE));
		assertNotTracked(SchemaCapabilityKey.entityAttribute(ATTRIBUTE_PRIORITY, Capability.FILTER, Scope.LIVE));
	}

	@Test
	@DisplayName("Should count a unique attribute under both FILTER and UNIQUE")
	void shouldCountUniqueAttributeUnderFilterAndUnique() {
		upsertInto(
			this.productIndex, null, new EntitySchemaAttributeAndCompoundSchemaProvider(this.productSchema),
			new AttributeKey(ATTRIBUTE_CODE), "A"
		);

		this.executor.applyChanges();

		// `unique()` implies filterability, so dropping the FILTER reading would report a capability queries rely on
		// as unused
		assertUpdatedCount(1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.FILTER, Scope.LIVE));
		assertUpdatedCount(1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_CODE, Capability.UNIQUE, Scope.LIVE));
	}

	@Test
	@DisplayName("Should count a sortable compound once under its own key when an attribute write rebuilds it")
	void shouldCountSortableCompoundOnceWhenAttributeWriteRebuildsIt() {
		final ReferenceSchema brandSchema = this.productSchema.getReferenceOrThrowException(Entities.BRAND);
		final EntitySchemaAttributeAndCompoundSchemaProvider entityAttributes =
			new EntitySchemaAttributeAndCompoundSchemaProvider(this.productSchema);
		final AttributeKey orderKey = new AttributeKey(ATTRIBUTE_ORDER);

		upsertInto(this.productIndex, null, entityAttributes, orderKey, 5);
		upsertInto(this.firstReducedIndex, brandSchema, entityAttributes, orderKey, 5);

		this.executor.applyChanges();

		// the compound is rebuilt in both indexes and is still one maintained element, counted under its own name
		// rather than under the attribute whose write triggered the rebuild
		assertUpdatedCount(1, SchemaCapabilityKey.sortableCompound(null, COMPOUND_ORDER_WITH_EAN, Scope.LIVE));
		// and the attribute that triggered it keeps its own two capabilities, counted separately because each can be
		// dropped from the schema on its own
		assertUpdatedCount(1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_ORDER, Capability.FILTER, Scope.LIVE));
		assertUpdatedCount(1, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_ORDER, Capability.SORT, Scope.LIVE));
	}

	@Test
	@DisplayName("Should count a reference attribute once, under its reference")
	void shouldCountReferenceAttributeOnceUnderItsReference() {
		final ReferenceKey referenceKey = new ReferenceKey(Entities.BRAND, 10);
		final ReferenceSchema brandSchema = this.productSchema.getReferenceOrThrowException(Entities.BRAND);
		final ExistingDataSupplierFactory supplierFactory = supplierFactory(1);

		referenceInsert(
			1, this.productSchema, brandSchema, this.executor,
			this.productIndex, this.brandTypeIndex, this.firstReducedIndex,
			referenceKey, null, supplierFactory
		);
		// one reference attribute mutation, written into the type index and the reduced index alike
		attributeUpdate(
			this.executor, supplierFactory, this.brandTypeIndex,
			this.firstReducedIndex, this.firstReducedIndex,
			brandSchema, referenceKey,
			new UpsertAttributeMutation(new AttributeKey(ATTRIBUTE_BRAND_ORDER), 7)
		);

		this.executor.applyChanges();

		assertUpdatedCount(
			1,
			SchemaCapabilityKey.referenceAttribute(
				Entities.BRAND, ATTRIBUTE_BRAND_ORDER, Capability.FILTER, Scope.LIVE
			)
		);
		assertUpdatedCount(
			1,
			SchemaCapabilityKey.referenceAttribute(
				Entities.BRAND, ATTRIBUTE_BRAND_ORDER, Capability.SORT, Scope.LIVE
			)
		);
		// the reference declares it, so the entity must not
		assertNotTracked(
			SchemaCapabilityKey.entityAttribute(ATTRIBUTE_BRAND_ORDER, Capability.FILTER, Scope.LIVE)
		);
	}

	@Test
	@DisplayName("Should track nothing for an attribute the schema does not index")
	void shouldTrackNothingForAttributeTheSchemaDoesNotIndex() {
		upsertInto(
			this.productIndex, null, new EntitySchemaAttributeAndCompoundSchemaProvider(this.productSchema),
			new AttributeKey(ATTRIBUTE_NOTE), "just a note"
		);

		this.executor.applyChanges();

		assertEquals(
			0, this.usageRegistry.size(), "An unindexed attribute costs no maintenance and must be invisible."
		);
	}

	@Test
	@DisplayName("Should count the work before the commit-or-rollback decision, and only once")
	void shouldCountTheWorkBeforeTheCommitOrRollbackDecision() {
		upsertInto(
			this.productIndex, null, new EntitySchemaAttributeAndCompoundSchemaProvider(this.productSchema),
			new AttributeKey(ATTRIBUTE_EAN), "EAN-001"
		);

		// `LocalMutationExecutorCollector#finish` calls applyChanges() before it decides between commit and rollback,
		// so a mutation the transaction later reverts has still been counted — the maintenance was performed either
		// way, exactly as for the per-index update counters
		this.executor.applyChanges();
		final SchemaCapabilityKey key = SchemaCapabilityKey.entityAttribute(
			ATTRIBUTE_EAN, Capability.FILTER, Scope.LIVE
		);
		assertUpdatedCount(1, key);

		// the accumulator empties on flush, so a repeated finalization cannot count the same work twice
		this.executor.applyChanges();
		assertUpdatedCount(1, key);
	}

	/**
	 * Applies an attribute upsert to a single index, the way the fan-out applies it to each index in turn.
	 *
	 * @param targetIndex       the index receiving the write
	 * @param referenceSchema   the reference owning `targetIndex`, or null for the global index — deliberately
	 *                          independent of which schema declares the attribute
	 * @param schemaProvider    the schema the attribute is declared in
	 * @param attributeKey      the attribute being written
	 * @param value             the value to write
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
			this.executor, referenceSchema, schemaProvider, entityAttributeValueSupplier(this.productSchema, 1),
			targetIndex, targetIndex, attributeKey, value,
			false, true
		);
	}

	/**
	 * Supplies pre-mutation entity attribute values out of the mock storage container.
	 *
	 * @param entitySchema     the schema of the mutated entity
	 * @param entityPrimaryKey the primary key of the mutated entity
	 * @return the supplier
	 */
	@Nonnull
	private ExistingAttributeValueSupplier entityAttributeValueSupplier(
		@Nonnull EntitySchema entitySchema,
		int entityPrimaryKey
	) {
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
