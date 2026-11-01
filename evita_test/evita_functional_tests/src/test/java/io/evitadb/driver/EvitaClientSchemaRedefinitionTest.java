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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentAllWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

}
