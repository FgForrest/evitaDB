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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.expression.trigger.IndexingExpressionInputValidator.IndexingExpressionKind;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.EntitySchemaStoragePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ExceptionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static io.evitadb.core.expression.trigger.IndexingExpressionInputCells.ENGINE_MAINTAINED;
import static io.evitadb.core.expression.trigger.IndexingExpressionInputCells.HOP;
import static io.evitadb.core.expression.trigger.IndexingExpressionInputCells.VALUE_FORM;
import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a stored schema holding an indexing expression that reads data evitaDB cannot keep its index current
 * with makes the catalog report as corrupted when it loads, the way any other load failure does, and publishes
 * nothing. Such a schema can only have been written by a release that admitted the read; the test produces it without
 * a binary fixture: the engine writes a catalog with an accepted expression, and the stored entity schema is then
 * rewritten offline through the collection's own storage-part persistence service - the route the storage protocol
 * migration tests use - by applying the schema mutation to the stored schema directly, which no validation guards.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A stored schema with an indexing expression evitaDB cannot track does not load")
@Tag(STORAGE)
@Tag(SCHEMA)
class IndexingExpressionInputLoadTest implements EvitaTestSupport {

	private static final String BRAND = "brand";
	private static final String PRODUCT = "product";
	private static final String REFERENCE_NAME = "brand";
	private static final String HISTOGRAM_NAME = "shares";
	private static final String VALUE = "$reference.attributes['share']";
	private static final String PLACEHOLDER = "$entity.attributes['status'] == 'A'";
	private static final String CONTROL = "$entity.attributes['status'] == 'B'";
	private static final String VERSION_READ = "$entity.version >= 3";

	/**
	 * Every stored state and slot, with the refused read and its reason, and the accepted controls of the histogram
	 * and the facet condition.
	 *
	 * @return the arguments: the state, the slot, the expression, the refused read (`null` for a form refusal and a
	 * control) and a fragment of the reason (`null` for a control)
	 */
	@Nonnull
	static Stream<Arguments> cells() {
		final List<Arguments> arguments = new ArrayList<>(12);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			arguments.add(Arguments.of(
				state, IndexingExpressionKind.BUCKETED_PARTIALLY, VERSION_READ, "$entity.version", ENGINE_MAINTAINED
			));
			arguments.add(Arguments.of(
				state, IndexingExpressionKind.ASSIGNED_WHEN, "$reference.referencedEntity != null",
				"$reference.referencedEntity", HOP
			));
			arguments.add(Arguments.of(
				state, IndexingExpressionKind.HISTOGRAM_VALUE, "$reference.attributes['share'] + 1", null, VALUE_FORM
			));
			arguments.add(Arguments.of(
				state, IndexingExpressionKind.FACETED_PARTIALLY, "$entity.version > 1", "$entity.version",
				ENGINE_MAINTAINED
			));
			arguments.add(Arguments.of(state, IndexingExpressionKind.BUCKETED_PARTIALLY, CONTROL, null, null));
			arguments.add(Arguments.of(state, IndexingExpressionKind.FACETED_PARTIALLY, CONTROL, null, null));
		}
		return arguments.stream();
	}

	/**
	 * Stores a catalog, rewrites the expression of one slot offline, and starts the engine over it.
	 *
	 * @param state      the state the catalog is stored in
	 * @param slot       the slot whose expression is rewritten
	 * @param expression the expression written into the slot
	 * @param read       the read the refusal names, `null` for a form refusal and for an accepted control
	 * @param reason     a fragment of the reason of the refusal, or `null` for an accepted control
	 */
	@ParameterizedTest(name = "{0} {1} `{2}`")
	@MethodSource("cells")
	@DisplayName("the catalog reports as corrupted, names the read and publishes nothing; the control loads")
	void shouldReportCatalogCorruptedWhenStoredExpressionReadsUntrackedData(
		@Nonnull CatalogState state,
		@Nonnull IndexingExpressionKind slot,
		@Nonnull String expression,
		@Nullable String read,
		@Nullable String reason
	) {
		final boolean refused = reason != null;
		final TestPaths paths = createTestPaths("IndexingExpressionInputLoad_" + state + "_" + slot + "_" + refused);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try {
			final CatalogSchemaContract catalogSchema;
			try (Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					defineSchema(session);
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});
				catalogSchema = evita.queryCatalog(TEST_CATALOG, session -> {
					return session.getCatalogSchema();
				});
			}
			final CatalogBootstrap rewritten = rewriteStoredExpression(
				configuration, catalogSchema, slot, ExpressionFactory.parse(expression)
			);

			final Evita reopened = new Evita(configuration);
			try {
				if (refused) {
					assertThrows(Exception.class, reopened::waitUntilFullyInitialized);
					assertEquals(CatalogState.CORRUPTED, reopened.getCatalogState(TEST_CATALOG).orElseThrow());
					final Exception failure = assertThrows(
						Exception.class,
						() -> reopened.queryCatalog(TEST_CATALOG, session -> {
							return session.getAllEntityTypes();
						})
					);
					final InvalidSchemaMutationException cause =
						ExceptionUtils.findInCauseChain(failure, InvalidSchemaMutationException.class);
					assertNotNull(cause, "the validator's refusal must be in the cause chain of: " + failure);
					final String message = cause.getMessage();
					assertTrue(message.startsWith(slotText(slot)), message);
					assertTrue(message.contains(reason), message);
					assertTrue(
						message.contains(read == null ? " has an unsupported form: " : "cannot read `" + read + "`"),
						message
					);
				} else {
					reopened.waitUntilFullyInitialized();
					assertEquals(state, reopened.getCatalogState(TEST_CATALOG).orElseThrow());
					final Expression stored = reopened.queryCatalog(
						TEST_CATALOG,
						session -> {
							return session.getEntitySchemaOrThrowException(PRODUCT)
								.getReference(REFERENCE_NAME)
								.map(
									reference -> slot == IndexingExpressionKind.FACETED_PARTIALLY ?
										reference.getFacetedPartiallyInScopes() :
										reference.getBucketedPartiallyInScopes()
								)
								.map(conditions -> conditions.get(Scope.LIVE))
								.orElse(null);
						}
					);
					assertNotNull(stored, "the control condition must be loaded");
					assertEquals(expression, stored.toExpressionString());
				}
			} finally {
				reopened.close();
			}
			if (refused) {
				assertEquals(
					rewritten, lastBootstrap(configuration, TEST_CATALOG),
					"a catalog that failed to load must publish nothing"
				);
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Defines the referenced type and the owner with a histogram and a facet, both conditioned on an accepted read.
	 *
	 * @param session the session
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey()
			.withAttribute("status", String.class, attribute -> attribute.filterable().nullable())
			.withReferenceToEntity(REFERENCE_NAME, BRAND, Cardinality.ZERO_OR_MORE, reference -> reference
				.indexedForFiltering()
				.faceted()
				.withAttribute("share", Integer.class, attribute -> attribute.filterable().nullable())
				.bucketedInScope(Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE), null)
				.bucketedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(PLACEHOLDER))
				.facetedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(PLACEHOLDER))
			)
			.updateVia(session);
	}

	/**
	 * Rewrites the expression of one slot of the stored owner schema with the engine stopped, and publishes the
	 * rewritten schema the way the engine publishes a flushed collection: the schema part is put through the
	 * collection's storage-part persistence service, the collection is flushed, its header is written into the
	 * catalog header, and a bootstrap record is appended.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogSchema the catalog schema the mutation is applied in
	 * @param slot          the slot to rewrite
	 * @param expression    the expression to write
	 * @return the bootstrap record publishing the rewritten schema
	 */
	@Nonnull
	private static CatalogBootstrap rewriteStoredExpression(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull IndexingExpressionKind slot,
		@Nonnull Expression expression
	) {
		final int catalogFileIndex = lastBootstrap(configuration, TEST_CATALOG).catalogFileIndex();
		withOfflineCatalog(
			configuration, TEST_CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
					service.getStoragePartPersistenceService(catalogVersion);
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
					catalogParts.getCatalogHeader(catalogVersion);
				final CollectionFileReference reference = Objects.requireNonNull(
					header.collectionFileIndex().get(PRODUCT), "the catalog must hold the owner collection"
				);
				final DefaultEntityCollectionPersistenceService collectionService =
					service.getOrCreateEntityCollectionPersistenceService(
						catalogVersion, PRODUCT, reference.entityTypePrimaryKey()
					);
				final EntitySchema stored = Objects.requireNonNull(
					collectionService.getStoragePartPersistenceService()
						.getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class)
				).entitySchema();
				final EntitySchema rewrittenSchema = (EntitySchema) mutationOf(slot, expression)
					.mutate(catalogSchema, stored);
				collectionService.getStoragePartPersistenceService()
					.putStoragePart(catalogVersion, new EntitySchemaStoragePart(rewrittenSchema));
				final OffsetIndexDescriptor descriptor = collectionService.flush(
					catalogVersion,
					new NoChangeHeaderInfoSupplier(collectionService.getEntityCollectionHeader())
				);
				catalogParts.putStoragePart(catalogVersion, collectionService.getEntityCollectionHeader());
				final Map<String, CollectionFileReference> collectionFileIndex =
					new HashMap<>(header.collectionFileIndex());
				collectionFileIndex.compute(
					PRODUCT,
					(key, current) -> new CollectionFileReference(
						PRODUCT, Objects.requireNonNull(current).entityTypePrimaryKey(), current.fileIndex(),
						descriptor.fileLocation()
					)
				);
				catalogParts.writeCatalogHeader(
					PersistenceService.STORAGE_PROTOCOL_VERSION, catalogVersion, service.getCatalogStoragePath(),
					header.walFileReference(), collectionFileIndex, header.catalogId(), header.catalogName(),
					header.catalogState(), header.lastEntityCollectionPrimaryKey()
				);
				service.recordBootstrap(catalogVersion, TEST_CATALOG, catalogFileIndex, null);
				return Boolean.TRUE;
			}
		);
		return lastBootstrap(configuration, TEST_CATALOG);
	}

	/**
	 * Returns the schema mutation setting the expression into the slot, the other slot left as defined.
	 *
	 * @param slot       the slot
	 * @param expression the expression
	 * @return the mutation
	 */
	@Nonnull
	private static LocalEntitySchemaMutation mutationOf(
		@Nonnull IndexingExpressionKind slot, @Nonnull Expression expression
	) {
		return switch (slot) {
			case BUCKETED_PARTIALLY -> new SetReferenceSchemaBucketedMutation(
				REFERENCE_NAME,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE), null)
				},
				new ScopedBucketedPartially[]{new ScopedBucketedPartially(Scope.LIVE, expression)}
			);
			case ASSIGNED_WHEN -> new SetReferenceSchemaBucketedMutation(
				REFERENCE_NAME,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(
						Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE), expression
					)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse(PLACEHOLDER))
				}
			);
			case HISTOGRAM_VALUE -> new SetReferenceSchemaBucketedMutation(
				REFERENCE_NAME,
				new ScopedHistogramIndexDefinition[]{
					new ScopedHistogramIndexDefinition(Scope.LIVE, HISTOGRAM_NAME, expression, null)
				},
				new ScopedBucketedPartially[]{
					new ScopedBucketedPartially(Scope.LIVE, ExpressionFactory.parse(PLACEHOLDER))
				}
			);
			case FACETED_PARTIALLY -> new SetReferenceSchemaFacetedMutation(
				REFERENCE_NAME,
				new Scope[]{Scope.LIVE},
				new ScopedFacetedPartially[]{new ScopedFacetedPartially(Scope.LIVE, expression)}
			);
		};
	}

	/**
	 * Returns the start of a refusal message of the slot: its name and, for a slot of a histogram, the histogram.
	 *
	 * @param slot the slot
	 * @return the start of the message
	 */
	@Nonnull
	private static String slotText(@Nonnull IndexingExpressionKind slot) {
		return switch (slot) {
			case BUCKETED_PARTIALLY -> "The `bucketedPartially` condition `";
			case ASSIGNED_WHEN -> "The `assignedWhen` condition of histogram `" + HISTOGRAM_NAME + "` `";
			case HISTOGRAM_VALUE -> "The value expression of histogram `" + HISTOGRAM_NAME + "` `";
			case FACETED_PARTIALLY -> "The `facetedPartially` condition `";
		};
	}

}
