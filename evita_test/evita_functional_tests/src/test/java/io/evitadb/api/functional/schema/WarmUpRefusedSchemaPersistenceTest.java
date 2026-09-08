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
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.ConflictingEngineMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Set;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards #1466 - a schema change refused by catalog-schema validation in a `WARMING_UP` catalog must not reach the
 * disk, and must not load silently on the next open.
 *
 * The refusal alone does not achieve that, because the refusal and the write are performed by different actors.
 * {@code EvitaSession#closeInternal} validates before it flushes, so the refused session performs no write of its
 * own - but by then {@link io.evitadb.core.collection.EntityCollection#updateSchema} has already exchanged the
 * invalid schema into the running catalog and put an {@code EntitySchemaStoragePart} into the data-store buffer, and
 * warm-up has no undo for that exchange. Every later publisher would therefore write it out:
 * {@code Catalog#terminateInternally} on an ordinary {@code Evita#close()}, and any subsequent session close, a
 * read-only one included. What stops all of them at once is the unpublishable barrier the refusal raises - see
 * {@code Catalog#markUnpublishableDueToInvalidSchema}.
 *
 * The guarantee is not specific to one validation rule - {@code SealedCatalogSchema#validate()} fans out to every
 * entity and reference schema, so every rule reachable from there has the same exposure. Two independent rules are
 * therefore exercised below: the attribute filter-accelerator rule (the cheapest one to trigger from a raw mutation)
 * and the reference rule that requires a managed referenced entity type to exist.
 *
 * Each refusal is asserted on its **message**, never on the exception type alone - several unrelated refusals throw
 * `InvalidSchemaMutationException` and would satisfy a bare `assertThrows` while proving something else. And each
 * case is paired with a control that pushes the *same* schema element through legitimately and finds it on the far
 * side of a reopen, so an empty assertion cannot pass merely because the reopened schema was read wrongly. The
 * reopen is load-bearing twice over: it is the only honest way to ask what reached the disk, and it fails outright
 * if the close that precedes it did not release the engine's folder lock.
 *
 * {@link AttributeFilterAcceleratorRefusalTest} pins the same accelerator case from the schema API's side.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Schema refused in warm-up must not reach the disk")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SCHEMA)
class WarmUpRefusedSchemaPersistenceTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_CODE = "code";
	private static final String REFERENCE_BRAND = "brand";
	/** An entity type deliberately never defined in the catalog, so a managed reference to it cannot validate. */
	private static final String NON_EXISTING_ENTITY_TYPE = "nonExistingEntityType";
	/** Primary key of the entity written by a session that closes successfully, and therefore publishes. */
	private static final int PUBLISHED_PRODUCT_PK = 1;
	/** Primary key of the entity written by the session whose close is refused, and which therefore never publishes. */
	private static final int DISCARDED_PRODUCT_PK = 2;
	/** Upper bound on how long any single asynchronous catalog lifecycle step is waited for. */
	private static final long AWAIT_BUDGET_MILLIS = 10_000L;
	/** Interval between two observations while waiting for such a step. */
	private static final long POLL_INTERVAL_MILLIS = 20L;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("WarmUpRefusedSchemaPersistence");
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

	@Nested
	@DisplayName("refused by the attribute filter-accelerator rule")
	class AcceleratorRule {

		@Test
		@Tag(ATTRIBUTE)
		@DisplayName("should not persist an accelerator declared on an attribute with no filter index")
		void shouldNotPersistAnAcceleratorDeclaredOnAnAttributeWithNoFilterIndex() {
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();

			// the builder cannot express this state at all - it refuses the chain while assembling it - so the
			// mutation has to be submitted raw. Every other route into the schema (gRPC, REST, GraphQL, the WAL)
			// carries mutations the same way, which is what makes this the shape worth pinning
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							new ModifyEntitySchemaMutation(
								Entities.PRODUCT,
								new SetAttributeSchemaAcceleratedMutation(
									ATTRIBUTE_CODE,
									new ScopedAttributeFilterAccelerators(
										Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH
									)
								)
							)
						);
					}
				)
			);
			assertTrue(
				exception.getMessage().contains(AttributeFilterAccelerator.SUBSTRING_SEARCH.name()),
				() -> "The refusal must name the accelerator it refuses, but was: " + exception.getMessage()
			);
			assertTrue(
				exception.getMessage().contains("no filter index"),
				() -> "The refusal must name the missing filter index, but was: " + exception.getMessage()
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertEquals(
				Set.of(),
				reopenedSchema.getAttribute(ATTRIBUTE_CODE).orElseThrow().getAcceleratorsInScope(Scope.LIVE),
				"The refused accelerator must not survive the close that follows the refusal"
			);
		}

		@Test
		@Tag(ATTRIBUTE)
		@DisplayName("should persist an accelerator the same route accepts")
		void shouldPersistAnAcceleratorTheSameRouteAccepts() {
			// the control for the case above: the very same raw mutation, on an attribute that *is* filterable, must
			// come back from a reopen. Without it an empty accelerator set would also be the answer to a schema that
			// was read wrongly, or to an attribute that never carried the declaration in the first place
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
						.updateVia(session);
				}
			);
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT,
							new SetAttributeSchemaAcceleratedMutation(
								ATTRIBUTE_CODE,
								new ScopedAttributeFilterAccelerators(
									Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH
								)
							)
						)
					);
				}
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertEquals(
				Set.of(AttributeFilterAccelerator.SUBSTRING_SEARCH),
				reopenedSchema.getAttribute(ATTRIBUTE_CODE).orElseThrow().getAcceleratorsInScope(Scope.LIVE),
				"An accepted accelerator must survive the reopen - otherwise the assertion above proves nothing"
			);
		}
	}

	@Nested
	@DisplayName("refused by the managed-reference rule")
	class ManagedReferenceRule {

		@Test
		@Tag(REFERENCE)
		@DisplayName("should not persist a managed reference to an entity type that does not exist")
		void shouldNotPersistAManagedReferenceToAnEntityTypeThatDoesNotExist() {
			// a second, entirely unrelated rule from the same `validate()` fan-out - the defect is a property of the
			// warm-up write path, not of the accelerator rule that first exposed it
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(
							new ModifyEntitySchemaMutation(
								Entities.PRODUCT,
								new CreateReferenceSchemaMutation(
									REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
									NON_EXISTING_ENTITY_TYPE, true,
									null, false,
									false, false
								)
							)
						);
					}
				)
			);
			// pinned to the wording of the rule under test, not merely to the exception type:
			// `CreateReferenceSchemaMutation` has refusals of its own that throw the same type, and a bare
			// `assertThrows` would accept any of them
			assertTrue(
				exception.getMessage().contains(NON_EXISTING_ENTITY_TYPE)
					&& exception.getMessage().contains("is not present in catalog"),
				() -> "The refusal must name the missing entity type, but was: " + exception.getMessage()
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertFalse(
				reopenedSchema.getReference(REFERENCE_BRAND).isPresent(),
				"The refused reference must not survive the close that follows the refusal"
			);
		}

		@Test
		@Tag(REFERENCE)
		@DisplayName("should persist a managed reference the same route accepts")
		void shouldPersistAManagedReferenceTheSameRouteAccepts() {
			// the control for the case above - the same raw mutation against an entity type that does exist
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);
				}
			);
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						new ModifyEntitySchemaMutation(
							Entities.PRODUCT,
							new CreateReferenceSchemaMutation(
								REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
								Entities.BRAND, true,
								null, false,
								false, false
							)
						)
					);
				}
			);

			final EntitySchemaContract reopenedSchema = WarmUpRefusedSchemaPersistenceTest.this.reopenAndReadSchema();
			assertTrue(
				reopenedSchema.getReference(REFERENCE_BRAND).isPresent(),
				"An accepted reference must survive the reopen - otherwise the assertion above proves nothing"
			);
		}
	}

	@Nested
	@DisplayName("recovery from the refusal")
	class Recovery {

		@Test
		@Tag(MANAGEMENT)
		@DisplayName("should deactivate the catalog and hand back the last published state on activation")
		void shouldDeactivateTheCatalogAndHandBackTheLastPublishedStateOnActivation() {
			// published, because the session that wrote them closed successfully: the schema and one entity
			WarmUpRefusedSchemaPersistenceTest.this.defineProductWithPlainAttribute();
			WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, PUBLISHED_PRODUCT_PK).upsertVia(session);
				}
			);

			// never published: a second entity, and then the schema change this session's close refuses. Both are
			// applied in memory - the refusal comes from validating the whole schema at close - and neither reaches
			// the disk, which is exactly the trade the recovery makes.
			// The managed-reference rule is the one used here rather than the accelerator rule: an accelerator on a
			// collection that already holds entities is refused by a pre-flight check instead, before any exchange
			// happens, so it would leave nothing to recover FROM and the test would pass without proving anything
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> WarmUpRefusedSchemaPersistenceTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(Entities.PRODUCT, DISCARDED_PRODUCT_PK).upsertVia(session);
						session.updateEntitySchema(
							new ModifyEntitySchemaMutation(
								Entities.PRODUCT,
								new CreateReferenceSchemaMutation(
									REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
									NON_EXISTING_ENTITY_TYPE, true,
									null, false,
									false, false
								)
							)
						);
					}
				)
			);
			assertTrue(
				exception.getMessage().contains(NON_EXISTING_ENTITY_TYPE)
					&& exception.getMessage().contains("is not present in catalog"),
				() -> "The recovery must be driven by the catalog-schema validation refusal, but was: "
					+ exception.getMessage()
			);

			WarmUpRefusedSchemaPersistenceTest.this.activateOnceTheDeactivationSettles();

			WarmUpRefusedSchemaPersistenceTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertFalse(
						session.getEntitySchemaOrThrow(Entities.PRODUCT).getReference(REFERENCE_BRAND).isPresent(),
						"Activation must hand back the schema of the last session that closed successfully"
					);
					assertTrue(
						session.getEntity(Entities.PRODUCT, PUBLISHED_PRODUCT_PK).isPresent(),
						"An entity written by a session that closed successfully must survive the recovery"
					);
					assertFalse(
						session.getEntity(Entities.PRODUCT, DISCARDED_PRODUCT_PK).isPresent(),
						"An entity written by the refused session must not survive it - it was never published"
					);
				}
			);
		}
	}

	/**
	 * Waits until the test catalog reaches the given state, failing the test when it does not do so within a bounded
	 * budget.
	 *
	 * The deactivation a refusal schedules is asynchronous by design - it runs on the engine's scheduler because it
	 * has to close the very sessions the refusing thread is running under - so a test that asks what happened next
	 * has to wait for it rather than assume it has already happened.
	 *
	 * @param expectedState the state to wait for; must not be null
	 */
	private void awaitCatalogState(@Nonnull CatalogState expectedState) {
		final long deadline = System.currentTimeMillis() + AWAIT_BUDGET_MILLIS;
		CatalogState observedState = null;
		while (System.currentTimeMillis() < deadline) {
			observedState = this.evita.getCatalogState(TEST_CATALOG).orElse(null);
			if (observedState == expectedState) {
				return;
			}
			pauseBriefly();
		}
		fail(
			"Catalog `" + TEST_CATALOG + "` stayed at `" + observedState + "` instead of reaching `" +
				expectedState + "`."
		);
	}

	/**
	 * Waits out the deactivation a refusal schedules and then activates the catalog again.
	 *
	 * Two waits are folded together on purpose. The catalog reaching {@link CatalogState#INACTIVE} says the
	 * deactivation's engine-state update has landed, but the lifecycle mutation carrying it releases its conflict
	 * key only once it finalizes, a moment later. An activation issued in between is refused with
	 * {@link ConflictingEngineMutationException} - the engine's "another lifecycle operation for this catalog is
	 * still in flight" signal, and a transient rather than a failure worth failing the test over.
	 */
	private void activateOnceTheDeactivationSettles() {
		awaitCatalogState(CatalogState.INACTIVE);
		activateWithConflictRetry();
	}

	/**
	 * Activates the test catalog, retrying while the engine reports that another lifecycle operation for it is
	 * still in flight.
	 */
	private void activateWithConflictRetry() {
		final long deadline = System.currentTimeMillis() + AWAIT_BUDGET_MILLIS;
		RuntimeException lastConflict;
		do {
			try {
				this.evita.activateCatalog(TEST_CATALOG);
				return;
			} catch (RuntimeException ex) {
				if (!(ex instanceof ConflictingEngineMutationException)
					&& !(ex.getCause() instanceof ConflictingEngineMutationException)) {
					throw ex;
				}
				lastConflict = ex;
				pauseBriefly();
			}
		} while (System.currentTimeMillis() < deadline);
		fail("Catalog `" + TEST_CATALOG + "` could not be activated: " + lastConflict.getMessage());
	}

	/**
	 * Waits until the test catalog is out of every transitional state and returns the state it settled in.
	 *
	 * @return the settled state; never null
	 */
	@Nonnull
	private CatalogState awaitSettledCatalogState() {
		final long deadline = System.currentTimeMillis() + AWAIT_BUDGET_MILLIS;
		CatalogState observedState = null;
		while (System.currentTimeMillis() < deadline) {
			observedState = this.evita.getCatalogState(TEST_CATALOG).orElse(null);
			if (observedState != null && !observedState.isTransitional()) {
				return observedState;
			}
			pauseBriefly();
		}
		fail("Catalog `" + TEST_CATALOG + "` stayed transitional at `" + observedState + "`.");
		throw new IllegalStateException("unreachable");
	}

	/**
	 * Sleeps for one polling interval, turning an interrupt into a test failure rather than a silent early return.
	 */
	private void pauseBriefly() {
		try {
			Thread.sleep(POLL_INTERVAL_MILLIS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			fail("Interrupted while waiting for an asynchronous catalog lifecycle step to settle.");
		}
	}

	/**
	 * Defines the product schema with a single `String` attribute that is neither filterable nor unique in any scope,
	 * so that an accelerator declared on it has no filter index to sit on.
	 */
	private void defineProductWithPlainAttribute() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.updateVia(session);
			}
		);
	}

	/**
	 * Closes the running instance and opens a fresh one over the same storage directory, returning the product schema
	 * as it was actually read back from disk.
	 *
	 * Reopening is the only honest way to ask what a refused session left behind - the in-memory catalog would answer
	 * for a schema that was never written, and the close is itself the write under test.
	 *
	 * @return the product entity schema of the reopened catalog; never null
	 */
	@Nonnull
	private EntitySchemaContract reopenAndReadSchema() {
		this.evita.close();
		this.evita = new Evita(configuration());
		// a refusal schedules a deactivation, and whether that lands before the close is a race this test must not
		// depend on. When it does land, the INACTIVE state is persisted and the reopened engine leaves the catalog
		// unloaded; when the close wins, the catalog comes back loaded. Both sides read the same bootstrap record -
		// the one published by the last session that closed successfully - which is the state under assertion
		if (awaitSettledCatalogState() == CatalogState.INACTIVE) {
			activateWithConflictRetry();
		}
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrow(Entities.PRODUCT);
			}
		);
	}

	/**
	 * Builds the throw-away embedded configuration this test runs against.
	 *
	 * @return the configuration; never null
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

}
