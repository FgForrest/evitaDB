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

package io.evitadb.index.mutation.index;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.mutation.DependencyType;
import io.evitadb.index.mutation.EntityIndexMutation;
import io.evitadb.index.mutation.ExpressionIndexTrigger;
import io.evitadb.index.mutation.FacetExpressionTrigger;
import io.evitadb.index.mutation.IndexImplicitMutations;
import io.evitadb.index.mutation.IndexMutation;
import io.evitadb.index.mutation.ReevaluateFacetExpressionMutation;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the source-side detection logic in
 * {@link EntityIndexLocalMutationExecutor#popIndexImplicitMutations}.
 * Verifies that attribute changes and entity removals produce correct
 * {@link ReevaluateFacetExpressionMutation} envelopes for cross-entity dispatch.
 *
 * The detection is based on iterating `inputMutations` for {@link AttributeMutation}
 * instances — no old-vs-new value comparison is performed (safe over-firing; target-side
 * executor handles idempotency).
 *
 * Uses real objects throughout — only {@link ExpressionIndexTrigger} uses a simple test record
 * because the real `FacetExpressionTriggerImpl` requires a parsed Expression AST and
 * Byte Buddy proxy descriptors.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EntityIndexLocalMutationExecutor — source-side trigger detection")
class EntityIndexLocalMutationExecutorTriggerTest {

	private static final String SOURCE_ENTITY_TYPE = "parameterGroup";
	private static final String TARGET_ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "parameter";
	private static final int ENTITY_PK = 99;

	/**
	 * Simple test implementation of {@link ExpressionIndexTrigger}. Provides the minimum contract
	 * needed for source-side detection without requiring Expression AST or Byte Buddy proxies.
	 *
	 * @param ownerEntityType    target entity type (e.g., "product")
	 * @param referenceName      reference carrying the expression (e.g., "parameter")
	 * @param scope              scope of the expression
	 * @param mutatedEntityType  source entity type whose changes fire this trigger
	 * @param dependencyType     how the source relates to the owner
	 * @param dependentAttributes attribute names the expression reads on the source entity
	 */
	private record TestTrigger(
		@Nonnull String ownerEntityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull String mutatedEntityType,
		@Nonnull DependencyType dependencyType,
		@Nonnull Set<String> dependentAttributes
	) implements ExpressionIndexTrigger {

		@Nonnull
		@Override
		public String getOwnerEntityType() {
			return this.ownerEntityType;
		}

		@Nonnull
		@Override
		public String getReferenceName() {
			return this.referenceName;
		}

		@Nonnull
		@Override
		public Scope getScope() {
			return this.scope;
		}

		@Nullable
		@Override
		public String getMutatedEntityType() {
			return this.mutatedEntityType;
		}

		@Nonnull
		@Override
		public DependencyType getDependencyType() {
			return this.dependencyType;
		}

		@Nullable
		@Override
		public String getDependentReferenceName() {
			return null;
		}

		@Nonnull
		@Override
		public Set<String> getDependentAttributes() {
			return this.dependentAttributes;
		}

		@Nonnull
		@Override
		public FilterBy getFilterByConstraint() {
			throw new UnsupportedOperationException("Not needed for source-side detection tests.");
		}

		@Override
		public boolean evaluate(
			int ownerEntityPK,
			@Nonnull ReferenceKey referenceKey,
			@Nonnull WritableEntityStorageContainerAccessor storageAccessor,
			@Nonnull Function<String, EntitySchemaContract> schemaResolver
		) {
			throw new UnsupportedOperationException("Not needed for source-side detection tests.");
		}
	}

	/** Attribute schema for the `inputWidgetType` attribute. */
	private static final EntityAttributeSchema INPUT_WIDGET_TYPE_SCHEMA =
		EntityAttributeSchema._internalBuild("inputWidgetType", String.class, false);
	/** Attribute schema for the `status` attribute. */
	private static final EntityAttributeSchema STATUS_SCHEMA =
		EntityAttributeSchema._internalBuild("status", String.class, false);
	/** Attribute schema for the `priority` attribute. */
	private static final EntityAttributeSchema PRIORITY_SCHEMA =
		EntityAttributeSchema._internalBuild("priority", Integer.class, false);
	/** Attribute schema for the `code` attribute. */
	private static final EntityAttributeSchema CODE_SCHEMA =
		EntityAttributeSchema._internalBuild("code", String.class, false);
	/** Localized attribute schema for the `name` attribute. */
	private static final EntityAttributeSchema NAME_SCHEMA =
		EntityAttributeSchema._internalBuild("name", String.class, true);

	/**
	 * Builds a schema with all test attribute definitions so that `applyMutation()` does not throw
	 * on unknown attributes.
	 */
	@Nonnull
	private static EntitySchema buildSourceSchema() {
		final Map<String, EntityAttributeSchemaContract> attributes = Map.of(
			"inputWidgetType", INPUT_WIDGET_TYPE_SCHEMA,
			"status", STATUS_SCHEMA,
			"priority", PRIORITY_SCHEMA,
			"code", CODE_SCHEMA,
			"name", NAME_SCHEMA
		);
		return EntitySchema._internalBuild(
			1, SOURCE_ENTITY_TYPE,
			null, null,
			false, false, null,
			false, null, 2,
			Set.of(Locale.ENGLISH),
			Collections.emptySet(),
			attributes,
			Collections.emptyMap(),
			Collections.emptyMap(),
			EnumSet.allOf(EvolutionMode.class),
			Collections.emptyMap()
		);
	}

	/**
	 * Creates a minimal {@link EntityIndexLocalMutationExecutor} with a real
	 * {@link MockStorageContainerAccessor} and {@link GlobalEntityIndex}, using the default
	 * {@link #ENTITY_PK}.
	 *
	 * @param containerAccessor pre-configured storage accessor
	 * @param registrySupplier trigger registry supplier, or null to test the null-supplier path
	 * @return a new executor ready for attribute mutation testing
	 */
	@Nonnull
	private static EntityIndexLocalMutationExecutor createExecutor(
		@Nonnull MockStorageContainerAccessor containerAccessor,
		@Nullable Supplier<CatalogExpressionTriggerRegistry> registrySupplier
	) {
		return createExecutor(containerAccessor, registrySupplier, ENTITY_PK);
	}

	/**
	 * Creates a minimal {@link EntityIndexLocalMutationExecutor} with a real
	 * {@link MockStorageContainerAccessor} and {@link GlobalEntityIndex}, using a custom entity PK.
	 *
	 * @param containerAccessor pre-configured storage accessor
	 * @param registrySupplier trigger registry supplier, or null to test the null-supplier path
	 * @param entityPK         the primary key for the entity being mutated
	 * @return a new executor ready for attribute mutation testing
	 */
	@Nonnull
	private static EntityIndexLocalMutationExecutor createExecutor(
		@Nonnull MockStorageContainerAccessor containerAccessor,
		@Nullable Supplier<CatalogExpressionTriggerRegistry> registrySupplier,
		int entityPK
	) {
		final EntitySchema schema = buildSourceSchema();
		final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
			1, SOURCE_ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL)
		);
		final CatalogIndex catalogIndex = new CatalogIndex(Scope.LIVE);
		final AtomicInteger sequencer = new AtomicInteger(1);
		return new EntityIndexLocalMutationExecutor(
			containerAccessor, entityPK,
			new MockEntityIndexCreator<>(globalIndex),
			new MockEntityIndexCreator<>(catalogIndex),
			() -> schema,
			sequencer::getAndIncrement,
			false,
			() -> { throw new UnsupportedOperationException("Not used in trigger test."); },
			registrySupplier,
			null,
			null
		);
	}

	/**
	 * Pre-loads an attribute into the mock container accessor's non-localized storage part.
	 *
	 * @param accessor  the storage accessor to populate
	 * @param schema    attribute schema definition
	 * @param attrName  attribute name
	 * @param value     initial attribute value
	 */
	private static void preLoadAttribute(
		@Nonnull MockStorageContainerAccessor accessor,
		@Nonnull EntityAttributeSchema schema,
		@Nonnull String attrName,
		@Nonnull Serializable value
	) {
		accessor.getAttributeStoragePart(SOURCE_ENTITY_TYPE, ENTITY_PK)
			.upsertAttribute(
				new AttributeKey(attrName),
				schema,
				existing -> new AttributeValue(new AttributeKey(attrName), value)
			);
	}

	/**
	 * Builds a real {@link CatalogExpressionTriggerRegistry} from the given triggers.
	 * Uses the public `rebuildForEntityType` API which is the same path as production code.
	 *
	 * @param ownerEntityType the entity type that owns the triggers
	 * @param triggers        the triggers to register
	 * @return a populated registry
	 */
	@Nonnull
	private static CatalogExpressionTriggerRegistry buildRegistry(
		@Nonnull String ownerEntityType,
		@Nonnull ExpressionIndexTrigger... triggers
	) {
		return CatalogExpressionTriggerRegistry.EMPTY
			.rebuildForEntityType(ownerEntityType, List.of(triggers));
	}

	// --- Basic trigger firing ---

	@Test
	@DisplayName("Should return mutation when attribute value changes")
	void shouldReturnMutationWhenAttributeValueChanges() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(1, result.indexMutations().length);
		final EntityIndexMutation envelope = result.indexMutations()[0];
		assertEquals(TARGET_ENTITY_TYPE, envelope.entityType());
		assertEquals(1, envelope.mutations().length);
		final ReevaluateFacetExpressionMutation facetMutation =
			(ReevaluateFacetExpressionMutation) envelope.mutations()[0];
		assertEquals(REFERENCE_NAME, facetMutation.referenceName());
		assertEquals(ENTITY_PK, facetMutation.mutatedEntityPK());
		assertEquals(DependencyType.GROUP_ENTITY_ATTRIBUTE, facetMutation.dependencyType());
		assertEquals(Scope.LIVE, facetMutation.scope());
	}

	@Test
	@DisplayName("Should fire for attribute creation (new attribute, no old value)")
	void shouldFireForAttributeCreation() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		// no pre-loaded attribute

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "CHECKBOX");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(1, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should fire for attribute removal")
	void shouldFireForAttributeRemoval() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final RemoveAttributeMutation mutation = new RemoveAttributeMutation("inputWidgetType");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(1, result.indexMutations().length);
	}

	// --- Delta mutations ---

	@Test
	@DisplayName("Should fire for delta attribute mutation")
	void shouldFireForDeltaAttributeMutation() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, PRIORITY_SCHEMA, "priority", 5);

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("priority")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final ApplyDeltaAttributeMutation<Integer> mutation = new ApplyDeltaAttributeMutation<>("priority", 3);
		executor.applyMutation(mutation);
		final List<LocalMutation<?, ?>> mutations = List.of(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(mutations);

		assertEquals(1, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should fire for zero delta attribute mutation (safe over-firing)")
	void shouldFireForZeroDeltaAttributeMutation() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, PRIORITY_SCHEMA, "priority", 5);

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("priority")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final ApplyDeltaAttributeMutation<Integer> mutation = new ApplyDeltaAttributeMutation<>("priority", 0);
		executor.applyMutation(mutation);
		final List<LocalMutation<?, ?>> mutations = List.of(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(mutations);

		assertEquals(1, result.indexMutations().length);
	}

	// --- Registry consultation ---

	@Test
	@DisplayName("Should return empty when no triggers are registered")
	void shouldReturnEmptyWhenNoTriggersRegistered() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final CatalogExpressionTriggerRegistry registry = CatalogExpressionTriggerRegistry.EMPTY;
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(0, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should not fire when mutated attribute is not in trigger's dependent attributes")
	void shouldNotFireWhenAttributeNotInTriggerDependentAttributes() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, CODE_SCHEMA, "code", "OLD_CODE");

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		// "code" is NOT in the trigger's dependentAttributes — should not fire
		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("code", "NEW_CODE");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(0, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should query registry for both dependency types")
	void shouldQueryRegistryForBothDependencyTypes() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, STATUS_SCHEMA, "status", "ACTIVE");

		final TestTrigger refTrigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE,
			Set.of("status")
		);
		final TestTrigger groupTrigger = new TestTrigger(
			TARGET_ENTITY_TYPE, "brand", Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("status")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, refTrigger, groupTrigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("status", "INACTIVE");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		int totalMutations = 0;
		for (final EntityIndexMutation envelope : result.indexMutations()) {
			totalMutations += envelope.mutations().length;
		}
		assertEquals(2, totalMutations);
	}

	// --- Grouping ---

	@Test
	@DisplayName("Should group mutations into single envelope per target")
	void shouldGroupMutationsIntoSingleEnvelopePerTarget() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger trigger1 = new TestTrigger(
			TARGET_ENTITY_TYPE, "parameter", Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final TestTrigger trigger2 = new TestTrigger(
			TARGET_ENTITY_TYPE, "brand", Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger1, trigger2);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(1, result.indexMutations().length);
		assertEquals(TARGET_ENTITY_TYPE, result.indexMutations()[0].entityType());
		assertEquals(2, result.indexMutations()[0].mutations().length);
	}

	@Test
	@DisplayName("Should create separate envelopes for different targets")
	void shouldCreateSeparateEnvelopesForDifferentTargets() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger productTrigger = new TestTrigger(
			"product", "parameter", Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final TestTrigger offerTrigger = new TestTrigger(
			"offer", "parameter", Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		// two triggers with different owners — need two rebuildForEntityType calls
		final CatalogExpressionTriggerRegistry registry = CatalogExpressionTriggerRegistry.EMPTY
			.rebuildForEntityType("product", List.of(productTrigger))
			.rebuildForEntityType("offer", List.of(offerTrigger));
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(2, result.indexMutations().length);
	}

	// --- Deduplication ---

	@Test
	@DisplayName("Should fire only once when multiple mutations touch the same attribute")
	void shouldDeduplicateMultipleMutationsOnSameAttribute() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		// two mutations on the same attribute in one batch
		executor.applyMutation(new UpsertAttributeMutation("inputWidgetType", "RADIO"));
		executor.applyMutation(new UpsertAttributeMutation("inputWidgetType", "DROPDOWN"));
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(
			new UpsertAttributeMutation("inputWidgetType", "RADIO"),
			new UpsertAttributeMutation("inputWidgetType", "DROPDOWN")
		));

		// single trigger firing despite two mutations
		assertEquals(1, result.indexMutations().length);
		assertEquals(1, result.indexMutations()[0].mutations().length);
	}

	// --- Mixed and multi-attribute batches ---

	@Test
	@DisplayName("Should skip non-attribute mutations in a mixed batch")
	void shouldSkipNonAttributeMutationsInMixedBatch() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation attrMutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		final UpsertAssociatedDataMutation assocMutation = new UpsertAssociatedDataMutation("description", "test");
		executor.applyMutation(attrMutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(attrMutation, assocMutation));

		// only the attribute mutation fires a trigger — the associated data mutation is silently skipped
		assertEquals(1, result.indexMutations().length);
		assertEquals(1, result.indexMutations()[0].mutations().length);
	}

	@Test
	@DisplayName("Should fire triggers for each distinct attribute in batch")
	void shouldFireTriggersForEachDistinctAttributeInBatch() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");
		preLoadAttribute(accessor, STATUS_SCHEMA, "status", "ACTIVE");

		final TestTrigger widgetTrigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final TestTrigger statusTrigger = new TestTrigger(
			TARGET_ENTITY_TYPE, "brand", Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("status")
		);
		final CatalogExpressionTriggerRegistry registry =
			buildRegistry(TARGET_ENTITY_TYPE, widgetTrigger, statusTrigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation widgetMutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		final UpsertAttributeMutation statusMutation = new UpsertAttributeMutation("status", "INACTIVE");
		executor.applyMutation(widgetMutation);
		executor.applyMutation(statusMutation);
		final IndexImplicitMutations result =
			executor.popIndexImplicitMutations(List.of(widgetMutation, statusMutation));

		// both triggers fire — deduplication is per-attribute, not per-batch
		int totalMutations = 0;
		for (final EntityIndexMutation envelope : result.indexMutations()) {
			totalMutations += envelope.mutations().length;
		}
		assertEquals(2, totalMutations);
	}

	// --- Null supplier ---

	@Test
	@DisplayName("Should return empty with null registry supplier")
	void shouldReturnEmptyWithNullRegistrySupplier() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, null);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(0, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should return empty when registry supplier returns null")
	void shouldReturnEmptyWhenRegistrySupplierReturnsNull() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		// supplier is non-null but returns null — must not NPE
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> null);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(0, result.indexMutations().length);
	}

	// --- Scope ---

	@Test
	@DisplayName("Should use scope from trigger, not from entity")
	void shouldUseScopeFromTriggerNotEntity() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.ARCHIVED,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		final ReevaluateFacetExpressionMutation facetMutation =
			(ReevaluateFacetExpressionMutation) result.indexMutations()[0].mutations()[0];
		assertEquals(Scope.ARCHIVED, facetMutation.scope());
	}

	// --- Batch isolation ---

	@Test
	@DisplayName("Should not carry over state between successive pop calls")
	void shouldNotCarryOverStateBetweenPops() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		// first batch: triggers fire
		final UpsertAttributeMutation mutation1 = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation1);
		final IndexImplicitMutations result1 = executor.popIndexImplicitMutations(List.of(mutation1));
		assertEquals(1, result1.indexMutations().length);

		// second pop with empty input: no triggers
		final IndexImplicitMutations result2 = executor.popIndexImplicitMutations(Collections.emptyList());
		assertEquals(0, result2.indexMutations().length);
	}

	// --- Empty input ---

	@Test
	@DisplayName("Should return empty when no attribute mutations are applied")
	void shouldReturnEmptyWhenNoAttributeMutationsApplied() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		// pre-initialize entity body so isEntityRemovedEntirely() doesn't NPE
		accessor.getEntityStoragePart(SOURCE_ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST);
		final CatalogExpressionTriggerRegistry registry = CatalogExpressionTriggerRegistry.EMPTY;
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final IndexImplicitMutations result = executor.popIndexImplicitMutations(Collections.emptyList());

		assertEquals(0, result.indexMutations().length);
	}

	// --- Entity removal ---

	@Test
	@DisplayName("Should fire all triggers on entity removal")
	void shouldFireAllTriggersOnEntityRemoval() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		accessor.getEntityStoragePart(SOURCE_ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST)
			.markForRemoval();

		final TestTrigger refTrigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE,
			Set.of("status")
		);
		final TestTrigger groupTrigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, refTrigger, groupTrigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		// no applyMutation() needed — removal bypasses per-attribute scanning
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(Collections.emptyList());

		assertEquals(1, result.indexMutations().length);
		final EntityIndexMutation envelope = result.indexMutations()[0];
		assertEquals(TARGET_ENTITY_TYPE, envelope.entityType());
		assertEquals(2, envelope.mutations().length);
	}

	@Test
	@DisplayName("Should return empty on entity removal when no triggers registered")
	void shouldReturnEmptyOnRemovalWhenNoTriggers() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		accessor.getEntityStoragePart(SOURCE_ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST)
			.markForRemoval();

		final CatalogExpressionTriggerRegistry registry = CatalogExpressionTriggerRegistry.EMPTY;
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final IndexImplicitMutations result = executor.popIndexImplicitMutations(Collections.emptyList());

		assertEquals(0, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should return empty on entity removal with null supplier")
	void shouldReturnEmptyOnRemovalWithNullSupplier() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		accessor.getEntityStoragePart(SOURCE_ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST)
			.markForRemoval();

		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, null);

		final IndexImplicitMutations result = executor.popIndexImplicitMutations(Collections.emptyList());

		assertEquals(0, result.indexMutations().length);
	}

	// --- Entity removal — grouping and PK ---

	@Test
	@DisplayName("Should group removal mutations by target entity type")
	void shouldGroupRemovalMutationsByTargetEntityType() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		accessor.getEntityStoragePart(SOURCE_ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST)
			.markForRemoval();

		final TestTrigger productTrigger = new TestTrigger(
			"product", "parameter", Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final TestTrigger offerTrigger = new TestTrigger(
			"offer", "parameter", Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = CatalogExpressionTriggerRegistry.EMPTY
			.rebuildForEntityType("product", List.of(productTrigger))
			.rebuildForEntityType("offer", List.of(offerTrigger));
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final IndexImplicitMutations result = executor.popIndexImplicitMutations(Collections.emptyList());

		assertEquals(2, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should carry correct entity PK in removal mutations")
	void shouldCarryCorrectEntityPkInRemovalMutations() {
		final int customPK = 42;
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		accessor.getEntityStoragePart(SOURCE_ENTITY_TYPE, customPK, EntityExistence.MUST_EXIST)
			.markForRemoval();

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.REFERENCED_ENTITY_ATTRIBUTE,
			Set.of("status")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry, customPK);

		final IndexImplicitMutations result = executor.popIndexImplicitMutations(Collections.emptyList());

		assertEquals(1, result.indexMutations().length);
		final ReevaluateFacetExpressionMutation facetMutation =
			(ReevaluateFacetExpressionMutation) result.indexMutations()[0].mutations()[0];
		assertEquals(customPK, facetMutation.mutatedEntityPK());
	}

	// --- Scope — separate mutations for different scopes ---

	@Test
	@DisplayName("Should create separate mutations for different scopes on the same reference")
	void shouldCreateSeparateMutationsForDifferentScopes() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		preLoadAttribute(accessor, INPUT_WIDGET_TYPE_SCHEMA, "inputWidgetType", "CHECKBOX");

		final TestTrigger liveTrigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final TestTrigger archivedTrigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.ARCHIVED,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry =
			buildRegistry(TARGET_ENTITY_TYPE, liveTrigger, archivedTrigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		// both triggers fire — same target entity type, so grouped into one envelope with two mutations
		assertEquals(1, result.indexMutations().length);
		assertEquals(2, result.indexMutations()[0].mutations().length);

		boolean hasLive = false;
		boolean hasArchived = false;
		for (final IndexMutation indexMutation : result.indexMutations()[0].mutations()) {
			final ReevaluateFacetExpressionMutation facetMutation =
				(ReevaluateFacetExpressionMutation) indexMutation;
			if (facetMutation.scope() == Scope.LIVE) {
				hasLive = true;
			} else if (facetMutation.scope() == Scope.ARCHIVED) {
				hasArchived = true;
			}
		}
		assertTrue(hasLive, "Expected a LIVE-scoped mutation");
		assertTrue(hasArchived, "Expected an ARCHIVED-scoped mutation");
	}

	// --- Mutation type filtering ---

	@Test
	@DisplayName("Should ignore reference attribute mutations (not entity-level attributes)")
	void shouldIgnoreReferenceAttributeMutations() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("status")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		// ReferenceAttributeMutation is a ReferenceMutation, NOT an AttributeMutation
		final ReferenceAttributeMutation refAttrMutation = new ReferenceAttributeMutation(
			REFERENCE_NAME, 1, new UpsertAttributeMutation("status", "ACTIVE")
		);
		final IndexImplicitMutations result =
			executor.popIndexImplicitMutations(List.of(refAttrMutation));

		assertEquals(0, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should ignore price mutations")
	void shouldIgnorePriceMutations() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final RemovePriceMutation priceMutation =
			new RemovePriceMutation(1, "basic", Currency.getInstance("USD"));
		final IndexImplicitMutations result =
			executor.popIndexImplicitMutations(List.of(priceMutation));

		assertEquals(0, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should ignore parent mutations")
	void shouldIgnoreParentMutations() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final SetParentMutation parentMutation = new SetParentMutation(10);
		final IndexImplicitMutations result =
			executor.popIndexImplicitMutations(List.of(parentMutation));

		assertEquals(0, result.indexMutations().length);
	}

	@Test
	@DisplayName("Should ignore scope mutations")
	void shouldIgnoreScopeMutations() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("inputWidgetType")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		final SetEntityScopeMutation scopeMutation = new SetEntityScopeMutation(Scope.ARCHIVED);
		final IndexImplicitMutations result =
			executor.popIndexImplicitMutations(List.of(scopeMutation));

		assertEquals(0, result.indexMutations().length);
	}

	// --- Trigger lookup ---

	@Nested
	@DisplayName("Trigger lookup via getTriggerFor")
	class TriggerLookupTest {

		@Test
		@DisplayName("Should return null when no local trigger supplier is installed")
		void shouldReturnNullWhenNoLocalTriggerSupplierInstalled() {
			final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
			final EntityIndexLocalMutationExecutor executor = createExecutorWithTriggerSupplier(
				accessor, null, null
			);

			final FacetExpressionTrigger result = executor.getTriggerFor(REFERENCE_NAME, Scope.LIVE);

			assertNull(result);
		}

		@Test
		@DisplayName("Should return trigger from supplier when installed")
		void shouldReturnTriggerFromSupplierWhenInstalled() {
			final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
			final TestFacetTrigger expectedTrigger = new TestFacetTrigger();
			final EntityIndexLocalMutationExecutor executor = createExecutorWithTriggerSupplier(
				accessor, null, (name, scope) -> expectedTrigger
			);

			final FacetExpressionTrigger result = executor.getTriggerFor("brand", Scope.LIVE);

			assertNotNull(result);
			assertSame(expectedTrigger, result);
		}

		@Test
		@DisplayName("Should delegate to supplier on each call for same reference and scope")
		void shouldDelegateToSupplierOnEachCallForSameReferenceAndScope() {
			final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
			final AtomicInteger counter = new AtomicInteger(0);
			final TestFacetTrigger trigger = new TestFacetTrigger();
			final EntityIndexLocalMutationExecutor executor = createExecutorWithTriggerSupplier(
				accessor, null, (name, scope) -> {
					counter.incrementAndGet();
					return trigger;
				}
			);

			final FacetExpressionTrigger first = executor.getTriggerFor(REFERENCE_NAME, Scope.LIVE);
			final FacetExpressionTrigger second = executor.getTriggerFor(REFERENCE_NAME, Scope.LIVE);

			assertSame(trigger, first);
			assertSame(trigger, second);
			// no memoization at executor level — supplier is called on every invocation
			assertEquals(2, counter.get());
		}

		@Test
		@DisplayName("Should delegate to supplier independently per reference and scope")
		void shouldDelegateToSupplierIndependentlyPerReferenceAndScope() {
			final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
			final AtomicInteger counter = new AtomicInteger(0);
			final TestFacetTrigger trigger = new TestFacetTrigger();
			final EntityIndexLocalMutationExecutor executor = createExecutorWithTriggerSupplier(
				accessor, null, (name, scope) -> {
					counter.incrementAndGet();
					return trigger;
				}
			);

			executor.getTriggerFor(REFERENCE_NAME, Scope.LIVE);
			executor.getTriggerFor("brand", Scope.LIVE);
			executor.getTriggerFor(REFERENCE_NAME, Scope.ARCHIVED);

			// each distinct (referenceName, scope) pair invokes the supplier separately
			assertEquals(3, counter.get());
		}

		@Test
		@DisplayName("Should return null when trigger supplier returns null for given reference")
		void shouldReturnNullWhenTriggerSupplierReturnsNullForGivenReference() {
			final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
			final EntityIndexLocalMutationExecutor executor = createExecutorWithTriggerSupplier(
				accessor, null, (name, scope) -> null
			);

			final FacetExpressionTrigger result = executor.getTriggerFor("brand", Scope.LIVE);

			assertNull(result);
			// hasFacetExpressionTriggers is true because the supplier itself is non-null
			assertTrue(executor.hasFacetExpressionTriggers());
		}

		@Test
		@DisplayName("Should report no facet expression triggers when supplier is null")
		void shouldReportNoFacetExpressionTriggersWhenSupplierIsNull() {
			final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
			final EntityIndexLocalMutationExecutor executor = createExecutorWithTriggerSupplier(
				accessor, null, null
			);

			assertFalse(executor.hasFacetExpressionTriggers());
			assertNull(executor.getTriggerFor(REFERENCE_NAME, Scope.LIVE));
		}

		@Test
		@DisplayName("Should not re-evaluate when no trigger is defined for reference")
		void shouldNotReEvaluateWhenNoTriggerDefinedForReference() {
			final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
			// supplier returns null — no expression defined for any reference/scope combination
			final AtomicInteger supplierCallCount = new AtomicInteger(0);
			final EntityIndexLocalMutationExecutor executor = createExecutorWithTriggerSupplier(
				accessor, null, (name, scope) -> {
					supplierCallCount.incrementAndGet();
					return null;
				}
			);

			// hasFacetExpressionTriggers returns true (supplier is non-null), but getTriggerFor returns null
			assertTrue(executor.hasFacetExpressionTriggers());

			// apply an attribute mutation — this would normally trigger re-evaluation
			// but since getTriggerFor returns null, the re-evaluation path in
			// ReferenceIndexMutator.reEvaluateFacetExpressionsInAllIndexes skips evaluation (cachedTrigger == null)
			final UpsertAttributeMutation mutation = new UpsertAttributeMutation("inputWidgetType", "RADIO");
			executor.applyMutation(mutation);

			// verify the mutation was processed without errors (no NPE, no evaluation attempted)
			// the source-side detection still works independently via the registry supplier
			final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

			// no cross-entity mutations because registrySupplier is null
			assertEquals(0, result.indexMutations().length);
		}

	}

	@Test
	@DisplayName("Should handle localized attribute key (locale does not prevent matching)")
	void shouldHandleLocalizedAttributeKey() {
		final MockStorageContainerAccessor accessor = new MockStorageContainerAccessor();
		// pre-load a localized attribute value for locale=en
		accessor.getAttributeStoragePart(SOURCE_ENTITY_TYPE, ENTITY_PK, Locale.ENGLISH)
			.upsertAttribute(
				new AttributeKey("name", Locale.ENGLISH),
				NAME_SCHEMA,
				existing -> new AttributeValue(new AttributeKey("name", Locale.ENGLISH), "Widget")
			);

		final TestTrigger trigger = new TestTrigger(
			TARGET_ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			SOURCE_ENTITY_TYPE, DependencyType.GROUP_ENTITY_ATTRIBUTE,
			Set.of("name")
		);
		final CatalogExpressionTriggerRegistry registry = buildRegistry(TARGET_ENTITY_TYPE, trigger);
		final EntityIndexLocalMutationExecutor executor = createExecutor(accessor, () -> registry);

		// localized mutation: AttributeKey("name", Locale.ENGLISH) — attributeName() returns "name"
		final UpsertAttributeMutation mutation =
			new UpsertAttributeMutation("name", Locale.ENGLISH, "Gadget");
		executor.applyMutation(mutation);
		final IndexImplicitMutations result = executor.popIndexImplicitMutations(List.of(mutation));

		assertEquals(1, result.indexMutations().length);
		final ReevaluateFacetExpressionMutation facetMutation =
			(ReevaluateFacetExpressionMutation) result.indexMutations()[0].mutations()[0];
		assertEquals(REFERENCE_NAME, facetMutation.referenceName());
		assertEquals(ENTITY_PK, facetMutation.mutatedEntityPK());
	}

	/**
	 * Creates a minimal {@link EntityIndexLocalMutationExecutor} with explicit control over both
	 * the trigger registry supplier and the local facet trigger supplier. This allows testing
	 * the `getTriggerFor()` memoization path independently of the source-side detection logic.
	 *
	 * @param containerAccessor      pre-configured storage accessor
	 * @param registrySupplier       trigger registry supplier, or null
	 * @param localTriggerSupplier   local facet trigger supplier, or null to test the null-supplier path
	 * @return a new executor
	 */
	@Nonnull
	private static EntityIndexLocalMutationExecutor createExecutorWithTriggerSupplier(
		@Nonnull MockStorageContainerAccessor containerAccessor,
		@Nullable Supplier<CatalogExpressionTriggerRegistry> registrySupplier,
		@Nullable BiFunction<String, Scope, FacetExpressionTrigger> localTriggerSupplier
	) {
		final EntitySchema schema = buildSourceSchema();
		final GlobalEntityIndex globalIndex = new GlobalEntityIndex(
			1, SOURCE_ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL)
		);
		final CatalogIndex catalogIndex = new CatalogIndex(Scope.LIVE);
		final AtomicInteger sequencer = new AtomicInteger(1);
		return new EntityIndexLocalMutationExecutor(
			containerAccessor, ENTITY_PK,
			new MockEntityIndexCreator<>(globalIndex),
			new MockEntityIndexCreator<>(catalogIndex),
			() -> schema,
			sequencer::getAndIncrement,
			false,
			() -> { throw new UnsupportedOperationException("Not used in trigger test."); },
			registrySupplier,
			localTriggerSupplier,
			null
		);
	}

	/**
	 * Minimal {@link FacetExpressionTrigger} implementation for trigger lookup tests.
	 * Provides identity-based equality so `assertSame` can verify memoization.
	 */
	private static final class TestFacetTrigger implements FacetExpressionTrigger {

		@Nonnull
		@Override
		public String getOwnerEntityType() {
			return TARGET_ENTITY_TYPE;
		}

		@Nonnull
		@Override
		public String getReferenceName() {
			return REFERENCE_NAME;
		}

		@Nonnull
		@Override
		public Scope getScope() {
			return Scope.LIVE;
		}

		@Nullable
		@Override
		public String getMutatedEntityType() {
			return null;
		}

		@Nullable
		@Override
		public DependencyType getDependencyType() {
			return null;
		}

		@Nullable
		@Override
		public String getDependentReferenceName() {
			return null;
		}

		@Nonnull
		@Override
		public Set<String> getDependentAttributes() {
			return Set.of();
		}

		@Nonnull
		@Override
		public FilterBy getFilterByConstraint() {
			throw new UnsupportedOperationException("Not needed for trigger lookup tests.");
		}

		@Override
		public boolean evaluate(
			int ownerEntityPK,
			@Nonnull ReferenceKey referenceKey,
			@Nonnull WritableEntityStorageContainerAccessor storageAccessor,
			@Nonnull Function<String, EntitySchemaContract> schemaResolver
		) {
			throw new UnsupportedOperationException("Not needed for trigger lookup tests.");
		}
	}

}
