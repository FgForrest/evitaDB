/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the verification scope of {@link io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor}'s
 * reference-attribute check.
 *
 * For an **existing** entity that check no longer walks the whole reference container - it verifies only the
 * references the incoming mutations named (issue #1531). That narrowing is safe only because the per-reference
 * verdict depends on nothing entity-scoped except the entity's locale set, and the implementation falls back to the
 * full scan whenever a locale is added.
 *
 * **These tests attack the narrowing, not the mechanism.** The load-bearing one is
 * {@link #untouchedReferencesAreDefaultedWhenEntityLocaleAppears()}: it mutates an entity attribute only, naming no
 * reference at all, and demands that references the batch never touched acquire their localized defaults in the new
 * locale. Remove the locale fallback and that test fails - which is the point, because the failure it guards against
 * is silent.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(REFERENCE)
@DisplayName("Reference attribute verification — scope of the existing-entity check")
class ReferenceAttributeVerificationScopeTest implements EvitaTestSupport {
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String ATTRIBUTE_NOTE = "note";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_MANDATORY = "mandatoryNote";
	private static final Locale ENGLISH = Locale.ENGLISH;
	private static final Locale GERMAN = Locale.GERMAN;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceAttributeVerificationScopeTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a reference the batch never named is defaulted when the same batch adds an entity locale")
	void untouchedReferencesAreDefaultedWhenEntityLocaleAppears() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				defineSchema(session, false);
				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);
				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 2)
				);
				// the product is created carrying ENGLISH only - both references get their ENGLISH default
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setAttribute(ATTRIBUTE_LABEL, ENGLISH, "english label")
						.setReference(REFERENCE_CATEGORIES, 1)
						.setReference(REFERENCE_CATEGORIES, 2)
				);
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// a MIXED batch: it introduces a new entity locale and touches exactly ONE reference (category 1).
				// The reference container therefore becomes dirty, the existing-entity check runs, and the question
				// is whether the reference it did NOT touch (category 2) is still defaulted in the new locale.
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_LABEL, GERMAN, "deutsches label")
					.setReference(
						REFERENCE_CATEGORIES, 1,
						whichIs -> whichIs.setAttribute(ATTRIBUTE_NOTE, ENGLISH, "touched")
					)
					.upsertVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session
					.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow();
				// category 2 was NOT named by the batch - it must still acquire the new locale's default, because
				// the added entity locale is what made it non-compliant
				assertEquals(
					"default note", findReference(product, 2).getAttribute(ATTRIBUTE_NOTE, GERMAN),
					"the untouched reference is missing its GERMAN default - the existing-entity check skipped a " +
						"reference that the newly added entity locale made non-compliant"
				);
				assertEquals(
					"default note", findReference(product, 2).getAttribute(ATTRIBUTE_NOTE, ENGLISH),
					"the untouched reference lost its ENGLISH default"
				);
				// the touched reference keeps the value the batch set and gains the new locale's default
				assertEquals(
					"touched", findReference(product, 1).getAttribute(ATTRIBUTE_NOTE, ENGLISH),
					"the touched reference lost the value the batch set"
				);
				assertEquals(
					"default note", findReference(product, 1).getAttribute(ATTRIBUTE_NOTE, GERMAN),
					"the touched reference is missing its GERMAN default"
				);
			}
		);
	}

	@Test
	@DisplayName("a reference added to an existing entity is still defaulted")
	void referenceAddedToExistingEntityIsDefaulted() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				defineSchema(session, false);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 2));
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setAttribute(ATTRIBUTE_LABEL, ENGLISH, "english label")
						.setReference(REFERENCE_CATEGORIES, 1)
				);
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(REFERENCE_CATEGORIES, 2)
					.upsertVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session
					.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow();
				assertEquals(
					"default note", findReference(product, 2).getAttribute(ATTRIBUTE_NOTE, ENGLISH),
					"the newly added reference was not verified"
				);
				assertEquals(
					"default note", findReference(product, 1).getAttribute(ATTRIBUTE_NOTE, ENGLISH),
					"the pre-existing reference lost its default"
				);
			}
		);
	}

	@Test
	@DisplayName("a mandatory attribute missing on a touched reference still fails the update")
	void missingMandatoryAttributeOnTouchedReferenceStillThrows() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				defineSchema(session, true);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 2));
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setAttribute(ATTRIBUTE_LABEL, ENGLISH, "english label")
						.setReference(
							REFERENCE_CATEGORIES, 1,
							whichIs -> whichIs.setAttribute(ATTRIBUTE_MANDATORY, "provided")
						)
				);
			}
		);

		assertThrows(
			MandatoryAttributesNotProvidedException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						// the mandatory attribute is deliberately omitted on the reference being added
						.setReference(REFERENCE_CATEGORIES, 2)
						.upsertVia(session);
				}
			),
			"adding a reference without its mandatory attribute must still be refused"
		);
	}

	/**
	 * Defines a product schema whose `categories` reference carries a localized, default-valued attribute - the shape
	 * that couples reference compliance to the entity's locale set.
	 *
	 * @param session      session to define the schema through
	 * @param withMandatory when true the reference also declares a non-nullable attribute with no default value
	 */
	private static void defineSchema(
		@Nonnull io.evitadb.api.EvitaSessionContract session,
		boolean withMandatory
	) {
		session.defineEntitySchema(Entities.CATEGORY).updateVia(session);
		session.defineEntitySchema(Entities.PRODUCT)
			.withAttribute(ATTRIBUTE_LABEL, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs.indexed()
						.withAttribute(
							ATTRIBUTE_NOTE, String.class,
							thatIs -> thatIs.localized().withDefaultValue("default note")
						);
					if (withMandatory) {
						whichIs.withAttribute(ATTRIBUTE_MANDATORY, String.class, thatIs -> {
						});
					}
				}
			)
			.updateVia(session);
	}

	/**
	 * Returns the product's reference to the given category, failing the test when it is absent.
	 *
	 * @param product     the fetched product
	 * @param categoryPk  primary key of the referenced category
	 * @return the reference, never null
	 */
	@Nonnull
	private static ReferenceContract findReference(@Nonnull SealedEntity product, int categoryPk) {
		final Optional<ReferenceContract> reference = product.getReference(REFERENCE_CATEGORIES, categoryPk);
		assertNotNull(reference.orElse(null), "reference to category " + categoryPk + " is missing entirely");
		return reference.orElseThrow();
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}
}
