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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeNormalizationStoragePart;
import io.evitadb.store.catalog.OffsetIndexStoragePartPersistenceService;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Bounded schema-adoption probes and writes. Index-level probes run on in-memory indexes; the schema-change path runs
 * on a real engine and reads the effects from its storage. The one mock left proves that a probe never iterates a
 * sort index - {@link SortIndex} is sealed, so no real index can report that it was not iterated.
 */
@Tag(INDEXING)
@DisplayName("Normalization schema changes pay only for changed effective policy")
class NormalizationSchemaChangeCostTest implements EvitaTestSupport {
	private static final AttributeIndexKey CODE = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey COMPOUND = new AttributeIndexKey(null, "compound", null);
	private static final String PRODUCT = "Product";

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
	@DisplayName("should keep no op schema and unnormalized additions free of policy writes and index refreshes")
	void shouldKeepNoOpSchemaAndUnnormalizedAdditionsFreeOfPolicyWritesAndIndexRefreshes() {
		final TestPaths paths = createTestPaths("NormalizationSchemaChangeCost");
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try {
			try (final Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey()
						.withAttribute("code", String.class, AttributeSchemaEditor::sortable)
						.updateVia(session);
					session.upsertEntity(session.createNewEntity(PRODUCT, 1).setAttribute("code", "Code"));
				});
				final SortIndex codeIndex = sortIndex(evita);
				final int schemaVersion = schemaVersion(evita);

				// a statement keeping the attribute unnormalized changes neither the schema nor the stored policy
				keepCodeUnnormalized(evita);
				assertEquals(schemaVersion, schemaVersion(evita));
				assertNull(storedPolicyLocation(evita));

				// an unnormalized compound stores its bindings, but no row, and leaves the existing sort index alone
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.getEntitySchemaOrThrowException(PRODUCT).openForWrite()
						.withAttribute("other", Integer.class)
						.withSortableAttributeCompound("compound", element("code"), element("other"))
						.updateVia(session);
				});
				assertTrue(policy(evita).rows().isEmpty());
				assertEquals(1, policy(evita).compounds().size(), "Bindings remain durable metadata");
				assertSame(codeIndex, sortIndex(evita));
				final FileLocation stored = storedPolicyLocation(evita);
				assertNotNull(stored);

				// repeating the statement rewrites nothing - the append-only record keeps its address
				final int compoundSchemaVersion = schemaVersion(evita);
				keepCodeUnnormalized(evita);
				assertEquals(compoundSchemaVersion, schemaVersion(evita));
				assertEquals(stored, storedPolicyLocation(evita));
			}
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				assertEquals(1, policy(evita).compounds().size(), "Bindings survive a restart");
				// dropping the only compound empties the policy, which removes the stored record
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.getEntitySchemaOrThrowException(PRODUCT).openForWrite()
						.withoutSortableAttributeCompound("compound")
						.updateVia(session);
				});
				assertEquals(AttributeNormalizationPolicy.EMPTY, policy(evita));
				assertNull(storedPolicyLocation(evita));
			}
		} finally {
			cleanupTestPaths(paths);
		}
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
	 * Applies a statement declaring no normalization on `code`, which is what the attribute already declares.
	 *
	 * @param evita engine holding the test catalog
	 */
	private static void keepCodeUnnormalized(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateEntitySchema(
				new ModifyEntitySchemaMutation(PRODUCT, new SetAttributeSchemaNormalizedMutation("code"))
			);
		});
	}

	/**
	 * Looks up the live product collection.
	 *
	 * @param evita engine holding the test catalog
	 * @return the product collection of the current catalog instance
	 */
	@Nonnull
	private static EntityCollection product(@Nonnull Evita evita) {
		return ((Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getCollectionForEntityOrThrowException(PRODUCT);
	}

	/**
	 * Reads the version of the product schema.
	 *
	 * @param evita engine holding the test catalog
	 * @return current version of the product schema
	 */
	private static int schemaVersion(@Nonnull Evita evita) {
		return product(evita).getSchema().version();
	}

	/**
	 * Reads the built-with policy the product collection has adopted.
	 *
	 * @param evita engine holding the test catalog
	 * @return the adopted policy
	 */
	@Nonnull
	private static AttributeNormalizationPolicy policy(@Nonnull Evita evita) {
		return product(evita).getNormalizationPolicy();
	}

	/**
	 * Looks up the sort index of `code` in the product's global index.
	 *
	 * @param evita engine holding the test catalog
	 * @return the sort index instance currently serving `code`
	 */
	@Nonnull
	private static SortIndex sortIndex(@Nonnull Evita evita) {
		return Objects.requireNonNull(product(evita).getGlobalIndexIfExists().orElseThrow().getSortIndex(CODE));
	}

	/**
	 * Finds where the product collection's stored policy record sits in its data file. The file is append-only, so
	 * a rewrite of the record, even with equal content, moves it to a new address.
	 *
	 * @param evita engine holding the test catalog
	 * @return address of the stored policy record, or `null` when no policy is stored
	 */
	@Nullable
	private static FileLocation storedPolicyLocation(@Nonnull Evita evita) {
		final StoragePartPersistenceService<?> parts = product(evita).getStoragePartPersistenceService();
		final OffsetIndex store = ((OffsetIndexStoragePartPersistenceService) parts).getOffsetIndex();
		final byte policyType = store.getIdForRecordType(AttributeNormalizationStoragePart.class);
		for (final Entry<RecordKey, FileLocation> entry : store.getEntries()) {
			if (entry.getKey().recordType() == policyType) {
				return entry.getValue();
			}
		}
		return null;
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
		return builder().withAttribute("code", String.class, AttributeSchemaEditor::nullable)
			.withAttribute("other", Integer.class).withAttribute("untouched", String.class,
			                                                     AttributeSchemaEditor::nullable
			)
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
