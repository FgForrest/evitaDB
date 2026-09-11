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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the verification scope of {@link io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor}'s
 * reference-attribute check.
 *
 * For an **existing** entity that check no longer walks the whole reference container - it verifies only the
 * references the incoming mutations named (issue #1531). That narrowing is safe only because the per-reference
 * verdict depends on nothing entity-scoped except the entity's locale set, and the implementation falls back to the
 * full scan whenever the entity's OWN locale set gains a locale - an attribute written in a locale the entity
 * already declares does not fall back - and whenever a mutation names a reference by a key that cannot be resolved
 * against the container at all.
 *
 * **These tests attack the narrowing, not the mechanism.** The load-bearing one is
 * {@link #untouchedReferencesAreDefaultedWhenEntityLocaleAppears()}: it mutates an entity attribute only, naming no
 * reference at all, and demands that references the batch never touched acquire their localized defaults in the new
 * locale. Remove the locale fallback and that test fails - which is the point, because the failure it guards against
 * is silent.
 *
 * The three scope tests below are written as a differential against one shared scenario - a reference schema that
 * gains a default-valued attribute after the entity was written, leaving every existing reference of that entity
 * non-compliant. Whether an untouched reference is repaired then reads out directly as whether the batch took the
 * incremental path or the full scan, through the public API and with no knowledge of internals:
 *
 * - {@link #untouchedReferenceKeepsItsGapAfterTheSchemaGainsADefaultValuedAttribute()} — the incremental path;
 * - {@link #batchNamingAReferenceWithAGenericKeyVerifiesEverything()} — the unresolvable-key fallback;
 * - {@link #batchRemovingAReferenceVerifiesOnlyTheReferencesItNamed()} — a removal, which resolves and therefore
 *   does *not* fall back, and is the case most easily confused with the one above.
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
	/**
	 * Attribute added to the reference schema *after* the entity was written, so every existing reference becomes
	 * non-compliant without any of them being touched. Deliberately non-localized, so the locale fallback plays no
	 * part in whether it is repaired.
	 */
	private static final String ATTRIBUTE_LATE_NOTE = "lateNote";
	private static final String LATE_NOTE_DEFAULT = "late default";
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
				assertEveryReferenceCompliant(product, schemaOf(session));
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
				assertEveryReferenceCompliant(product, schemaOf(session));
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

	@Test
	@DisplayName("a reference the batch never named keeps a gap the schema opened after it was written")
	void untouchedReferenceKeepsItsGapAfterTheSchemaGainsADefaultValuedAttribute() {
		createProductWithTwoCategories();
		addLateDefaultValuedAttributeToReferenceSchema();

		// an ordinary builder-driven upsert: the mutation carries the reference's resolved internal key, so the
		// incremental path takes it and category 2 is never looked at
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
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
				assertEquals(
					LATE_NOTE_DEFAULT, findReference(product, 1).getAttribute(ATTRIBUTE_LATE_NOTE),
					"the touched reference must be brought up to date with the schema"
				);
				// This is the narrowing, stated as behaviour rather than as prose: an untouched reference is
				// trusted to have been compliant when it was last written, so a schema change that makes it
				// non-compliant is not repaired by a later upsert of a sibling reference. Before #1531 the full
				// scan repaired it. The two tests below pin the fallbacks that still do.
				assertNull(
					findReference(product, 2).getAttribute(ATTRIBUTE_LATE_NOTE),
					"the untouched reference must be left exactly as it was written"
				);
			}
		);
	}

	@Test
	@DisplayName("a batch naming a reference by a generic key verifies every reference")
	void batchNamingAReferenceWithAGenericKeyVerifiesEverything() {
		createProductWithTwoCategories();
		addLateDefaultValuedAttributeToReferenceSchema();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					new EntityUpsertMutation(
						Entities.PRODUCT, 10, EntityExistence.MUST_EXIST,
						new ReferenceAttributeMutation(
							// the two-argument constructor yields internalPrimaryKey == 0, which the key manager
							// cannot translate - exactly the condition the fallback tests
							new ReferenceKey(REFERENCE_CATEGORIES, 1),
							new UpsertAttributeMutation(new AttributeKey(ATTRIBUTE_NOTE, ENGLISH), "touched")
						)
					)
				);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session
					.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow();
				assertEquals(
					"touched", findReference(product, 1).getAttribute(ATTRIBUTE_NOTE, ENGLISH),
					"the hand-built mutation did not reach the reference it named"
				);
				assertEquals(
					LATE_NOTE_DEFAULT, findReference(product, 2).getAttribute(ATTRIBUTE_LATE_NOTE),
					"an unresolvable key must send the whole batch back to the full scan, which repairs every " +
						"reference - the incremental path must never verify less than the full one would"
				);
				assertEveryReferenceCompliant(product, schemaOf(session));
			}
		);
	}

	@Test
	@DisplayName("a batch removing a reference verifies only the references it named")
	void batchRemovingAReferenceVerifiesOnlyTheReferencesItNamed() {
		createProductWithTwoCategories();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 3));
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(REFERENCE_CATEGORIES, 3)
					.upsertVia(session);
			}
		);
		addLateDefaultValuedAttributeToReferenceSchema();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(REFERENCE_CATEGORIES, 1)
					.upsertVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session
					.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow();
				assertTrue(
					product.getReference(REFERENCE_CATEGORIES, 1).isEmpty(),
					"the removal itself must have taken effect, or the batch under test never happened"
				);
				// A removed reference is *resolvable* - the container still holds its dropped slot - so the batch
				// keeps the incremental path and verifies only what it named. It is the reference the key cannot
				// resolve AT ALL that means the resolver is holding a stale key and must fall back; the two look
				// identical through `findReference`, which answers empty for both, and telling them apart is what
				// keeps every removal off the whole-container scan.
				assertNull(
					findReference(product, 2).getAttribute(ATTRIBUTE_LATE_NOTE),
					"a batch removing a reference must not re-verify the references it never named"
				);
				assertNull(
					findReference(product, 3).getAttribute(ATTRIBUTE_LATE_NOTE),
					"a batch removing a reference must not re-verify the references it never named"
				);
			}
		);
	}

	@Test
	@DisplayName("a reference the batch never named is not re-checked against an attribute the schema newly mandates")
	void untouchedReferenceIsNotRecheckedAgainstANewlyMandatedAttribute() {
		createProductWithTwoCategories();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withAttribute(ATTRIBUTE_MANDATORY, String.class, thatIs -> {
						})
					)
					.updateVia(session);
			}
		);

		// the touched reference provides the newly mandated value, the untouched one cannot - and is not asked
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						REFERENCE_CATEGORIES, 1,
						whichIs -> whichIs.setAttribute(ATTRIBUTE_MANDATORY, "provided")
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
				assertEquals(
					"provided", findReference(product, 1).getAttribute(ATTRIBUTE_MANDATORY),
					"the touched reference must carry the value the batch set"
				);
				// The silent half of the narrowing, and the reason it is worth a test of its own: the entity is
				// left holding a reference that violates a mandatory constraint, with no exception anywhere. The
				// full scan would have refused the whole upsert instead.
				assertNull(
					findReference(product, 2).getAttribute(ATTRIBUTE_MANDATORY),
					"the untouched reference must be left exactly as it was written"
				);
			}
		);
	}

	/*
		ASSERTIONS
	 */

	/**
	 * Asserts the invariant the full scan enforces, expressed without reference to *which* path ran: every existing
	 * reference of the entity carries a value for every attribute its schema declares mandatory or default-valued,
	 * in every locale the entity declares.
	 *
	 * Written this way on purpose. A test built on it attacks the quantifier rather than the mechanism, so it keeps
	 * its meaning whatever the set of fallbacks turns out to be.
	 *
	 * @param product the fetched entity, with all its references
	 * @param schema  the entity schema the references are verified against
	 */
	private static void assertEveryReferenceCompliant(
		@Nonnull SealedEntity product,
		@Nonnull EntitySchemaContract schema
	) {
		final Set<Locale> locales = product.getLocales();
		for (final ReferenceContract reference : product.getReferences()) {
			final ReferenceSchemaContract referenceSchema =
				schema.getReferenceOrThrowException(reference.getReferenceName());
			for (final AttributeSchemaContract attributeSchema : referenceSchema.getAttributes().values()) {
				if (attributeSchema.isNullable() && attributeSchema.getDefaultValue() == null) {
					continue;
				}
				final String attributeName = attributeSchema.getName();
				if (attributeSchema.isLocalized()) {
					for (final Locale locale : locales) {
						assertNotNull(
							reference.getAttribute(attributeName, locale),
							"reference " + reference.getReferenceKey() + " carries no `" + attributeName
								+ "` in " + locale
						);
					}
				} else {
					assertNotNull(
						reference.getAttribute(attributeName),
						"reference " + reference.getReferenceKey() + " carries no `" + attributeName + "`"
					);
				}
			}
		}
	}

	/*
		FIXTURE
	 */

	/**
	 * Creates the base schema plus a product carrying two references, both compliant with the schema as it stands
	 * at that moment. This is the state every fallback test opens a gap in.
	 */
	private void createProductWithTwoCategories() {
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
						.setReference(REFERENCE_CATEGORIES, 2)
				);
			}
		);
	}

	/**
	 * Adds a non-localized, default-valued attribute to the `categories` reference schema, which leaves every
	 * reference already written non-compliant without touching any of them.
	 */
	private void addLateDefaultValuedAttributeToReferenceSchema() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withAttribute(
							ATTRIBUTE_LATE_NOTE, String.class,
							thatIs -> thatIs.nullable().withDefaultValue(LATE_NOTE_DEFAULT)
						)
					)
					.updateVia(session);
			}
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
		@Nonnull EvitaSessionContract session,
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
	 * Returns the product entity schema as the session sees it.
	 *
	 * @param session the reading session
	 * @return the product schema
	 */
	@Nonnull
	private static EntitySchemaContract schemaOf(@Nonnull EvitaSessionContract session) {
		return session.getEntitySchemaOrThrowException(Entities.PRODUCT);
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
		return product.getReference(REFERENCE_CATEGORIES, categoryPk)
			.orElseThrow(
				() -> new AssertionError("reference to category " + categoryPk + " is missing entirely")
			);
	}

	/**
	 * Builds the engine configuration these tests boot against. Session inactivity closing is disabled so a
	 * session held open across several assertions is never reclaimed underneath them.
	 *
	 * @return the configuration; never `null`
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}
}
