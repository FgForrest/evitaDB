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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins that a reflected reference allows duplicates if and only if the original reference it reflects does.
 *
 * The engine propagates references between the two sides by looking up the partitions of one side with the
 * duplicate setting of the other. When they disagree, the lookup misses: a reflected reference allowing duplicates
 * over an original that does not created no original reference when written from the reflected side, and failed
 * with an internal error when its entity was created after the original reference pointing to it. The schema is
 * therefore refused whenever the two sides disagree - in either direction, whether the mismatch is declared up front
 * or introduced later by changing the cardinality of either side.
 *
 * Every scenario runs both in warm-up mode and in the transactional (alive) mode, because the two are validated and
 * propagated through different session paths.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reflected reference duplicate cardinality")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(SCHEMA)
class ReflectedReferenceDuplicateCardinalityFunctionalTest implements EvitaTestSupport {

	private static final String PRODUCT = "Product";
	private static final String CATEGORY = "Category";
	/**
	 * The original reference on the product collection.
	 */
	private static final String REF_CATEGORIES = "categories";
	/**
	 * The reflected reference on the category collection, mirroring {@link #REF_CATEGORIES}.
	 */
	private static final String REF_PRODUCTS = "products";
	/**
	 * The representative attribute of the original reference, inherited by the reflected one.
	 */
	private static final String ATTRIBUTE_COUNTRY = "country";
	private static final int CATEGORY_PK = 1;
	private static final int PRODUCT_PK = 10;
	/**
	 * The validation error reported for a reflected reference allowing duplicates over an original that does not.
	 */
	private static final String REFLECTED_ALLOWS_ERROR =
		"Reflected reference `products` cannot allow duplicates, because the original reflected reference " +
			"`categories` in entity `Product` disallows them! Align the cardinality of both references, for example " +
			"by letting the reflected reference inherit the cardinality of the original one.";
	/**
	 * The validation error reported for a reflected reference disallowing duplicates over an original that allows them.
	 */
	private static final String REFLECTED_DISALLOWS_ERROR =
		"Reflected reference `products` cannot disallow duplicates, because the original reflected reference " +
			"`categories` in entity `Product` allows them! Align the cardinality of both references, for example " +
			"by letting the reflected reference inherit the cardinality of the original one.";

	private TestPaths paths;
	private Evita evita;

	/**
	 * Describes the references of the given name held by the given entity as `referencedPk:country` strings.
	 *
	 * @param session       the session to read the entity in
	 * @param entityType    the type of the entity holding the references
	 * @param primaryKey    the primary key of the entity holding the references
	 * @param referenceName the name of the described references
	 * @return the sorted descriptions of the references
	 */
	@Nonnull
	private static List<String> describe(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int primaryKey,
		@Nonnull String referenceName
	) {
		return session.getEntity(entityType, primaryKey, entityFetchAllContent())
			.orElseThrow()
			.getReferences(referenceName)
			.stream()
			.map(ReferenceContract.class::cast)
			.map(it -> it.getReferencedPrimaryKey() + ":" + it.getAttribute(ATTRIBUTE_COUNTRY))
			.sorted()
			.toList();
	}

	/**
	 * Asserts that the given failure was caused by an {@link InvalidSchemaMutationException} carrying the expected
	 * validation error.
	 *
	 * @param failure       the failure thrown by the session
	 * @param expectedError the validation error the exception must report
	 */
	private static void assertRefusedWith(@Nonnull Throwable failure, @Nonnull String expectedError) {
		Throwable cause = failure;
		while (cause != null && !(cause instanceof InvalidSchemaMutationException)) {
			cause = cause.getCause();
		}
		if (cause == null) {
			fail("Expected the schema to be refused by validation, but got: " + failure, failure);
		}
		assertTrue(
			cause.getMessage().contains(expectedError),
			"Unexpected validation message: " + cause.getMessage()
		);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReflectedReferenceDuplicateCardinality");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Defines the category collection with the reflected reference and the product collection with the original
	 * reference. In the alive mode the empty catalog goes live first, so that the schema is defined (and validated)
	 * in a transactional session.
	 *
	 * @param reflectedCardinality the explicit cardinality of the reflected reference, `null` to inherit it
	 * @param originalCardinality  the cardinality of the original reference
	 * @param alive                whether the catalog should go live before the schema is defined
	 */
	private void defineSchema(
		@Nullable Cardinality reflectedCardinality,
		@Nonnull Cardinality originalCardinality,
		boolean alive
	) {
		if (alive) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORY)
					.withReflectedReferenceToEntity(
						REF_PRODUCTS, PRODUCT, REF_CATEGORIES,
						whichIs -> {
							whichIs.indexedForFiltering().withAttributesInherited();
							if (reflectedCardinality != null) {
								whichIs.withCardinality(reflectedCardinality);
							}
						}
					)
					.updateVia(session);
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						REF_CATEGORIES, CATEGORY, originalCardinality,
						whichIs -> whichIs.indexedForFiltering()
							.withAttribute(ATTRIBUTE_COUNTRY, String.class, thatIs -> thatIs.filterable().representative())
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Runs the given writer in a read-write session of the test catalog.
	 *
	 * @param writer the logic writing into the catalog
	 */
	private void write(@Nonnull Consumer<EvitaSessionContract> writer) {
		this.evita.updateCatalog(TEST_CATALOG, writer);
	}

	/**
	 * Asserts that the product holds the original reference to the category and the category holds the reflected
	 * reference to the product, both with the same representative attribute value.
	 */
	private void assertBothSides() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(
					List.of(CATEGORY_PK + ":DE"),
					describe(session, PRODUCT, PRODUCT_PK, REF_CATEGORIES),
					"product side"
				);
				assertEquals(
					List.of(PRODUCT_PK + ":DE"),
					describe(session, CATEGORY, CATEGORY_PK, REF_PRODUCTS),
					"category side"
				);
				return null;
			}
		);
	}

	@Nested
	@DisplayName("when both references allow duplicates")
	class SymmetricCardinality {

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should reflect the original reference written after the category exists")
		void shouldReflectOriginalWrittenAfterCategory(boolean alive) {
			defineSchema(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, alive);
			write(session -> {
				session.upsertEntity(session.createNewEntity(CATEGORY, CATEGORY_PK));
				session.upsertEntity(
					session.createNewEntity(PRODUCT, PRODUCT_PK)
						.setOrUpdateReference(
							REF_CATEGORIES, CATEGORY_PK, Functions.alwaysFalse(),
							whichIs -> whichIs.setAttribute(ATTRIBUTE_COUNTRY, "DE")
						)
				);
			});
			assertBothSides();
		}

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should reflect the original reference written before the category exists once it is created")
		void shouldReflectOriginalWrittenBeforeCategory(boolean alive) {
			defineSchema(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, alive);
			write(session -> session.upsertEntity(
				session.createNewEntity(PRODUCT, PRODUCT_PK)
					.setOrUpdateReference(
						REF_CATEGORIES, CATEGORY_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTRIBUTE_COUNTRY, "DE")
					)
			));
			write(session -> session.upsertEntity(session.createNewEntity(CATEGORY, CATEGORY_PK)));
			assertBothSides();
		}

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should create the original reference from the reflected one")
		void shouldCreateOriginalFromReflected(boolean alive) {
			defineSchema(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, alive);
			write(session -> {
				session.upsertEntity(session.createNewEntity(PRODUCT, PRODUCT_PK));
				session.upsertEntity(
					session.createNewEntity(CATEGORY, CATEGORY_PK)
						.setOrUpdateReference(
							REF_PRODUCTS, PRODUCT_PK, Functions.alwaysFalse(),
							whichIs -> whichIs.setAttribute(ATTRIBUTE_COUNTRY, "DE")
						)
				);
			});
			assertBothSides();
		}
	}

	@Nested
	@DisplayName("when the references are defined with mismatching duplicates")
	class MismatchDeclared {

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should refuse a reflected reference allowing duplicates over an original that does not")
		void shouldRefuseReflectedAllowingDuplicatesOverOriginalThatDoesNot(boolean alive) {
			final Exception failure = assertThrows(
				Exception.class,
				() -> defineSchema(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, Cardinality.ZERO_OR_MORE, alive)
			);
			assertRefusedWith(failure, REFLECTED_ALLOWS_ERROR);
		}

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should refuse a reflected reference disallowing duplicates over an original that allows them")
		void shouldRefuseReflectedDisallowingDuplicatesOverOriginalThatAllowsThem(boolean alive) {
			final Exception failure = assertThrows(
				Exception.class,
				() -> defineSchema(Cardinality.ZERO_OR_MORE, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, alive)
			);
			assertRefusedWith(failure, REFLECTED_DISALLOWS_ERROR);
		}

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should accept a reflected reference inheriting the cardinality of an original without duplicates")
		void shouldAcceptInheritedCardinality(boolean alive) {
			defineSchema(null, Cardinality.ZERO_OR_MORE, alive);
			this.assertReflectedCardinality(Cardinality.ZERO_OR_MORE);
		}

		/**
		 * Asserts the effective cardinality of the reflected reference.
		 *
		 * @param expected the expected cardinality
		 */
		private void assertReflectedCardinality(@Nonnull Cardinality expected) {
			ReflectedReferenceDuplicateCardinalityFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						expected,
						session.getEntitySchemaOrThrowException(CATEGORY)
							.getReferenceOrThrowException(REF_PRODUCTS)
							.getCardinality()
					);
					return null;
				}
			);
		}
	}

	@Nested
	@DisplayName("when the cardinality of one side changes later")
	class MismatchIntroducedByChange {

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should refuse the original reference dropping duplicates the reflected one allows")
		void shouldRefuseOriginalDroppingDuplicates(boolean alive) {
			defineSchema(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, alive);
			final Exception failure = assertThrows(
				Exception.class,
				() -> changeOriginalCardinality(Cardinality.ZERO_OR_MORE)
			);
			assertRefusedWith(failure, REFLECTED_ALLOWS_ERROR);
		}

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should refuse the original reference gaining duplicates the reflected one disallows")
		void shouldRefuseOriginalGainingDuplicates(boolean alive) {
			defineSchema(Cardinality.ZERO_OR_MORE, Cardinality.ZERO_OR_MORE, alive);
			final Exception failure = assertThrows(
				Exception.class,
				() -> changeOriginalCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES)
			);
			assertRefusedWith(failure, REFLECTED_DISALLOWS_ERROR);
		}

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should refuse the reflected reference gaining duplicates the original one disallows")
		void shouldRefuseReflectedGainingDuplicates(boolean alive) {
			defineSchema(Cardinality.ZERO_OR_MORE, Cardinality.ZERO_OR_MORE, alive);
			final Exception failure = assertThrows(
				Exception.class,
				() -> changeReflectedCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES)
			);
			assertRefusedWith(failure, REFLECTED_ALLOWS_ERROR);
		}

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should refuse the reflected reference dropping duplicates the original one allows")
		void shouldRefuseReflectedDroppingDuplicates(boolean alive) {
			defineSchema(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, alive);
			final Exception failure = assertThrows(
				Exception.class,
				() -> changeReflectedCardinality(Cardinality.ZERO_OR_MORE)
			);
			assertRefusedWith(failure, REFLECTED_DISALLOWS_ERROR);
		}

		@ParameterizedTest(name = "alive {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("should let an inherited cardinality follow the original reference")
		void shouldFollowOriginalWhenCardinalityIsInherited(boolean alive) {
			defineSchema(null, Cardinality.ZERO_OR_MORE, alive);
			changeOriginalCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES);
			ReflectedReferenceDuplicateCardinalityFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						session.getEntitySchemaOrThrowException(CATEGORY)
							.getReferenceOrThrowException(REF_PRODUCTS)
							.getCardinality()
					);
					return null;
				}
			);
		}

		/**
		 * Changes the cardinality of the original reference on the product collection.
		 *
		 * @param cardinality the new cardinality
		 */
		private void changeOriginalCardinality(@Nonnull Cardinality cardinality) {
			write(
				session -> session.getEntitySchemaOrThrowException(PRODUCT)
					.openForWrite()
					.withReferenceToEntity(REF_CATEGORIES, CATEGORY, cardinality)
					.updateVia(session)
			);
		}

		/**
		 * Changes the cardinality of the reflected reference on the category collection.
		 *
		 * @param cardinality the new cardinality
		 */
		private void changeReflectedCardinality(@Nonnull Cardinality cardinality) {
			write(
				session -> session.getEntitySchemaOrThrowException(CATEGORY)
					.openForWrite()
					.withReflectedReferenceToEntity(
						REF_PRODUCTS, PRODUCT, REF_CATEGORIES,
						whichIs -> whichIs.withCardinality(cardinality)
					)
					.updateVia(session)
			);
		}
	}
}
