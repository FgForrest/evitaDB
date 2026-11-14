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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the warning the server logs when a stored reference becomes indexed or faceted in a scope while the
 * collection holds entities. The reference and facet indexes are filled as entities are indexed and nothing builds
 * them for the entities already stored, so filtering by the reference misses those entities; the server
 * accepts such a change and warns about it instead of refusing it. The warning must not be logged when no stored
 * entity can miss anything.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference indexed or faceted after the data")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(FACET)
class ReferenceIndexedAfterDataTest implements EvitaTestSupport {
	private static final String REFERENCE_BRAND = "brand";
	private static final int BRAND_PK = 1;
	private static final String GROUP_TYPE = "brandGroup";
	private static final String WARNING_PREFIX = "Reference `" + REFERENCE_BRAND + "` of entity `" + Entities.PRODUCT + "` became ";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceIndexedAfterDataTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@DisplayName("warn when a stored plain reference becomes indexed and faceted over stored entities, in warm-up")
	@Test
	void shouldWarnWhenPlainReferenceBecomesIndexedAndFacetedOverStoredEntitiesInWarmUp() {
		storeProduct(false, whichIs -> { });

		final List<String> warnings = captureWarnings(() -> changeReference(whichIs -> whichIs.indexedForFiltering().faceted()));

		assertTrue(
			warnings.contains(WARNING_PREFIX + "indexed and faceted in scope `LIVE`"),
			"Expected the warning, got: " + warnings
		);
	}

	@DisplayName("warn when a stored plain reference becomes indexed and faceted over stored entities, in a live catalog")
	@Test
	void shouldWarnWhenPlainReferenceBecomesIndexedAndFacetedOverStoredEntitiesWhenAlive() {
		storeProduct(true, whichIs -> { });

		final List<String> warnings = captureWarnings(() -> changeReference(whichIs -> whichIs.indexedForFiltering().faceted()));

		assertTrue(
			warnings.contains(WARNING_PREFIX + "indexed and faceted in scope `LIVE`"),
			"Expected the warning, got: " + warnings
		);
	}

	@DisplayName("warn when a stored indexed reference becomes faceted over stored entities")
	@Test
	void shouldWarnWhenIndexedReferenceBecomesFacetedOverStoredEntities() {
		storeProduct(false, ReferenceSchemaBuilder::indexedForFiltering);

		final List<String> warnings = captureWarnings(() -> changeReference(whichIs -> whichIs.indexedForFiltering().faceted()));

		assertTrue(warnings.contains(WARNING_PREFIX + "faceted in scope `LIVE`"), "Expected the warning, got: " + warnings);
	}

	@DisplayName("warn when a stored indexed reference gains an indexed component over stored entities")
	@Test
	void shouldWarnWhenIndexedReferenceGainsComponentOverStoredEntities() {
		storeProduct(false, whichIs -> whichIs.withGroupType(GROUP_TYPE).indexedForFiltering());

		final List<String> warnings = captureWarnings(
			() -> changeReference(
				whichIs -> whichIs.withGroupType(GROUP_TYPE)
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
			)
		);

		assertEquals(List.of(WARNING_PREFIX + "indexed with `REFERENCED_GROUP_ENTITY` in scope `LIVE`"), warnings);
	}

	@DisplayName("not warn when a stored indexed reference drops an indexed component")
	@Test
	void shouldNotWarnWhenIndexedReferenceDropsComponent() {
		storeProduct(
			false,
			whichIs -> whichIs.withGroupType(GROUP_TYPE)
				.indexedWithComponents(
					ReferenceIndexedComponents.REFERENCED_ENTITY, ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
				)
		);

		final List<String> warnings = captureWarnings(
			() -> changeReference(
				whichIs -> whichIs.withGroupType(GROUP_TYPE)
					.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY)
			)
		);

		assertEquals(List.of(), warnings);
		assertEquals(Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY), currentReference().getIndexedComponents());
	}

	@DisplayName("warn only for the scope a stored indexed reference becomes indexed in")
	@Test
	void shouldWarnOnlyForScopeStoredReferenceBecomesIndexedIn() {
		storeProduct(false, whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE));

		final List<String> warnings = captureWarnings(
			() -> changeReference(whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED))
		);

		assertEquals(List.of(WARNING_PREFIX + "indexed in scope `ARCHIVED`"), warnings);
	}

	@DisplayName("not warn when the reference becomes indexed and faceted before any entity is stored")
	@Test
	void shouldNotWarnWhenReferenceBecomesIndexedAndFacetedInEmptyCollection() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.BRAND).withoutGeneratedPrimaryKey().updateVia(session);
			defineProduct(session, whichIs -> { });
		});

		final List<String> warnings = captureWarnings(() -> changeReference(whichIs -> whichIs.indexedForFiltering().faceted()));

		assertEquals(List.of(), warnings);
	}

	@DisplayName("not warn when a stored indexed and faceted reference is defined the same way again")
	@Test
	void shouldNotWarnWhenIndexedAndFacetedReferenceStaysAsItIs() {
		storeProduct(false, whichIs -> whichIs.indexedForFiltering().faceted());

		final List<String> warnings = captureWarnings(() -> changeReference(whichIs -> whichIs.indexedForFiltering().faceted()));

		assertEquals(List.of(), warnings);
	}

	@DisplayName("not warn when the change creates the reference over stored entities")
	@Test
	void shouldNotWarnWhenReferenceIsCreatedOverStoredEntities() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.BRAND).withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
			session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));
		});

		// the stored product has no reference whose indexes could miss it
		final List<String> warnings = captureWarnings(
			() -> changeReference(whichIs -> whichIs.indexedForFiltering().faceted())
		);

		assertTrue(currentReference().isFaceted(), "The change must have created the faceted reference.");
		assertEquals(List.of(), warnings);
	}

	@DisplayName("not warn when a stored indexed and faceted reference stops being indexed and faceted")
	@Test
	void shouldNotWarnWhenReferenceStopsBeingIndexedAndFaceted() {
		storeProduct(false, whichIs -> whichIs.indexedForFiltering().faceted());

		final List<String> warnings = captureWarnings(
			() -> changeReference(whichIs -> whichIs.nonFaceted().nonIndexed())
		);

		assertFalse(currentReference().isIndexed(), "The change must have made the reference non-indexed.");
		assertEquals(List.of(), warnings);
	}

	/**
	 * Defines a brand and a product referencing it with the given definition, and stores one of each.
	 *
	 * @param alive      whether the catalog goes live afterwards
	 * @param definition the definition of the reference the product is stored with
	 */
	private void storeProduct(boolean alive, @Nonnull Consumer<ReferenceSchemaBuilder> definition) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND).withoutGeneratedPrimaryKey().updateVia(session);
				defineProduct(session, definition);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, BRAND_PK));
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1).setReference(REFERENCE_BRAND, BRAND_PK));
				if (alive) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Redefines the reference of the product with the given definition and waits until the change is visible.
	 *
	 * @param definition the definition the reference changes to
	 */
	private void changeReference(@Nonnull Consumer<ReferenceSchemaBuilder> definition) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				defineProduct(session, definition);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
	}

	/**
	 * Returns the reference of the product to the brand as the catalog holds it now, to tell a change that took
	 * effect from one that did nothing and therefore had nothing to warn about.
	 *
	 * @return the current schema of the reference
	 */
	@Nonnull
	private ReferenceSchemaContract currentReference() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.getReferenceOrThrowException(REFERENCE_BRAND);
			}
		);
	}

	/**
	 * Defines the product with a reference to the brand of the given definition.
	 *
	 * @param session    the session to define the product in
	 * @param definition the definition of the reference
	 */
	private static void defineProduct(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<ReferenceSchemaBuilder> definition
	) {
		session.defineEntitySchema(Entities.PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE, definition)
			.updateVia(session);
	}

	/**
	 * Runs the action and returns the warnings the entity collection logged meanwhile about references whose indexes
	 * miss stored entities, from any thread - in a live catalog the schema change is applied again when the
	 * transaction is incorporated into the shared state.
	 *
	 * @param action the action to run
	 * @return the formatted messages of the captured warnings
	 */
	@Nonnull
	private static List<String> captureWarnings(@Nonnull Runnable action) {
		final List<String> warnings = new ArrayList<>();
		final ReentrantLock warningsLock = new ReentrantLock();
		final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
			@Override
			protected void append(ILoggingEvent eventObject) {
				if (eventObject.getLevel() == Level.WARN && eventObject.getFormattedMessage().startsWith(WARNING_PREFIX)) {
					// only the part naming the reference, the change and the scope - the advice may be reworded
					final String message = eventObject.getFormattedMessage();
					warningsLock.lock();
					try {
						warnings.add(message.substring(0, message.indexOf('`', message.indexOf("in scope `") + 10) + 1));
					} finally {
						warningsLock.unlock();
					}
				}
			}
		};
		final Logger logger = assertInstanceOf(
			Logger.class, LoggerFactory.getLogger(EntityCollection.class), "Logback backend required to capture log output."
		);
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		warningsLock.lock();
		try {
			return new ArrayList<>(warnings);
		} finally {
			warningsLock.unlock();
		}
	}

}
