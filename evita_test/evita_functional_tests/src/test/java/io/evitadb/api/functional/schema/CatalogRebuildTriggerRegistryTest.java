/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A catalog is rebuilt from its existing collections in three places - a transactional commit, the warm-up to ALIVE
 * transition, and the handover that puts one catalog behind another's name. The last two re-run
 * {@code EntityCollection#initSchema()} to re-resolve reflected references, and an exchanged schema notifies the
 * catalog, which by default validates that entity type's cross-entity index triggers straight away.
 *
 * **Straight away is too early during a rebuild**, because a cross-entity trigger is resolved against the catalog's
 * entity schema index - and the rebuild is still filling it. A histogram whose value comes from an attribute of the
 * *referenced* entity therefore looks up an entity type the index has not reached yet and is refused as invalid,
 * although the schema it describes is entirely valid and has been serving.
 *
 * The schema below states that shape symmetrically on purpose: both entity types carry a reflected reference (which
 * is what makes {@code initSchema()} exchange at all) *and* a histogram reading an attribute of the other one. Only
 * one of the two can be initialised first, and whichever it is, its counterpart is missing from the index at that
 * moment - so the test does not depend on the iteration order of the collection map, which is a hash order over
 * entity type names.
 *
 * Both rebuild sites are covered: {@code goLive} is the cheap one, and the handover is the one that
 * {@link io.evitadb.core.management.PublishRestoredCatalogTask} drives - there a refusal lands past the point of no
 * return, where it costs the target catalog its availability until the server is restarted.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog rebuild must not validate cross-entity triggers against a half-built schema index")
@Tag(ENGINE)
@Tag(SCHEMA)
class CatalogRebuildTriggerRegistryTest implements EvitaTestSupport {
	/** Both entity types play the same role, so they are named for the symmetry rather than for a domain. */
	private static final String ENTITY_ALPHA = "alpha";
	private static final String ENTITY_BETA = "beta";
	/** The attribute each histogram reads off the *referenced* entity - the lookup that needs the schema index. */
	private static final String ATTRIBUTE_VALUE = "value";
	private static final String REFERENCE_BETAS = "betas";
	private static final String REFERENCE_ALPHAS = "alphas";
	/** Reflected references exist solely to make `initSchema()` exchange the schema and notify the catalog. */
	private static final String REFERENCE_ALPHAS_REFLECTED = "alphasReflected";
	private static final String REFERENCE_BETAS_REFLECTED = "betasReflected";
	private static final String HISTOGRAM_VALUE = "valueHistogram";
	/** Name of the second catalog, which is handed over onto {@link #TEST_CATALOG}'s name. */
	private static final String REPLACEMENT_CATALOG = "testCatalogReplacement";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogRebuildTriggerRegistry");
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
	@DisplayName("should go live with histograms reading attributes of another entity type")
	void shouldGoLiveWithHistogramsReadingAttributesOfAnotherEntityType() {
		this.evita.updateCatalog(TEST_CATALOG, CatalogRebuildTriggerRegistryTest::defineInterlinkedHistograms);

		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);

		assertEquals(
			CatalogState.ALIVE,
			this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG).getCatalogState()
		);
	}

	@Test
	@DisplayName("should hand a catalog over onto another's name with such histograms")
	void shouldHandACatalogOverOntoAnothersNameWithSuchHistograms() {
		this.evita.updateCatalog(TEST_CATALOG, CatalogRebuildTriggerRegistryTest::defineInterlinkedHistograms);
		this.evita.defineCatalog(REPLACEMENT_CATALOG);
		this.evita.updateCatalog(REPLACEMENT_CATALOG, CatalogRebuildTriggerRegistryTest::defineInterlinkedHistograms);

		this.evita.replaceCatalogWithProgress(REPLACEMENT_CATALOG, TEST_CATALOG).onCompletion().toCompletableFuture().join();

		assertEquals(
			TEST_CATALOG,
			this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG).getName()
		);
	}

	/**
	 * Defines two entity types that each reference the other, each carrying a histogram whose value is read from an
	 * attribute of the entity it references, and each carrying a reflected reference onto the other's reference.
	 *
	 * @param session session to define the schema through
	 */
	private static void defineInterlinkedHistograms(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_ALPHA)
			.withAttribute(ATTRIBUTE_VALUE, Integer.class, whichIs -> whichIs.filterable().sortable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_BETA)
			.withAttribute(ATTRIBUTE_VALUE, Integer.class, whichIs -> whichIs.filterable().sortable().nullable())
			.updateVia(session);

		session.defineEntitySchema(ENTITY_ALPHA)
			.withReferenceToEntity(
				REFERENCE_BETAS, ENTITY_BETA, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.bucketed(
						HISTOGRAM_VALUE,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['" + ATTRIBUTE_VALUE + "']")
					)
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_BETA)
			.withReferenceToEntity(
				REFERENCE_ALPHAS, ENTITY_ALPHA, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.bucketed(
						HISTOGRAM_VALUE,
						ExpressionFactory.parse("$reference.referencedEntity?.attributes['" + ATTRIBUTE_VALUE + "']")
					)
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_ALPHA)
			.withReflectedReferenceToEntity(REFERENCE_ALPHAS_REFLECTED, ENTITY_BETA, REFERENCE_ALPHAS)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_BETA)
			.withReflectedReferenceToEntity(REFERENCE_BETAS_REFLECTED, ENTITY_ALPHA, REFERENCE_BETAS)
			.updateVia(session);
	}

	/**
	 * Builds the Evita configuration pointing at this test's own directories.
	 *
	 * @return configuration for the tested engine instance
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}

}
