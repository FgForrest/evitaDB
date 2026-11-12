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

package io.evitadb.driver;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SchemaPostProcessor;
import io.evitadb.api.SchemaPostProcessorCapturingResult;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.ScopeAttributeSettings;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentAllWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an application re-defining its unchanged schema through the Java client on start-up neither emits a
 * schema mutation nor collides with a concurrent writer.
 *
 * Every test uses a client created anew from the server's endpoints, the way a restarted application connects: the
 * original client keeps its own schema cache, which already reflects the mutations it sent and would therefore hide
 * anything the server failed to transfer back.
 *
 * The transport-independent analyzer behaviour is covered by `ClassSchemaAnalyzerRedefinitionTest`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Re-defining an unchanged schema through a restarted client")
@ExtendWith(EvitaParameterResolver.class)
@Tag(DRIVER)
@Tag(GRPC)
@Tag(SCHEMA)
class EvitaClientSchemaRedefinitionTest implements EvitaTestSupport {
	private static final String DATA_SET = "EvitaClientSchemaRedefinitionDataSet";
	private static final String ENTITY_BRAND = "RedefinedBrand";
	private static final String ENTITY_ARTICLE = "RedefinedArticle";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String REFERENCE_ARTICLE_BRAND = "brand";
	private static final String ENTITY_BRAND_GROUP = "RedefinedBrandGroup";
	private static final String REFERENCE_BRAND_ARTICLES = "articles";
	private static final String ENTITY_RANGE_HOLDER = "RedefinedRangeHolder";
	private static final String ATTRIBUTE_ZERO_RANGE = "zeroRange";
	private static final String ATTRIBUTE_FRACTIONAL_RANGE = "fractionalRange";
	/**
	 * Default value without explicit retained decimal places whose bounds encode to the same number at any precision:
	 * only its effective precision of one decimal place tells it apart from the same range compared at another one.
	 */
	private static final BigDecimalNumberRange ZERO_RANGE_DEFAULT = BigDecimalNumberRange.between(
		new BigDecimal("0.0"), new BigDecimal("0.0")
	);
	/**
	 * Default value without explicit retained decimal places whose bounds encode to other numbers at another precision.
	 */
	private static final BigDecimalNumberRange FRACTIONAL_RANGE_DEFAULT = BigDecimalNumberRange.between(
		new BigDecimal("1.0"), new BigDecimal("2.0")
	);
	/**
	 * Post-processor making a genuine change of the global `code` attribute, which takes a catalog-wide conflict key.
	 */
	private static final SchemaPostProcessor CHANGE_CODE_DESCRIPTION = (catalogBuilder, entityBuilder) ->
		catalogBuilder.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.withDescription("changed"));

	/**
	 * Starts the server with the gRPC and system APIs; every test defines its own catalog.
	 *
	 * @param evitaServer the started server
	 */
	@DataSet(
		value = DATA_SET,
		openWebApi = {GrpcProvider.CODE, SystemProvider.CODE},
		readOnly = false,
		destroyAfterClass = true,
		useRealThreadPools = true
	)
	void setUp(@Nonnull EvitaServer evitaServer) {
		assertNotNull(evitaServer.getExternalApiServer());
	}

	@DisplayName("catalog schema delivered to the client keeps the representative flag of a global attribute")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDeliverRepresentativeFlagOfGlobalAttribute(
		@Nonnull EvitaClient evitaClient,
		@Nonnull EvitaServer evitaServer
	) {
		final String catalog = "redefinitionRepresentativeFlag";
		defineBrandAndArticleSchemas(evitaClient, catalog);

		// block lambda with `return` selects the Function overload of `queryCatalog` unambiguously
		final boolean representativeOnServer = evitaServer.getEvita().queryCatalog(
			catalog,
			session -> {
				return session.getCatalogSchema()
					.getAttribute(ATTRIBUTE_CODE)
					.map(GlobalAttributeSchemaContract::isRepresentative)
					.orElseThrow();
			}
		);
		assertTrue(representativeOnServer, "The server-side catalog schema must mark `code` as representative.");

		try (final EvitaClient restartedClient = newClient(evitaServer)) {
			final boolean representativeOnClient = restartedClient.queryCatalog(
				catalog,
				session -> {
					return session.getCatalogSchema()
						.getAttribute(ATTRIBUTE_CODE)
						.map(GlobalAttributeSchemaContract::isRepresentative)
						.orElseThrow();
				}
			);
			assertTrue(
				representativeOnClient,
				"The catalog schema delivered over gRPC must keep the `representative` flag of global attribute `code`."
			);
		}
	}

	@DisplayName("restarted client re-defining an unchanged representative global attribute emits no mutation")
	@UseDataSet(DATA_SET)
	@Test
	void shouldEmitNoMutationWhenRestartedClientRedefinesUnchangedSchema(
		@Nonnull EvitaClient evitaClient,
		@Nonnull EvitaServer evitaServer
	) {
		final String catalog = "redefinitionReanalysis";
		defineBrandAndArticleSchemas(evitaClient, catalog);

		try (final EvitaClient restartedClient = newClient(evitaServer)) {
			final AtomicReference<LocalCatalogSchemaMutation[]> captured = new AtomicReference<>();
			restartedClient.updateCatalog(
				catalog,
				session -> {
					session.defineEntitySchemaFromModelClass(BrandModel.class, capturingInto(captured));
				}
			);

			final LocalCatalogSchemaMutation[] mutations = captured.get();
			assertNotNull(mutations, "The analysis result was not captured.");
			assertEquals(
				0, mutations.length,
				"Re-defining an unchanged schema must emit no mutation, but got " + mutations.length + ":\n" +
					String.join("\n", Arrays.stream(mutations).map(Object::toString).toList())
			);
		}
	}

	/**
	 * A decimal range default value created without explicit retained decimal places compares at the scale of its own
	 * bounds. The schema the client reads back must carry that precision, otherwise the builder finds every unchanged
	 * re-definition a change of the default value.
	 *
	 * @param evitaClient client defining the schema first
	 * @param evitaServer the running server
	 */
	@DisplayName("re-defining unchanged decimal range default values through the schema builder changes nothing")
	@UseDataSet(DATA_SET)
	@Tag(ATTRIBUTE)
	@Test
	void shouldEmitNoMutationWhenClientsRedefineUnchangedDecimalRangeDefaultsThroughBuilder(
		@Nonnull EvitaClient evitaClient,
		@Nonnull EvitaServer evitaServer
	) {
		assertRedefinitionOfRangeDefaultsChangesNothing(
			evitaClient, evitaServer, "redefinitionRangeDefaultsBuilder",
			EvitaClientSchemaRedefinitionTest::defineRangeHolderThroughBuilder
		);
	}

	/**
	 * The model-class variant of the builder scenario: the analyzer compares the default value a getter returns with
	 * the one the client reads back, so a precision lost on the way would make every start-up of an application with an
	 * unchanged model emit a change of the default value.
	 *
	 * @param evitaClient client defining the schema first
	 * @param evitaServer the running server
	 */
	@DisplayName("re-defining an unchanged model with decimal range default values changes nothing")
	@UseDataSet(DATA_SET)
	@Tag(ATTRIBUTE)
	@Test
	void shouldEmitNoMutationWhenClientsRedefineUnchangedModelWithDecimalRangeDefaults(
		@Nonnull EvitaClient evitaClient,
		@Nonnull EvitaServer evitaServer
	) {
		assertRedefinitionOfRangeDefaultsChangesNothing(
			evitaClient, evitaServer, "redefinitionRangeDefaultsModel",
			EvitaClientSchemaRedefinitionTest::defineRangeHolderFromModelClass
		);
	}

	@DisplayName("start-up re-definition of an unchanged schema does not conflict with a concurrent entity write")
	@UseDataSet(DATA_SET)
	@Tag(TRANSACTION)
	@Test
	void shouldNotConflictWithConcurrentWriterWhenRedefinedSchemaIsUnchanged(
		@Nonnull EvitaClient evitaClient,
		@Nonnull EvitaServer evitaServer
	) {
		final String catalog = "redefinitionConcurrentWriter";
		defineCatalogWithLiveArticle(evitaClient, catalog);

		try (final EvitaClient restartedClient = newClient(evitaServer)) {
			// the new application instance starts its schema reconciliation ...
			final EvitaSessionContract startupSession = restartedClient.createReadWriteSession(catalog);
			startupSession.defineEntitySchemaFromModelClass(BrandModel.class);

			// ... while the old instance, still running, writes an entity of an unrelated collection
			updateArticleAsOldInstance(evitaClient, catalog);

			assertDoesNotThrow(
				startupSession::close,
				"Re-defining an unchanged schema must not conflict with a concurrent write of another collection."
			);
		}
	}

	@DisplayName("start-up re-definition changing a global attribute conflicts with a concurrent entity write")
	@UseDataSet(DATA_SET)
	@Tag(TRANSACTION)
	@Test
	void shouldConflictWithConcurrentWriterWhenRedefinitionChangesGlobalAttribute(
		@Nonnull EvitaClient evitaClient,
		@Nonnull EvitaServer evitaServer
	) {
		final String catalog = "redefinitionConcurrentWriterControl";
		defineCatalogWithLiveArticle(evitaClient, catalog);

		try (final EvitaClient restartedClient = newClient(evitaServer)) {
			// the new application instance changes the global attribute during its schema reconciliation ...
			final EvitaSessionContract startupSession = restartedClient.createReadWriteSession(catalog);
			startupSession.defineEntitySchemaFromModelClass(BrandModel.class, CHANGE_CODE_DESCRIPTION);

			// ... while the old instance, still running, writes an entity of an unrelated collection
			updateArticleAsOldInstance(evitaClient, catalog);

			// the conflict shows that the same scenario with an unchanged schema would fail too, if the re-definition
			// still emitted a mutation of the global attribute
			final RuntimeException conflict = assertThrows(
				RuntimeException.class,
				startupSession::close,
				"A genuine change of a global attribute must conflict with a concurrent write of another collection."
			);
			assertTrue(
				String.valueOf(conflict.getMessage()).contains("Conflicting mutations detected"),
				() -> "The commit must fail on the conflict, but failed with: " + conflict
			);
		}
	}

	@DisplayName("removing and re-creating a reference in one schema update keeps the references stored entities hold")
	@UseDataSet(DATA_SET)
	@Test
	void shouldKeepStoredReferencesWhenReferenceIsRemovedAndRecreatedInOneUpdate(@Nonnull EvitaClient evitaClient) {
		final String catalog = "redefinitionRecreatedReference";
		evitaClient.defineCatalog(catalog);
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_ARTICLE)
					.withReferenceToEntity(
						REFERENCE_ARTICLE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexedForFiltering().withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable())
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_BRAND, 1).upsertVia(session);
				session.createNewEntity(ENTITY_ARTICLE, 1)
					.setReference(REFERENCE_ARTICLE_BRAND, 1, thatIs -> thatIs.setAttribute(ATTRIBUTE_ORDER, 7))
					.upsertVia(session);
			}
		);

		// the reference is removed and re-created with another cardinality and a described attribute in one update
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.getEntitySchemaOrThrowException(ENTITY_ARTICLE)
					.openForWrite()
					.withoutReferenceTo(REFERENCE_ARTICLE_BRAND)
					.withReferenceToEntity(
						REFERENCE_ARTICLE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFiltering().withAttribute(
							ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable().withDescription("Order of the brand.")
						)
					)
					.updateVia(session);
			}
		);

		evitaClient.queryCatalog(
			catalog,
			session -> {
				final ReferenceSchemaContract reference = session.getEntitySchemaOrThrowException(ENTITY_ARTICLE)
					.getReferenceOrThrowException(REFERENCE_ARTICLE_BRAND);
				assertEquals(Cardinality.ZERO_OR_MORE, reference.getCardinality());
				assertEquals("Order of the brand.", reference.getAttribute(ATTRIBUTE_ORDER).orElseThrow().getDescription());

				final ReferenceContract storedReference = session.getEntity(ENTITY_ARTICLE, 1, referenceContentAllWithAttributes())
					.orElseThrow()
					.getReference(REFERENCE_ARTICLE_BRAND, 1)
					.orElseThrow();
				assertEquals(Integer.valueOf(7), storedReference.getAttribute(ATTRIBUTE_ORDER));
				// the reference index still answers a filter on the reference attribute
				assertEquals(
					List.of(1),
					session.queryListOfEntityReferences(
							query(
								collection(ENTITY_ARTICLE),
								filterBy(referenceHaving(REFERENCE_ARTICLE_BRAND, attributeEquals(ATTRIBUTE_ORDER, 7)))
							)
						)
						.stream()
						.map(EntityReferenceContract::getPrimaryKey)
						.toList()
				);
			}
		);
	}

	@DisplayName("re-creating a plain reference as an indexed and faceted one and back in one update each reaches the server")
	@UseDataSet(DATA_SET)
	@Test
	@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void shouldIndexFacetAndPlainAgainReferenceRecreatedInOneUpdate(@Nonnull EvitaClient evitaClient) {
		final String catalog = "redefinitionRecreatedFacetedReference";
		evitaClient.defineCatalog(catalog);
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_ARTICLE)
					.withReferenceToEntity(REFERENCE_ARTICLE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE)
					.updateVia(session);
				session.createNewEntity(ENTITY_BRAND, 1).upsertVia(session);
				session.createNewEntity(ENTITY_ARTICLE, 1)
					.setReference(REFERENCE_ARTICLE_BRAND, 1)
					.upsertVia(session);
			}
		);

		// the removal and the re-creation collapse into two index changes and a change of the faceting, which the
		// server must accept in the order the builder sends them
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.getEntitySchemaOrThrowException(ENTITY_ARTICLE)
					.openForWrite()
					.withoutReferenceTo(REFERENCE_ARTICLE_BRAND)
					.withReferenceToEntity(
						REFERENCE_ARTICLE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexedForFiltering()
							.faceted()
							.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.nullable())
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_ARTICLE, 2)
					.setReference(REFERENCE_ARTICLE_BRAND, 1)
					.upsertVia(session);
			}
		);
		evitaClient.queryCatalog(
			catalog,
			session -> {
				final ReferenceSchemaContract reference = session.getEntitySchemaOrThrowException(ENTITY_ARTICLE)
					.getReferenceOrThrowException(REFERENCE_ARTICLE_BRAND);
				assertTrue(reference.isIndexed());
				assertTrue(reference.isFaceted());
				// an article written after the change is in the facet index
				assertTrue(
					session.queryListOfEntityReferences(
							query(
								collection(ENTITY_ARTICLE),
								filterBy(facetHaving(REFERENCE_ARTICLE_BRAND, entityPrimaryKeyInSet(1)))
							)
						)
						.stream()
						.map(EntityReferenceContract::getPrimaryKey)
						.toList()
						.contains(2)
				);
			}
		);

		// and back: the faceting must not get lost among the index changes and the removal of the attribute
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.getEntitySchemaOrThrowException(ENTITY_ARTICLE)
					.openForWrite()
					.withoutReferenceTo(REFERENCE_ARTICLE_BRAND)
					.withReferenceToEntity(REFERENCE_ARTICLE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE)
					.updateVia(session);
			}
		);
		evitaClient.queryCatalog(
			catalog,
			session -> {
				final ReferenceSchemaContract reference = session.getEntitySchemaOrThrowException(ENTITY_ARTICLE)
					.getReferenceOrThrowException(REFERENCE_ARTICLE_BRAND);
				assertFalse(reference.isIndexed());
				assertFalse(reference.isFaceted());
				assertTrue(reference.getAttributes().isEmpty());
				assertTrue(
					session.getEntity(ENTITY_ARTICLE, 1, referenceContentAllWithAttributes())
						.orElseThrow()
						.getReference(REFERENCE_ARTICLE_BRAND, 1)
						.isPresent()
				);
			}
		);
	}

	@DisplayName("declared and inherited components of a reflected reference with inherited indexing reach the server")
	@UseDataSet(DATA_SET)
	@Test
	@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void shouldKeepDeclaredComponentsOfReflectedReferenceWithInheritedIndexing(@Nonnull EvitaClient evitaClient) {
		final String catalog = "redefinitionReflectedComponents";
		evitaClient.defineCatalog(catalog);
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
				session.defineEntitySchema(ENTITY_ARTICLE)
					.withReferenceToEntity(
						REFERENCE_ARTICLE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.withGroupType(ENTITY_BRAND_GROUP).indexedForFiltering()
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_BRAND)
					.withReflectedReferenceToEntity(REFERENCE_BRAND_ARTICLES, ENTITY_ARTICLE, REFERENCE_ARTICLE_BRAND)
					.updateVia(session);
			}
		);

		// the declared indexing in between is overridden, the components it declares are not - the builder sends
		// them beside the inherited index types, which the server must not drop
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.getEntitySchemaOrThrowException(ENTITY_BRAND)
					.openForWrite()
					.withReflectedReferenceToEntity(
						REFERENCE_BRAND_ARTICLES, ENTITY_ARTICLE, REFERENCE_ARTICLE_BRAND,
						whichIs -> whichIs
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.indexedInScope((Scope[]) null)
					)
					.updateVia(session);
			}
		);
		evitaClient.queryCatalog(
			catalog,
			session -> {
				final ReflectedReferenceSchemaContract reference = (ReflectedReferenceSchemaContract)
					session.getEntitySchemaOrThrowException(ENTITY_BRAND)
						.getReferenceOrThrowException(REFERENCE_BRAND_ARTICLES);
				assertTrue(reference.isIndexedInherited());
				assertFalse(reference.isIndexedComponentsInherited());
				assertEquals(
					Set.of(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					),
					reference.getIndexedComponents(Scope.LIVE)
				);
			}
		);

		// re-created inheriting everything, the components become inherited too
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.getEntitySchemaOrThrowException(ENTITY_BRAND)
					.openForWrite()
					.withoutReferenceTo(REFERENCE_BRAND_ARTICLES)
					.withReflectedReferenceToEntity(REFERENCE_BRAND_ARTICLES, ENTITY_ARTICLE, REFERENCE_ARTICLE_BRAND)
					.updateVia(session);
			}
		);
		evitaClient.queryCatalog(
			catalog,
			session -> {
				final ReflectedReferenceSchemaContract reference = (ReflectedReferenceSchemaContract)
					session.getEntitySchemaOrThrowException(ENTITY_BRAND)
						.getReferenceOrThrowException(REFERENCE_BRAND_ARTICLES);
				assertTrue(reference.isIndexedInherited());
				assertTrue(reference.isIndexedComponentsInherited());
			}
		);
	}

	/**
	 * Defines the catalog with the brand and article schemas through the given client.
	 *
	 * @param evitaClient client to use
	 * @param catalog     name of the catalog to create
	 */
	private static void defineBrandAndArticleSchemas(@Nonnull EvitaClient evitaClient, @Nonnull String catalog) {
		evitaClient.defineCatalog(catalog);
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.defineEntitySchemaFromModelClass(BrandModel.class);
				session.defineEntitySchemaFromModelClass(ArticleModel.class);
			}
		);
	}

	/**
	 * Defines the brand and article schemas, creates one article and switches the catalog to the transactional mode,
	 * in which concurrent sessions are checked for conflicts.
	 *
	 * @param evitaClient client to use
	 * @param catalog     name of the catalog to create
	 */
	private static void defineCatalogWithLiveArticle(@Nonnull EvitaClient evitaClient, @Nonnull String catalog) {
		defineBrandAndArticleSchemas(evitaClient, catalog);
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.createNewEntity(ENTITY_ARTICLE, 1)
					.setAttribute(ATTRIBUTE_TITLE, "initial")
					.upsertVia(session);
			}
		);
		evitaClient.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Changes the title of the article in its own transaction, as the old application instance still running does.
	 *
	 * @param evitaClient client of the old application instance
	 * @param catalog     name of the catalog
	 */
	private static void updateArticleAsOldInstance(@Nonnull EvitaClient evitaClient, @Nonnull String catalog) {
		evitaClient.updateCatalog(
			catalog,
			session -> {
				session.getEntity(ENTITY_ARTICLE, 1, attributeContentAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_TITLE, "changed by the old instance")
					.upsertVia(session);
			}
		);
	}

	/**
	 * Defines the range holder schema through the given client, switches the catalog to the transactional mode and
	 * re-defines the unchanged schema through the same client and through a restarted one. Asserts that neither
	 * re-definition emits a mutation, that the versions of the catalog, of its schema and of the entity schema stay put
	 * and that the default values a restarted client reads back render and compare like the ones defined.
	 *
	 * @param evitaClient client defining the schema first
	 * @param evitaServer the running server
	 * @param catalog     name of the catalog to create
	 * @param definition  defines the range holder schema through the given client in the given catalog and returns the
	 *                    schema mutations it emitted
	 */
	private static void assertRedefinitionOfRangeDefaultsChangesNothing(
		@Nonnull EvitaClient evitaClient,
		@Nonnull EvitaServer evitaServer,
		@Nonnull String catalog,
		@Nonnull BiFunction<EvitaClient, String, List<String>> definition
	) {
		evitaClient.defineCatalog(catalog);
		definition.apply(evitaClient, catalog);
		// in the transactional mode every committed schema mutation advances the catalog version and the catalog schema
		// version, even one that leaves the entity schema as it was
		evitaClient.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		final RangeHolderVersions definedVersions = readVersionsOnServer(evitaServer, catalog);

		final List<String> mutationsOfOriginalClient = definition.apply(evitaClient, catalog);
		final List<String> mutationsOfRestartedClient;
		final RangeDefaults readBack;
		try (final EvitaClient restartedClient = newClient(evitaServer)) {
			mutationsOfRestartedClient = definition.apply(restartedClient, catalog);
			readBack = readRangeDefaults(restartedClient, catalog);
		}
		final RangeHolderVersions redefinedVersions = readVersionsOnServer(evitaServer, catalog);

		assertAll(
			() -> assertEquals(
				List.of(), mutationsOfOriginalClient,
				"Re-defining the unchanged schema through the client that defined it must emit no mutation."
			),
			() -> assertEquals(
				List.of(), mutationsOfRestartedClient,
				"Re-defining the unchanged schema through a restarted client must emit no mutation."
			),
			() -> assertEquals(
				definedVersions, redefinedVersions,
				"Re-defining the unchanged schema must advance no version."
			),
			() -> assertReadBackAsDefined(ATTRIBUTE_ZERO_RANGE, ZERO_RANGE_DEFAULT, readBack.zeroRange()),
			() -> assertReadBackAsDefined(
				ATTRIBUTE_FRACTIONAL_RANGE, FRACTIONAL_RANGE_DEFAULT, readBack.fractionalRange()
			)
		);
	}

	/**
	 * Defines the range holder schema through the schema builder of the given client.
	 *
	 * @param client  client to use
	 * @param catalog name of the catalog
	 * @return the schema mutations the builder emitted, empty when it found nothing to change
	 */
	@Nonnull
	private static List<String> defineRangeHolderThroughBuilder(@Nonnull EvitaClient client, @Nonnull String catalog) {
		return client.updateCatalog(
			catalog,
			session -> {
				final EntitySchemaBuilder builder = session.defineEntitySchema(ENTITY_RANGE_HOLDER)
					.withAttribute(
						ATTRIBUTE_ZERO_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.withDefaultValue(ZERO_RANGE_DEFAULT)
					)
					.withAttribute(
						ATTRIBUTE_FRACTIONAL_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.withDefaultValue(FRACTIONAL_RANGE_DEFAULT)
					);
				final List<String> mutations = builder.toMutation()
					.map(it -> Arrays.stream(it.getSchemaMutations()).map(Object::toString).toList())
					.orElse(List.of());
				builder.updateVia(session);
				return mutations;
			}
		);
	}

	/**
	 * Defines the range holder schema from {@link RangeHolderModel} through the given client.
	 *
	 * @param client  client to use
	 * @param catalog name of the catalog
	 * @return the schema mutations the analysis of the model emitted, empty when it found nothing to change
	 */
	@Nonnull
	private static List<String> defineRangeHolderFromModelClass(@Nonnull EvitaClient client, @Nonnull String catalog) {
		final AtomicReference<LocalCatalogSchemaMutation[]> captured = new AtomicReference<>();
		client.updateCatalog(
			catalog,
			session -> {
				session.defineEntitySchemaFromModelClass(RangeHolderModel.class, capturingInto(captured));
			}
		);
		final LocalCatalogSchemaMutation[] mutations = captured.get();
		assertNotNull(mutations, "The analysis result was not captured.");
		return Arrays.stream(mutations).map(Object::toString).toList();
	}

	/**
	 * Reads the versions of the catalog, of its schema and of the range holder schema directly on the server.
	 *
	 * @param evitaServer the running server
	 * @param catalog     name of the catalog
	 * @return the versions the server holds
	 */
	@Nonnull
	private static RangeHolderVersions readVersionsOnServer(@Nonnull EvitaServer evitaServer, @Nonnull String catalog) {
		// block lambda with `return` selects the Function overload of `queryCatalog` unambiguously
		return evitaServer.getEvita().queryCatalog(
			catalog,
			session -> {
				return new RangeHolderVersions(
					session.getCatalogVersion(),
					session.getCatalogSchema().version(),
					session.getEntitySchemaOrThrowException(ENTITY_RANGE_HOLDER).version()
				);
			}
		);
	}

	/**
	 * Reads the default values of both range attributes of the range holder schema through the given client.
	 *
	 * @param client  client to use
	 * @param catalog name of the catalog
	 * @return the default values the client reads
	 */
	@Nonnull
	private static RangeDefaults readRangeDefaults(@Nonnull EvitaClient client, @Nonnull String catalog) {
		return client.queryCatalog(
			catalog,
			session -> {
				final SealedEntitySchema schema = session.getEntitySchemaOrThrowException(ENTITY_RANGE_HOLDER);
				return new RangeDefaults(
					schema.getAttribute(ATTRIBUTE_ZERO_RANGE).orElseThrow().getDefaultValue(),
					schema.getAttribute(ATTRIBUTE_FRACTIONAL_RANGE).orElseThrow().getDefaultValue()
				);
			}
		);
	}

	/**
	 * Asserts that the default value read back is a decimal range rendering like the one defined and comparing at the
	 * same effective precision.
	 *
	 * @param attributeName name of the attribute, for the failure messages
	 * @param defined       the default value defined
	 * @param readBack      the default value read back
	 */
	private static void assertReadBackAsDefined(
		@Nonnull String attributeName,
		@Nonnull BigDecimalNumberRange defined,
		@Nullable Serializable readBack
	) {
		final BigDecimalNumberRange readBackRange = assertInstanceOf(
			BigDecimalNumberRange.class, readBack,
			"The default value of `" + attributeName + "` must be read back as a decimal range."
		);
		assertAll(
			() -> assertEquals(
				defined.toString(), readBackRange.toString(),
				"Rendering of the default value of `" + attributeName + "`."
			),
			() -> assertEquals(
				defined.getEffectiveRetainedDecimalPlaces(), readBackRange.getEffectiveRetainedDecimalPlaces(),
				"Effective retained decimal places of the default value of `" + attributeName + "`."
			)
		);
	}

	/**
	 * Creates a post-processor that changes nothing and stores the resulting mutations into the given reference.
	 *
	 * @param captured reference receiving the mutations
	 * @return the capturing post-processor
	 */
	@Nonnull
	private static SchemaPostProcessorCapturingResult capturingInto(
		@Nonnull AtomicReference<LocalCatalogSchemaMutation[]> captured
	) {
		return new SchemaPostProcessorCapturingResult() {
			@Override
			public void postProcess(
				@Nonnull CatalogSchemaBuilder catalogSchemaBuilder,
				@Nonnull EntitySchemaBuilder entitySchemaBuilder
			) {
				// the model class alone defines the schema
			}

			@Override
			public void captureResult(@Nonnull LocalCatalogSchemaMutation[] mutations) {
				captured.set(mutations);
			}
		};
	}

	/**
	 * Creates a new client connected to the server, configured like the one the test extension injects, so that it
	 * starts with an empty schema cache - exactly what a restarted application gets.
	 *
	 * @param evitaServer the running server
	 * @return a new client the caller must close
	 */
	@Nonnull
	private static EvitaClient newClient(@Nonnull EvitaServer evitaServer) {
		final AbstractApiOptions grpcConfig = evitaServer.getExternalApiServer()
			.getApiOptions()
			.getEndpointConfiguration(GrpcProvider.CODE);
		final AbstractApiOptions systemConfig = evitaServer.getExternalApiServer()
			.getApiOptions()
			.getEndpointConfiguration(SystemProvider.CODE);
		assertNotNull(grpcConfig, "The gRPC API must be open.");
		assertNotNull(systemConfig, "The system API must be open.");
		return new EvitaClient(
			EvitaClientConfiguration.builder()
				.host(grpcConfig.getHost()[0].hostAddress())
				.port(grpcConfig.getHost()[0].port())
				.systemApiPort(systemConfig.getHost()[0].port())
				// the injected client disables the keep-alive ping for the test lane as well
				.pingIntervalMillis(0)
				.tls(
					ClientTlsOptions.builder()
						.certificateFolderPath(
							Path.of(evitaServer.getEvita().getConfiguration().storage().storageDirectory() + "-client")
						)
						.build()
				)
				.timeouts(
					ClientTimeoutOptions.builder()
						.timeout(10, TimeUnit.MINUTES)
						.streamingTimeout(10, TimeUnit.MINUTES)
						.build()
				)
				.build()
		);
	}

	/**
	 * Brand entity sharing a global, representative `code` attribute unique within the collection in both scopes.
	 */
	@Entity(name = ENTITY_BRAND)
	public interface BrandModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the global, representative code.
		 */
		@Attribute(
			name = ATTRIBUTE_CODE,
			global = true,
			representative = true,
			scope = {
				@ScopeAttributeSettings(
					scope = Scope.LIVE, unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, sortable = true
				),
				@ScopeAttributeSettings(scope = Scope.ARCHIVED, unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		)
		@Nonnull
		String getCode();

	}

	/**
	 * Article entity with no global attribute; the concurrent writer modifies it.
	 */
	@Entity(name = ENTITY_ARTICLE)
	public interface ArticleModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the article title.
		 */
		@Attribute(name = ATTRIBUTE_TITLE, nullable = true)
		@Nullable
		String getTitle();

	}

	/**
	 * Entity with two decimal range attributes whose default values carry no explicit retained decimal places.
	 */
	@Entity(name = ENTITY_RANGE_HOLDER)
	public interface RangeHolderModel {

		/**
		 * Returns the primary key.
		 */
		@PrimaryKey(autoGenerate = false)
		int getId();

		/**
		 * Returns the range whose default value encodes to the same bounds at any precision.
		 */
		@Attribute(name = ATTRIBUTE_ZERO_RANGE)
		@Nonnull
		default BigDecimalNumberRange getZeroRange() {
			return ZERO_RANGE_DEFAULT;
		}

		/**
		 * Returns the range whose default value encodes to other bounds at another precision.
		 */
		@Attribute(name = ATTRIBUTE_FRACTIONAL_RANGE)
		@Nonnull
		default BigDecimalNumberRange getFractionalRange() {
			return FRACTIONAL_RANGE_DEFAULT;
		}

	}

	/**
	 * Versions the server holds for a catalog with the range holder schema.
	 *
	 * @param catalogVersion       version of the catalog, advanced by every committed transaction with a mutation
	 * @param catalogSchemaVersion version of the catalog schema
	 * @param entitySchemaVersion  version of the range holder schema
	 */
	private record RangeHolderVersions(long catalogVersion, int catalogSchemaVersion, int entitySchemaVersion) {
	}

	/**
	 * Default values of the two range attributes of the range holder schema as a client reads them.
	 *
	 * @param zeroRange       default value of the `zeroRange` attribute
	 * @param fractionalRange default value of the `fractionalRange` attribute
	 */
	private record RangeDefaults(@Nullable Serializable zeroRange, @Nullable Serializable fractionalRange) {
	}

}
