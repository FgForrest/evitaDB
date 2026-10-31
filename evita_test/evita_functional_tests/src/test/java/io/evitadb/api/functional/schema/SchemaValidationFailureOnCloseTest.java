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

package io.evitadb.api.functional.schema;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.annotation.Nonnull;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.wordList;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Verifies that a catalog-schema validation which fails with an exception that is not a schema refusal publishes
 * nothing - neither by committing the transaction of an ALIVE catalog, nor through a later flush of a WARMING_UP one,
 * nor by making a WARMING_UP catalog go live.
 *
 * Every refusal `validate()` is known to raise belongs to the `EvitaInvalidUsageException` family, so the failure is
 * injected: a static mock makes `FulltextSchemaValidator`, which `CatalogSchema#validate()` always calls, throw a
 * plain {@link IllegalStateException} - the shape an engine defect uncovered by the validation walk would take.
 * Static mocks are bound to the thread that opens them, and both the session close and the go-live operator validate
 * on the calling thread, so the mock is opened around that call alone: the schema change before it applies
 * unaffected, and the asynchronous deactivation and the reads after it run against the real validator.
 *
 * Each case reads its outcome where it would have been published: the ALIVE case after a later commit that waits for
 * its changes to become visible (commits are applied in order, so an earlier commit would be visible by then - and
 * would make the later one conflict with it), the warm-up cases after reopening the catalog from disk.
 */
@DisplayName("Schema validation that fails before a publication must not publish")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SCHEMA)
class SchemaValidationFailureOnCloseTest implements EvitaTestSupport {
	/** Fully qualified name of the package-private validator every `CatalogSchema#validate()` call reaches. */
	private static final String VALIDATOR_CLASS = "io.evitadb.api.requestResponse.schema.dto.FulltextSchemaValidator";
	/** Message of the injected validation failure, so the assertions know the failure is the injected one. */
	private static final String INJECTED_FAILURE = "validation broke";
	/** Resource declared by the session whose validation fails. */
	private static final String REFUSED_RESOURCE = "refused";
	/** Resource declared by a session that closes normally. */
	private static final String ACCEPTED_RESOURCE = "accepted";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("SchemaValidationFailureOnClose");
		this.evita = new Evita(configuration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("should roll back the transaction of an ALIVE catalog on close")
	void shouldRollBackTheTransactionOfAnAliveCatalogOnClose() {
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);

		final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG);
		declareResource(session, REFUSED_RESOURCE);
		// the rollback is reported the way a refused schema is - as a rollback carrying the failure as its cause
		final RollbackException exception = assertThrows(
			RollbackException.class, () -> withFailingValidation(session::close)
		);
		assertInjectedFailure(exception);

		this.evita.updateCatalog(TEST_CATALOG, (EvitaSessionContract it) -> declareResource(it, ACCEPTED_RESOURCE));
		assertOnlyTheAcceptedResource(
			this.evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema)
		);
	}

	@Test
	@DisplayName("should raise the unpublishable barrier of a WARMING_UP catalog on close")
	void shouldRaiseTheUnpublishableBarrierOfAWarmingUpCatalogOnClose() {
		this.evita.updateCatalog(TEST_CATALOG, (EvitaSessionContract it) -> declareResource(it, ACCEPTED_RESOURCE));

		final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG);
		declareResource(session, REFUSED_RESOURCE);
		final GenericEvitaInternalError exception = CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
			this.evita, TEST_CATALOG, GenericEvitaInternalError.class, () -> withFailingValidation(session::close)
		);
		assertInjectedFailure(exception);

		assertOnlyTheAcceptedResource(reopenAndReadCatalogSchema());
	}

	@Test
	@DisplayName("should raise the unpublishable barrier when a WARMING_UP catalog goes live")
	void shouldRaiseTheUnpublishableBarrierWhenAWarmingUpCatalogGoesLive() {
		this.evita.updateCatalog(TEST_CATALOG, (EvitaSessionContract it) -> declareResource(it, ACCEPTED_RESOURCE));

		final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG);
		declareResource(session, REFUSED_RESOURCE);
		final GenericEvitaInternalError exception = CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
			this.evita, TEST_CATALOG, GenericEvitaInternalError.class,
			() -> withFailingValidation(session::goLiveAndClose)
		);
		assertEquals("Unexpected internal Evita error occurred: " + INJECTED_FAILURE, exception.getMessage());

		assertOnlyTheAcceptedResource(reopenAndReadCatalogSchema());
	}

	/**
	 * Declares a word-list resource in the catalog schema - a catalog-level change, so the catalog schema version
	 * moves and the publication validates it.
	 *
	 * @param session the read-write session to declare the resource in
	 * @param name    the name of the resource
	 */
	private static void declareResource(@Nonnull EvitaSessionContract session, @Nonnull String name) {
		session.getCatalogSchema()
			.openForWrite()
			.withFulltextResource(name, wordList("puma"))
			.updateVia(session);
	}

	/**
	 * Runs the action while the catalog-schema validation throws the injected failure on this thread.
	 *
	 * @param action the action that validates the catalog schema - a session close or a go-live
	 */
	private static void withFailingValidation(@Nonnull Runnable action) {
		try (
			MockedStatic<?> ignored = mockStatic(
				validatorClass(),
				invocation -> {
					throw new IllegalStateException(INJECTED_FAILURE);
				}
			)
		) {
			action.run();
		}
	}

	/**
	 * Asserts that the exception carries the injected validation failure as its cause, so it was not thrown for some
	 * other reason.
	 *
	 * @param exception the exception the publication failed with
	 */
	private static void assertInjectedFailure(@Nonnull Throwable exception) {
		final IllegalStateException cause = assertInstanceOf(IllegalStateException.class, exception.getCause());
		assertEquals(INJECTED_FAILURE, cause.getMessage());
	}

	/**
	 * Asserts that the catalog schema holds the resource of the session that closed normally and not the one of the
	 * session whose validation failed - the first proves the schema was read from where it was published.
	 *
	 * @param catalogSchema the catalog schema as published
	 */
	private static void assertOnlyTheAcceptedResource(@Nonnull CatalogSchemaContract catalogSchema) {
		assertTrue(catalogSchema.getFulltextResource(ACCEPTED_RESOURCE).isPresent());
		assertFalse(
			catalogSchema.getFulltextResource(REFUSED_RESOURCE).isPresent(),
			"The schema change whose validation failed must not have been published"
		);
	}

	/**
	 * Loads the package-private validator class by name.
	 *
	 * @return the validator class
	 */
	@Nonnull
	private static Class<?> validatorClass() {
		try {
			return Class.forName(VALIDATOR_CLASS);
		} catch (ClassNotFoundException ex) {
			throw new IllegalStateException("Validator class `" + VALIDATOR_CLASS + "` was not found.", ex);
		}
	}

	/**
	 * Closes the running instance, opens a fresh one over the same storage directory and returns the catalog schema
	 * as read back from disk. A catalog the refusal deactivated is activated again.
	 *
	 * @return the catalog schema of the reopened catalog
	 */
	@Nonnull
	private CatalogSchemaContract reopenAndReadCatalogSchema() {
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
		// whether the scheduled deactivation lands before the close is a race; both sides read the same bootstrap
		// record, which is the state under assertion
		if (this.evita.getCatalogState(TEST_CATALOG).orElse(null) == CatalogState.INACTIVE) {
			CatalogUnpublishableBarrierAssertions.activateWithConflictRetry(this.evita, TEST_CATALOG);
		}
		return this.evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema);
	}

	/**
	 * Builds the throw-away embedded configuration this test runs against.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

}
