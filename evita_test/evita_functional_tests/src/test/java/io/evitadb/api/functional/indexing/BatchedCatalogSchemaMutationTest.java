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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.functional.schema.CatalogUnpublishableBarrierAssertions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SCHEMA;

/**
 * Exercises cross-entity validation semantics of batched catalog schema mutations.
 *
 * When a single `updateCatalogSchema(...)` call (i.e. a single `ModifyCatalogSchemaMutation`)
 * carries mutations that together are consistent, but individual intermediate mutations are
 * transiently inconsistent (e.g. entity B declares a histogram referencing an attribute of
 * entity A that will be added by a later mutation in the same batch), the batch as a whole
 * must be accepted. Cross-entity validation must be deferred until the end of the batch.
 *
 * When the same mutations arrive in three separate `updateCatalogSchema(...)` calls, the
 * middle call sees an inconsistent end state and must still be rejected. In a warming-up catalog
 * the rejection comes after the middle call has been applied, so it deactivates the catalog, and
 * activating it again loads the last state it published.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Batched catalog schema mutation cross-entity validation")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(SCHEMA)
class BatchedCatalogSchemaMutationTest implements EvitaTestSupport {

	private static final String ENTITY_A = "entityA";
	private static final String ENTITY_B = "entityB";
	private static final String REF_A = "refA";
	private static final String ATTR_X = "attrX";
	private static final String HIST_VALUE = "valueHistogram";

	private EvitaTestSupport.TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("BatchedCatalogSchemaMutationTest");
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Builds the test evitaDB configuration pointing at per-test directories.
	 *
	 * @return evitaDB configuration for the test
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}

	/**
	 * Happy-path test: create B (with a bucketed histogram whose value expression
	 * references `entityA.attributes['attrX']`) and modify A (add `attrX` as filterable)
	 * inside a single `updateCatalogSchema(...)` call.
	 *
	 * Before the fix this batch throws from `HistogramValueDescriptorFactory.build` at
	 * the point mutation-for-B is applied, because `attrX` does not yet exist on A.
	 * After the fix cross-entity trigger rebuild is deferred until after both mutations
	 * of the batch have been applied, so the final schema is consistent and the batch
	 * is accepted.
	 */
	@Nested
	@DisplayName("Single batched mutation")
	class SingleBatch {

		@Test
		@DisplayName("should accept batch when attribute is added to referenced entity in same batch")
		void shouldAcceptBatchWhenAttributeAddedToReferencedEntityInSameBatch() {
			BatchedCatalogSchemaMutationTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// prerequisite: entity A exists (empty, no attributes)
					session.defineEntitySchema(ENTITY_A);

					// mutation for B: create reference with histogram value expression
					// that refers to entityA.attributes[attrX] — which does NOT yet exist on A
					final ModifyEntitySchemaMutation createReferenceAndHistogramOnB = session
						.defineEntitySchema(ENTITY_B)
						.withReferenceToEntity(
							REF_A, ENTITY_A, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFiltering()
								.bucketed(
									HIST_VALUE,
									ExpressionFactory.parse(
										"$reference.referencedEntity?.attributes['" + ATTR_X + "']"
									)
								)
						)
						.toMutation()
						.orElseThrow();

					// mutation for A: add `attrX` as a filterable numeric attribute
					final ModifyEntitySchemaMutation addAttributeToA = session
						.defineEntitySchema(ENTITY_A)
						.withAttribute(
							ATTR_X, Integer.class,
							AttributeSchemaEditor::filterable
						)
						.toMutation()
						.orElseThrow();

					// submit both mutations as a single batch — the batch is consistent
					// as a whole (A ends up with attrX, and B's histogram resolves cleanly),
					// so it must be accepted
					assertDoesNotThrow(
						() -> session.updateCatalogSchema(
							createReferenceAndHistogramOnB,
							addAttributeToA
						)
					);

					// verify post-conditions: both entities exist and A has the attribute
					final SealedEntitySchema schemaOfA = session
						.getEntitySchema(ENTITY_A)
						.orElseThrow();
					assertTrue(
						schemaOfA.getAttribute(ATTR_X).isPresent(),
						"attribute '" + ATTR_X + "' must be present on entity A"
					);
					final SealedEntitySchema schemaOfB = session
						.getEntitySchema(ENTITY_B)
						.orElseThrow();
					assertTrue(
						schemaOfB.getReference(REF_A).isPresent(),
						"reference '" + REF_A + "' must be present on entity B"
					);
				}
			);
		}
	}

	/**
	 * Negative test: when the same operations arrive as three separate
	 * `updateCatalogSchema(...)` calls, the middle call (create B with histogram
	 * referencing `attrX` on A) is standalone and must still fail because at
	 * that call's end state A has no `attrX`. The catalog is warming up, so the
	 * rejection deactivates it, and activating it again loads A and B as they
	 * were published before the rejected call.
	 */
	@Nested
	@DisplayName("Separate mutation calls")
	class SeparateCalls {

		@Test
		@DisplayName("should reject standalone creation of histogram referencing missing attribute")
		void shouldRejectStandaloneCreationOfHistogramReferencingMissingAttribute() {
			final RuntimeException refusal = CatalogUnpublishableBarrierAssertions.assertRefusalRaisesTheBarrier(
				BatchedCatalogSchemaMutationTest.this.evita, TEST_CATALOG, RuntimeException.class,
				() -> BatchedCatalogSchemaMutationTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						// call 1: create A empty
						session.defineEntitySchema(ENTITY_A);

						// call 2 (separate): create B with histogram referencing A.attrX
						// MUST fail — attrX does not exist on A and there is no follow-up
						// mutation in this call that would add it
						session.defineEntitySchema(ENTITY_B)
							.withReferenceToEntity(
								REF_A, ENTITY_A, Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs
									.indexedForFiltering()
									.bucketed(
										HIST_VALUE,
										ExpressionFactory.parse(
											"$reference.referencedEntity?.attributes['"
												+ ATTR_X + "']"
										)
									)
							)
							.updateVia(session);
					}
				)
			);
			assertTrue(
				causeChainMentions(refusal, "does not exist on entity type"),
				() -> "the rejection must name the missing attribute, but was: " + refusal
			);

			final Evita reopened = reopen();
			reopened.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.getEntitySchema(ENTITY_A).isPresent(), "entity A must be loaded");
					assertFalse(
						session.getEntitySchema(ENTITY_B).orElseThrow().getReference(REF_A).isPresent(),
						"entity B must be loaded without the rejected reference"
					);
				}
			);
		}

		/**
		 * Tells whether a throwable or one of its causes carries the given message fragment.
		 *
		 * @param throwable the throwable
		 * @param fragment  the message fragment
		 * @return true when the fragment is found
		 */
		private static boolean causeChainMentions(@Nonnull Throwable throwable, @Nonnull String fragment) {
			for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
				if (cause.getMessage() != null && cause.getMessage().contains(fragment)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Closes the running engine and opens a new one over the same storage, activating the catalog when the
		 * deactivation was stored before the close; the new engine replaces the old one, so `tearDown` closes it.
		 *
		 * @return the reopened engine
		 */
		@Nonnull
		private Evita reopen() {
			BatchedCatalogSchemaMutationTest.this.evita.close();
			final Evita reopened = new Evita(getEvitaConfiguration());
			BatchedCatalogSchemaMutationTest.this.evita = reopened;
			reopened.waitUntilFullyInitialized();
			CatalogUnpublishableBarrierAssertions.activateIfDeactivated(reopened, TEST_CATALOG);
			return reopened;
		}
	}

}
