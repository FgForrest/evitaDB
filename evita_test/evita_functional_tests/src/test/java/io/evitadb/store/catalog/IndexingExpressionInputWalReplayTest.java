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
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.spi.store.catalog.wal.IsolatedWalPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.evitadb.core.expression.trigger.IndexingExpressionInputCells.hasCatalogFrame;
import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies what a catalog does on start when the unincorporated tail of its write-ahead log holds a schema change
 * carrying an indexing expression the definition-time check refuses - a transaction a release that admitted the read
 * committed but did not incorporate before it stopped. The replay applies the change through the same
 * `Catalog#updateSchema` a session uses, so its preflight refuses it; the catalog must then report as corrupted, the
 * way a stored schema with such an expression does, and publish nothing.
 *
 * The tail is produced without a binary fixture: the engine writes an alive catalog, and with the engine stopped the
 * transaction is appended through the catalog's own write-ahead log writer - the isolated log of a transaction and
 * the shared log append - exactly as a commit appends it. The test covers an alive catalog only, because only its
 * sessions run in transactions (`Catalog#supportsTransaction`), and only a transaction reaches the write-ahead log.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A refused indexing expression in the unincorporated write-ahead log")
@Tag(STORAGE)
@Tag(WAL)
@Tag(SCHEMA)
class IndexingExpressionInputWalReplayTest implements EvitaTestSupport {

	private static final String BRAND = "brand";
	private static final String PRODUCT = "product";
	private static final String REFERENCE_NAME = "brand";
	private static final String HISTOGRAM_NAME = "shares";
	private static final String VALUE = "$reference.attributes['share']";
	private static final String PLACEHOLDER = "$entity.attributes['status'] == 'A'";
	private static final String CONTROL = "$entity.attributes['status'] == 'B'";
	private static final String VERSION_READ = "$entity.version >= 3";

	/**
	 * Stores an alive catalog, appends a transaction changing the `bucketedPartially` condition to the write-ahead
	 * log with the engine stopped, and starts the engine over it.
	 *
	 * @param condition the condition the appended transaction sets; the refused read or the accepted control
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {VERSION_READ, CONTROL})
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("reports the catalog corrupted and publishes nothing; the accepted control is replayed")
	void shouldReportCatalogCorruptedWhenWalTailCarriesRefusedExpression(@Nonnull String condition) {
		final boolean refused = VERSION_READ.equals(condition);
		final TestPaths paths = createTestPaths("IndexingExpressionInputWalReplay_" + refused);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try {
			final CatalogSchemaContract catalogSchema;
			final EntitySchemaContract productSchema;
			try (Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					defineSchema(session);
					session.goLiveAndClose();
				});
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.createNewEntity(BRAND, 1).upsertVia(session);
				});
				catalogSchema = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema);
				productSchema = evita.queryCatalog(TEST_CATALOG, session -> {
					return session.getEntitySchemaOrThrowException(PRODUCT);
				});
			}
			final long appendedVersion = appendSchemaChangeToWal(
				configuration, catalogSchema, productSchema, ExpressionFactory.parse(condition)
			);
			final CatalogBootstrap beforeStart = lastBootstrap(configuration, TEST_CATALOG);
			assertTrue(
				beforeStart.catalogVersion() < appendedVersion,
				"the appended transaction must be beyond the published version " + beforeStart.catalogVersion()
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
					assertTrue(cause.getMessage().contains("cannot read `$entity.version`"), cause.getMessage());
					// the replayed schema change meets the payload preflight, not the check of a stored schema
					assertTrue(
						hasCatalogFrame(cause, "verifyEntitySchemaMutationsApplicable"),
						"the refusal must come from the payload preflight of the replay: " + cause.getMessage()
					);
				} else {
					reopened.waitUntilFullyInitialized();
					assertEquals(CatalogState.ALIVE, reopened.getCatalogState(TEST_CATALOG).orElseThrow());
					final Expression stored = reopened.queryCatalog(
						TEST_CATALOG,
						session -> {
							return session.getEntitySchemaOrThrowException(PRODUCT)
								.getReference(REFERENCE_NAME)
								.map(ReferenceSchemaContract::getBucketedPartiallyInScopes)
								.map(conditions -> conditions.get(Scope.LIVE))
								.orElse(null);
						}
					);
					assertNotNull(stored, "the replayed condition must be in the schema");
					assertEquals(condition, stored.toExpressionString(), "the replay must apply the appended change");
				}
			} finally {
				reopened.close();
			}
			if (refused) {
				assertEquals(
					beforeStart, lastBootstrap(configuration, TEST_CATALOG),
					"a catalog whose replay failed must publish nothing"
				);
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Defines the referenced type and the owner with a histogram conditioned on an accepted read.
	 *
	 * @param session the session
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey()
			.withAttribute("status", String.class, attribute -> attribute.filterable().nullable())
			.withReferenceToEntity(REFERENCE_NAME, BRAND, Cardinality.ZERO_OR_MORE, reference -> reference
				.indexedForFiltering()
				.withAttribute("share", Integer.class, attribute -> attribute.filterable().nullable())
				.bucketedInScope(Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE), null)
				.bucketedPartiallyInScope(Scope.LIVE, ExpressionFactory.parse(PLACEHOLDER))
			)
			.updateVia(session);
	}

	/**
	 * Appends, with the engine stopped, one committed transaction holding a schema change that sets the
	 * `bucketedPartially` condition of the owner's reference, the way a commit appends it: the mutation is written into
	 * the isolated log of the transaction, which is then appended to the shared write-ahead log and made durable.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogSchema the catalog schema the transaction runs on
	 * @param productSchema the schema of the owner type
	 * @param condition     the condition the change sets
	 * @return the catalog version of the appended transaction
	 */
	private static long appendSchemaChangeToWal(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract productSchema,
		@Nonnull Expression condition
	) {
		return withOfflineCatalog(
			configuration, TEST_CATALOG,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final long walVersion = service.getLastCatalogVersionInMutationStream();
				assertEquals(catalogVersion, walVersion, "the stored catalog must have incorporated its whole log");
				final long nextVersion = catalogVersion + 1;
				final UUID transactionId = UUIDUtil.randomUUID();
				final IsolatedWalPersistenceService transactionLog = service.createIsolatedWalPersistenceService(
					transactionId, catalogSchema,
					entityType -> PRODUCT.equals(entityType) ? productSchema : null
				);
				transactionLog.write(
					catalogVersion,
					new ModifyEntitySchemaMutation(
						PRODUCT,
						new SetReferenceSchemaBucketedMutation(
							REFERENCE_NAME,
							new ScopedHistogramIndexDefinition[]{
								new ScopedHistogramIndexDefinition(
									Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(VALUE), null
								)
							},
							new ScopedBucketedPartially[]{new ScopedBucketedPartially(Scope.LIVE, condition)}
						)
					)
				);
				service.appendWalAndDiscardDeferringSync(
					catalogVersion,
					new TransactionMutation(
						transactionId, nextVersion, transactionLog.getMutationCount(),
						transactionLog.getMutationSizeInBytes(), OffsetDateTime.now()
					),
					transactionLog.getWalReference()
				);
				service.syncWal();
				return nextVersion;
			}
		);
	}

}
