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


package io.evitadb.index.analysis;

import io.evitadb.api.CatalogState;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeNormalizationStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Bounded schema-adoption probes and writes, using in-memory stores and mocked persistence only. */
@Tag(INDEXING)
@DisplayName("Normalization schema changes pay only for changed effective policy")
class NormalizationSchemaChangeCostTest {
	private static final AttributeIndexKey CODE = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey COMPOUND = new AttributeIndexKey(null, "compound", null);

	@Test
	@DisplayName("should never iterate scalar sort values or inspect another attribute")
	void shouldNeverIterateScalarSortValuesOrInspectAnotherAttribute() {
		final OwnerSortIndex index = mock(OwnerSortIndex.class);
		assertFalse(AttributeNormalizationReconciler.containsStringKey(
			new AttributeIndexKey(null, "other", null), index, CODE, AttributeNormalizationPolicy.EMPTY));
		verifyNoInteractions(index);
		when(index.getComparatorBase()).thenReturn(sources(Integer.class));
		assertFalse(AttributeNormalizationReconciler.containsStringKey(CODE, index, CODE,
			AttributeNormalizationPolicy.EMPTY));
		final AttributeNormalizationPolicy numericPosition = new AttributeNormalizationPolicy(Map.of(),
			Map.of(COMPOUND, List.of(
				new AttributeNormalizationPolicy.ElementBinding("code", Integer.class, false, false),
				new AttributeNormalizationPolicy.ElementBinding("other", String.class, false, false))), Set.of());
		assertFalse(AttributeNormalizationReconciler.containsStringKey(COMPOUND, index, CODE, numericPosition));
		when(index.getComparatorBase()).thenReturn(sources(String.class));
		assertTrue(AttributeNormalizationReconciler.containsStringKey(CODE, index, CODE,
			AttributeNormalizationPolicy.EMPTY));
		verify(index, never()).getDistinctValueIterator(anyBoolean());
	}

	@Test
	@DisplayName("should probe only decisions that depend on population")
	void shouldProbeOnlyDecisionsThatDependOnPopulation() {
		final EntitySchemaContract schema = builder().withAttribute("code", String.class)
			.withAttribute("number", Integer.class).toInstance();
		assertEquals(AttributeNormalizationPolicy.EMPTY, AttributeNormalizationReconciler.reconcile("unit",
			AttributeNormalizationPolicy.EMPTY, schema, key -> fail("Unnecessary population check: " + key),
			key -> fail("No compound exists"), Map.of()));
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 300})
	@DisplayName("should commit only changed compound functions without emitting unchanged sort content")
	void shouldCommitOnlyChangedCompoundFunctionsWithoutEmittingUnchangedSortContent(int count) {
		final EntitySchemaContract schema = compoundSchema();
		final AttributeNormalizationPolicy previous = policy(schema, false);
		final BuiltWithAttributeNormalizationResolver oldResolver = resolver(previous);
		final EntityAttributeIndex index = new EntityAttributeIndex("Product", oldResolver);
		for (int i = 1; i <= count; i++) {
			index.insertSortAttributeCompound(schema, null,
				schema.getSortableAttributeCompound("compound").orElseThrow(),
				name -> schema.getAttribute(name).orElseThrow().getType(), null, new Serializable[]{null, i}, i);
		}
		index.insertSortAttributeCompound(schema, null,
			schema.getSortableAttributeCompound("unchanged").orElseThrow(),
			name -> schema.getAttribute(name).orElseThrow().getType(), null,
			new Serializable[]{null, 1}, count + 1);
		index.getModifiedStorageParts(1, new TrappedChanges());
		index.resetDirty();
		final SortIndex unchanged = index.getSortIndex(new AttributeIndexKey(null, "unchanged", null));
		final SortIndex original = index.getSortIndex(COMPOUND);
		assertStateAfterCommit(index, edited -> {
			edited.useNormalizationResolver(resolver(policy(schema, true)));
			assertNotSame(original, edited.getSortIndex(COMPOUND));
			assertSame(unchanged, edited.getSortIndex(new AttributeIndexKey(null, "unchanged", null)));
			final TrappedChanges changes = new TrappedChanges();
			edited.getModifiedStorageParts(1, changes);
			assertEquals(0, changes.getTrappedChangesCount(), "Functions have no persisted sort content");
		}, (old, committed) -> {
			assertNotSame(original, committed.getSortIndex(COMPOUND));
			assertSame(unchanged, committed.getSortIndex(new AttributeIndexKey(null, "unchanged", null)));
			committed.insertSortAttributeCompound(schema, null,
				schema.getSortableAttributeCompound("compound").orElseThrow(),
				name -> schema.getAttribute(name).orElseThrow().getType(), null,
				new Serializable[]{"TITLE", count + 1}, count + 2);
			assertEquals("title", ((SortIndex.ComparableArray) committed.getSortIndex(COMPOUND)
				.getValueOfRecord(count + 2).orElseThrow()).array()[0]);
		});
	}

	@Test
	@DisplayName("should commit virtual function changes without emitting the registry")
	void shouldCommitVirtualFunctionChangesWithoutEmittingTheRegistry() {
		final EntitySchemaContract schema = compoundSchema();
		final BuiltWithAttributeNormalizationResolver previous = resolver(policy(schema, false));
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		final SortIndex.ComparatorSource[] sources = {
			new SortIndex.ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
			new SortIndex.ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
		index.declareSortKey(COMPOUND, sources, 0,
			() -> previous.resolveStoredSortNormalizer(COMPOUND, sources, 0));
		index.putPartitionOwner(1, 1, 1);
		index.putSortValue(COMPOUND, 1, new Serializable[]{null, 1});
		index.appendStorageParts(1, "reference", new TrappedChanges());
		index.resetDirty();
		assertStateAfterCommit(index, edited -> {
			edited.useNormalizationResolver(previous, resolver(policy(schema, true)));
			final TrappedChanges changes = new TrappedChanges();
			edited.appendStorageParts(1, "reference", changes);
			assertEquals(0, changes.getTrappedChangesCount());
		}, (old, committed) -> {
			committed.putPartitionOwner(2, 2, 2);
			committed.putSortValue(COMPOUND, 2, new Serializable[]{"TITLE", 2});
			assertEquals("title", ((SortIndex.ComparableArray) committed.getNormalizedSortValue(COMPOUND,
				committed.getSortValueId(COMPOUND, 2))).array()[0]);
		});
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	@DisplayName("should keep no op schema and unnormalized additions free of policy writes and index refreshes")
	void shouldKeepNoOpSchemaAndUnnormalizedAdditionsFreeOfPolicyWritesAndIndexRefreshes() {
		final CatalogSchema catalogSchema = catalogSchema();
		final EntitySchema schema = EntitySchema._internalBuild("Product");
		final Catalog catalog = mock(Catalog.class);
		when(catalog.getSchema()).thenReturn(new CatalogSchemaDecorator(catalogSchema));
		when(catalog.getGlobalAttributeNormalizationRows()).thenReturn(Map.of());
		when(catalog.getAnalysisStepRegistry()).thenReturn(new AnalysisStepRegistry());
		final EntityCollectionPersistenceService persistence = mock(EntityCollectionPersistenceService.class);
		final StoragePartPersistenceService<StorageDescriptor> storage = mock(StoragePartPersistenceService.class);
		final EntityCollectionHeader header = mock(EntityCollectionHeader.class);
		when(header.entityType()).thenReturn("Product");
		when(persistence.getEntityCollectionHeader()).thenReturn(header);
		when(persistence.getStoragePartPersistenceService()).thenReturn(storage);
		when(storage.getStoragePart(0, 1, EntitySchemaStoragePart.class))
			.thenReturn(new EntitySchemaStoragePart(schema));
		final EntityCollection collection = new EntityCollection("unit", 0, CatalogState.ALIVE, 1, "Product", 0,
			mock(CatalogPersistenceService.class), persistence, mock(CacheSupervisor.class), new SequenceService(),
			mock(TrafficRecordingEngine.class));
		collection.attachToCatalog(null, catalog);
		final GlobalEntityIndex index = mock(GlobalEntityIndex.class);
		when(index.getIndexKey()).thenReturn(new EntityIndexKey(EntityIndexType.GLOBAL));
		when(index.getPrimaryKey()).thenReturn(1);
		collection.addIndex(index);
		final Object originalSchema = collection.getSchema();
		collection.updateSchema(null, catalogSchema);
		assertSame(originalSchema, collection.getSchema());
		verify(catalog, never()).entitySchemaUpdated(any());
		verify(storage, never()).putStoragePart(anyLong(), isA(AttributeNormalizationStoragePart.class));
		collection.updateSchema(null, catalogSchema, builder().withAttribute("code", String.class)
			.toMutation().orElseThrow().getSchemaMutations());
		assertEquals(AttributeNormalizationPolicy.EMPTY, collection.getNormalizationPolicy());
		verify(storage, never()).putStoragePart(anyLong(), isA(AttributeNormalizationStoragePart.class));
		collection.updateSchema(null, catalogSchema, builder().withAttribute("code", String.class)
			.withAttribute("other", Integer.class)
			.withSortableAttributeCompound("compound", element("code"), element("other"))
			.toMutation().orElseThrow().getSchemaMutations());
		assertTrue(collection.getNormalizationPolicy().rows().isEmpty());
		assertEquals(1, collection.getNormalizationPolicy().compounds().size(), "Bindings remain durable metadata");
		verify(storage).putStoragePart(anyLong(), isA(AttributeNormalizationStoragePart.class));
		verify(index, never()).useNormalizationResolver(any());
		verify(index, never()).hasIndexedStringKeys(any(), any());
		clearInvocations(storage, catalog);
		final Object beforeNoOp = collection.getSchema();
		collection.updateSchema(null, catalogSchema, new SetAttributeSchemaNormalizedMutation("code"));
		assertSame(beforeNoOp, collection.getSchema());
		verify(catalog, never()).entitySchemaUpdated(any());
		verify(storage, never()).putStoragePart(anyLong(), isA(AttributeNormalizationStoragePart.class));
		collection.adoptNormalizationPolicy(AttributeNormalizationPolicy.EMPTY);
		verify(storage).removeStoragePart(0, 1, AttributeNormalizationStoragePart.class);
		clearInvocations(storage);
		collection.adoptNormalizationPolicy(AttributeNormalizationPolicy.EMPTY);
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("should use only adopted global rows and bind new compounds from their declaration")
	void shouldUseOnlyAdoptedGlobalRowsAndBindNewCompoundsFromTheirDeclaration() {
		final EntitySchemaContract schema = compoundSchema();
		final AttributeNormalizationPolicy adopted =
			new AttributeNormalizationPolicy(Map.of(), Map.of(), Set.of("used"));
		final AnalysisStepRegistry registry = new AnalysisStepRegistry();
		final BuiltWithAttributeNormalizationResolver old = new BuiltWithAttributeNormalizationResolver(
			registry, adopted, Map.of());
		assertTrue(new BuiltWithAttributeNormalizationResolver(registry, adopted,
			Map.of("unrelated", List.of(lowercase()))).hasSameRows(old));
		assertFalse(new BuiltWithAttributeNormalizationResolver(registry, adopted,
			Map.of("used", List.of(lowercase()))).hasSameRows(old));
		final AttributeNormalizationPolicy staleBindings = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(lowercase())), Map.of(COMPOUND,
				List.of(new AttributeNormalizationPolicy.ElementBinding("untouched", String.class, false, false),
					new AttributeNormalizationPolicy.ElementBinding("other", Integer.class, false, false))), Set.of());
		final BuiltWithAttributeNormalizationResolver retainedSlot = resolver(staleBindings);
		final Serializable[] tuple = {"TITLE", 1};
		retainedSlot.resolveCompoundNormalizer(null, schema.getSortableAttributeCompound("compound").orElseThrow(),
			name -> schema.getAttribute(name).orElseThrow(), null).apply(tuple);
		assertEquals("title", tuple[0], "New empty compound bindings need no resolver-slot refresh");
	}

	/**
	 * Supplies one frozen scalar comparator descriptor.
	 *
	 * @param type value type of the single comparator source
	 * @return one ascending, nulls-last comparator source of the given type
	 */
	@Nonnull
	private static SortIndex.ComparatorSource[] sources(@Nonnull Class<?> type) {
		return new SortIndex.ComparatorSource[]{
			new SortIndex.ComparatorSource(type, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
	}

	/**
	 * Creates an ordered compound element.
	 *
	 * @param name name of the attribute forming the compound element
	 * @return ascending element placing nulls last
	 */
	@Nonnull
	private static AttributeElement element(@Nonnull String name) {
		return new AttributeElement(name, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
	}

	/**
	 * Describes a compound with a nullable String and a populated numeric sibling.
	 *
	 * @return `Product` schema with the `compound` and `unchanged` sortable compounds
	 */
	@Nonnull
	private static EntitySchemaContract compoundSchema() {
		return builder().withAttribute("code", String.class, a -> a.nullable())
			.withAttribute("other", Integer.class).withAttribute("untouched", String.class, a -> a.nullable())
			.withSortableAttributeCompound("compound", element("code"), element("other"))
			.withSortableAttributeCompound("unchanged", element("untouched"), element("other")).toInstance();
	}

	/**
	 * Builds real policies without storing an empty row.
	 *
	 * @param schema schema whose compounds are described in the policy
	 * @param normalized `true` to record a `lowercase` row for the `code` attribute, `false` to record no row
	 * @return policy built from the given schema
	 */
	@Nonnull
	private static AttributeNormalizationPolicy policy(@Nonnull EntitySchemaContract schema, boolean normalized) {
		return new AttributeNormalizationPolicy(normalized ? Map.of(CODE, List.of(lowercase())) : Map.of(),
			AttributeNormalizationReconciler.describeCompounds(schema), Set.of());
	}

	/**
	 * Borrows an immutable policy for new wrappers.
	 *
	 * @param policy policy the resolver reads built-with functions from
	 * @return resolver over the policy with a fresh step registry
	 */
	@Nonnull
	private static BuiltWithAttributeNormalizationResolver resolver(@Nonnull AttributeNormalizationPolicy policy) {
		return new BuiltWithAttributeNormalizationResolver(new AnalysisStepRegistry(), policy, Map.of());
	}

	/**
	 * Produces schema candidates without an engine.
	 *
	 * @return builder of an empty `Product` schema inside an in-memory catalog schema
	 */
	@Nonnull
	private static InternalEntitySchemaBuilder builder() {
		return new InternalEntitySchemaBuilder(catalogSchema(), EntitySchema._internalBuild("Product"));
	}

	/**
	 * Supplies the standalone schema accessor used by the builders.
	 *
	 * @return in-memory catalog schema with all evolution modes enabled
	 */
	@Nonnull
	private static CatalogSchema catalogSchema() {
		return CatalogSchema._internalBuild("unit", NamingConvention.generate("unit"), null,
			EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE);
	}
}
