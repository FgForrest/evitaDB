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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins that the two standalone unique indexes key every value exactly as the filter index does, for every attribute
 * type whose filter normalizer is not the identity.
 *
 * The catalog's `GlobalUniqueIndex` holds `uniqueGlobally` and `uniqueGloballyWithinLocale` attributes, a collection's
 * `OwnerUniqueIndex` a localized attribute unique across locales. Both run every value through
 * `FilterIndex#getNormalizer` on every entry point, so two spellings the filter index treats as one value are one
 * unique value here too: a lookup by either spelling finds the owner, the second spelling is refused for anyone else
 * (in another collection, too, for a catalog attribute), the value is released on update and removal, and all of it
 * holds after the catalog is loaded back from disk.
 *
 * ## The fixture
 *
 * Every {@link TypeCase} builds its own catalog over its attribute type:
 *
 * | collection | attribute | declared as | entity `alpha:1` holds |
 * |---|---|---|---|
 * | `alpha`, `beta` | {@link #GLOBAL} | catalog, `uniqueGlobally` | value 1 |
 * | `alpha`, `beta` | {@link #GLOBAL_LOCALIZED} | catalog, localized, `uniqueGloballyWithinLocale` | value 1 in English |
 * | `alpha`, `beta` | {@link #GLOBAL_ARRAY} | catalog, `uniqueGlobally`, array | values 1 and {@link #SECOND_ELEMENT} |
 * | `alpha` | {@link #OWNER} | entity, localized, `unique` | value 1 in English |
 * | `alpha` | {@link #OWNER_ARRAY} | entity, localized, `unique`, array | values 1 and {@link #SECOND_ELEMENT} in English |
 *
 * `beta` starts empty. Each case writes its values in one spelling and probes and competes with the other.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Standalone unique attribute normalized like the filter index")
@Tag(INDEXING)
@Tag(STORAGE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
class UniqueAttributeNormalizationFunctionalTest implements EvitaTestSupport {
	private static final String ALPHA = "alpha";
	private static final String BETA = "beta";
	private static final String GLOBAL = "globalUnique";
	private static final String GLOBAL_LOCALIZED = "globalUniqueWithinLocale";
	private static final String GLOBAL_ARRAY = "globalUniqueArray";
	private static final String OWNER = "ownerUnique";
	private static final String OWNER_ARRAY = "ownerUniqueArray";
	/**
	 * The value index of the second element of every array attribute of `alpha:1`.
	 */
	private static final int SECOND_ELEMENT = 51;
	/**
	 * The value index `alpha:1` moves to when it is updated.
	 */
	private static final int UPDATED = 101;
	/**
	 * More values than one 256-entry leaf holds, so both standalone unique trees persist in the `PAGED` shape.
	 */
	private static final int PAGED_COUNT = 300;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("UniqueAttributeNormalization");
		this.evita = new Evita(configuration());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(TypeCase.class)
	@DisplayName("Should find the owner by the equivalent spelling through every standalone unique index")
	void shouldFindOwnerByEquivalentSpelling(@Nonnull TypeCase typeCase) {
		createFixture(typeCase, 1);

		assertEveryIndexFinds(typeCase, 1);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(TypeCase.class)
	@DisplayName("Should refuse the equivalent spelling for another entity")
	void shouldRefuseEquivalentSpellingForAnotherEntity(@Nonnull TypeCase typeCase) {
		createFixture(typeCase, 1);

		assertEveryIndexRefusesAnotherOwner(typeCase, 1);
		// the refused attempts took nothing from the owner
		assertEveryIndexFinds(typeCase, 1);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(TypeCase.class)
	@DisplayName("Should keep the value rewritten in the equivalent spelling and release it on update and removal")
	void shouldKeepRewrittenValueAndReleaseItOnUpdateAndRemoval(@Nonnull TypeCase typeCase) {
		createFixture(typeCase, 1);

		// the same value in the other spelling replaces it as one unique value
		update(
			typeCase, ALPHA, 1,
			builder -> builder
				.setAttribute(GLOBAL, typeCase.spelling(1))
				.setAttribute(GLOBAL_LOCALIZED, Locale.ENGLISH, typeCase.spelling(1))
				.setAttribute(GLOBAL_ARRAY, typeCase.array(typeCase.spelling(1), typeCase.spelling(SECOND_ELEMENT)))
				.setAttribute(OWNER, Locale.ENGLISH, typeCase.spelling(1))
				.setAttribute(
					OWNER_ARRAY, Locale.ENGLISH,
					typeCase.array(typeCase.spelling(1), typeCase.spelling(SECOND_ELEMENT))
				)
		);
		assertEveryIndexFinds(typeCase, 1);
		assertEveryIndexRefusesAnotherOwner(typeCase, 1);

		// moving away from the value, or dropping it, frees it for everyone else
		update(
			typeCase, ALPHA, 1,
			builder -> builder
				.setAttribute(GLOBAL, typeCase.value(UPDATED))
				.removeAttribute(GLOBAL_LOCALIZED, Locale.ENGLISH)
				.setAttribute(GLOBAL_ARRAY, typeCase.array(typeCase.value(UPDATED)))
				.setAttribute(OWNER, Locale.ENGLISH, typeCase.value(UPDATED))
				.removeAttribute(OWNER_ARRAY, Locale.ENGLISH)
		);
		final Serializable released = typeCase.spelling(1);
		assertAll(
			() -> assertEquals(List.of(), find(null, null, attributeEquals(GLOBAL, released)), GLOBAL),
			() -> assertEquals(
				List.of(), find(null, Locale.ENGLISH, attributeEquals(GLOBAL_LOCALIZED, released)), GLOBAL_LOCALIZED
			),
			() -> assertEquals(List.of(), find(null, null, attributeEquals(GLOBAL_ARRAY, released)), GLOBAL_ARRAY),
			() -> assertEquals(
				List.of(), find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER, released)), OWNER
			),
			() -> assertEquals(
				List.of(), find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER_ARRAY, released)), OWNER_ARRAY
			),
			() -> assertEquals(
				List.of(ALPHA + ":1"), find(null, null, attributeEquals(GLOBAL, typeCase.spelling(UPDATED))),
				GLOBAL + " updated"
			),
			() -> assertEquals(
				List.of(ALPHA + ":1"), find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER, typeCase.spelling(UPDATED))),
				OWNER + " updated"
			)
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(BETA, 1)
					.setAttribute(GLOBAL, typeCase.spelling(1))
					.setAttribute(GLOBAL_LOCALIZED, Locale.ENGLISH, typeCase.spelling(1))
					.setAttribute(GLOBAL_ARRAY, typeCase.array(typeCase.spelling(1)))
					.upsertVia(session);
				session.createNewEntity(ALPHA, 2)
					.setAttribute(OWNER, Locale.GERMAN, typeCase.spelling(1))
					.setAttribute(OWNER_ARRAY, Locale.GERMAN, typeCase.array(typeCase.spelling(SECOND_ELEMENT)))
					.upsertVia(session);
			}
		);
		assertAll(
			() -> assertEquals(
				List.of(BETA + ":1"), find(null, null, attributeEquals(GLOBAL, typeCase.value(1))), GLOBAL
			),
			() -> assertEquals(
				List.of(BETA + ":1"), find(null, Locale.ENGLISH, attributeEquals(GLOBAL_LOCALIZED, typeCase.value(1))),
				GLOBAL_LOCALIZED
			),
			() -> assertEquals(
				List.of(BETA + ":1"), find(null, null, attributeEquals(GLOBAL_ARRAY, typeCase.value(1))), GLOBAL_ARRAY
			),
			() -> assertEquals(
				List.of(ALPHA + ":2"), find(ALPHA, Locale.GERMAN, attributeEquals(OWNER, typeCase.value(1))), OWNER
			),
			() -> assertEquals(
				List.of(ALPHA + ":2"),
				find(ALPHA, Locale.GERMAN, attributeEquals(OWNER_ARRAY, typeCase.value(SECOND_ELEMENT))),
				OWNER_ARRAY
			)
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(TypeCase.class)
	@DisplayName("Should fold two spellings of one value in one array onto one unique value")
	void shouldFoldTwoSpellingsInOneArray(@Nonnull TypeCase typeCase) {
		Assumptions.assumeTrue(typeCase.hasDistinctSpelling(), "The type has a single spelling per value.");
		createFixture(typeCase, 0);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(ALPHA, 1)
					.setAttribute(GLOBAL_ARRAY, typeCase.array(typeCase.value(1), typeCase.spelling(1)))
					.setAttribute(OWNER_ARRAY, Locale.ENGLISH, typeCase.array(typeCase.value(1), typeCase.spelling(1)))
					.upsertVia(session);
			}
		);
		assertAll(
			() -> assertEquals(
				List.of(ALPHA + ":1"), find(null, null, attributeEquals(GLOBAL_ARRAY, typeCase.value(1))), GLOBAL_ARRAY
			),
			() -> assertEquals(
				List.of(ALPHA + ":1"), find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER_ARRAY, typeCase.spelling(1))),
				OWNER_ARRAY
			)
		);

		// removing the array retires the one value it holds, whichever spelling is named first
		update(
			typeCase, ALPHA, 1,
			builder -> builder.removeAttribute(GLOBAL_ARRAY).removeAttribute(OWNER_ARRAY, Locale.ENGLISH)
		);
		assertAll(
			() -> assertEquals(List.of(), find(null, null, attributeEquals(GLOBAL_ARRAY, typeCase.value(1)))),
			() -> assertEquals(List.of(), find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER_ARRAY, typeCase.value(1))))
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(TypeCase.class)
	@DisplayName("Should find and refuse the equivalent spelling after the catalog is loaded back")
	void shouldFindAndRefuseAfterReload(@Nonnull TypeCase typeCase) {
		createFixture(typeCase, 3);
		restart();

		for (int i = 1; i <= 3; i++) {
			assertEveryIndexFinds(typeCase, i);
		}
		assertEveryIndexRefusesAnotherOwner(typeCase, 2);

		// a reloaded tree takes a write and survives another reload
		update(typeCase, ALPHA, 2, builder -> builder.setAttribute(OWNER, Locale.ENGLISH, typeCase.value(UPDATED)));
		restart();
		assertEquals(
			List.of(ALPHA + ":2"), find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER, typeCase.spelling(UPDATED)))
		);
		assertEquals(List.of(), find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER, typeCase.spelling(2))));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(TypeCase.class)
	@DisplayName("Should find every value by the equivalent spelling after a paged index is loaded back")
	void shouldFindEveryValueAfterPagedReload(@Nonnull TypeCase typeCase) {
		Assumptions.assumeTrue(
			typeCase.distinctValueCount() > PAGED_COUNT + UPDATED, "The type has too few values to page."
		);
		createFixture(typeCase, PAGED_COUNT);
		restart();

		final List<Executable> lookups = new ArrayList<>(PAGED_COUNT * 2);
		for (int i = 1; i <= PAGED_COUNT; i++) {
			final int index = i;
			lookups.add(() -> assertEquals(
				List.of(ALPHA + ":" + index), find(null, null, attributeEquals(GLOBAL, typeCase.spelling(index))),
				GLOBAL + " " + index
			));
			lookups.add(() -> assertEquals(
				List.of(ALPHA + ":" + index),
				find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER, typeCase.spelling(index))),
				OWNER + " " + index
			));
		}
		assertAll(lookups);
		final Serializable taken = typeCase.spelling(PAGED_COUNT);
		assertAll(
			() -> assertRefused(GLOBAL, session -> session.createNewEntity(BETA, 1000)
				.setAttribute(GLOBAL, taken).upsertVia(session)),
			() -> assertRefused(OWNER, session -> session.createNewEntity(ALPHA, 10_000)
				.setAttribute(OWNER, Locale.GERMAN, taken).upsertVia(session))
		);
	}

	/**
	 * Closes the engine and opens it again on the same data, so every index is loaded back from disk.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Builds the per-test configuration over the test path triplet, which stays stable across restarts.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/**
	 * Builds the fixture described on the class, with `count` entities in `alpha`: entity `i` holds value `i` (and
	 * value `i + SECOND_ELEMENT - 1` as the second array element) in the first spelling. A fixture of
	 * {@link #SECOND_ELEMENT} entities or more fills the scalar attributes alone, since its second elements would
	 * collide with the first ones. The catalog is made ALIVE, which flushes it to disk.
	 *
	 * @param typeCase the attribute type under test
	 * @param count    the number of entities in `alpha`
	 */
	private void createFixture(@Nonnull TypeCase typeCase, int count) {
		final Class<? extends Serializable> type = typeCase.type();
		final Class<? extends Serializable> arrayType = typeCase.arrayType();
		final int decimalPlaces = typeCase.indexedDecimalPlaces();
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(
				GLOBAL, type, thatIs -> thatIs.uniqueGlobally().nullable().indexDecimalPlaces(decimalPlaces)
			)
			.withAttribute(
				GLOBAL_LOCALIZED, type,
				thatIs -> thatIs.localized().uniqueGloballyWithinLocale().nullable().indexDecimalPlaces(decimalPlaces)
			)
			.withAttribute(
				GLOBAL_ARRAY, arrayType, thatIs -> thatIs.uniqueGlobally().nullable().indexDecimalPlaces(decimalPlaces)
			)
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ALPHA)
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withGlobalAttribute(GLOBAL).withGlobalAttribute(GLOBAL_LOCALIZED).withGlobalAttribute(GLOBAL_ARRAY)
					.withAttribute(
						OWNER, type,
						thatIs -> thatIs.localized().unique().nullable().indexDecimalPlaces(decimalPlaces)
					)
					.withAttribute(
						OWNER_ARRAY, arrayType,
						thatIs -> thatIs.localized().unique().nullable().indexDecimalPlaces(decimalPlaces)
					)
					.updateVia(session);
				session.defineEntitySchema(BETA)
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH, Locale.GERMAN)
					.withGlobalAttribute(GLOBAL).withGlobalAttribute(GLOBAL_LOCALIZED).withGlobalAttribute(GLOBAL_ARRAY)
					.updateVia(session);
				for (int i = 1; i <= count; i++) {
					final EntityBuilder entity = session.createNewEntity(ALPHA, i)
						.setAttribute(GLOBAL, typeCase.value(i))
						.setAttribute(GLOBAL_LOCALIZED, Locale.ENGLISH, typeCase.value(i))
						.setAttribute(OWNER, Locale.ENGLISH, typeCase.value(i));
					if (count < SECOND_ELEMENT) {
						// the second elements must not reach into the value range the first ones occupy
						final Serializable secondElement = typeCase.value(i + SECOND_ELEMENT - 1);
						entity
							.setAttribute(GLOBAL_ARRAY, typeCase.array(typeCase.value(i), secondElement))
							.setAttribute(OWNER_ARRAY, Locale.ENGLISH, typeCase.array(typeCase.value(i), secondElement));
					}
					entity.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Opens entity `primaryKey` of `entityType` for write, applies `change` and upserts it.
	 *
	 * @param typeCase   the attribute type under test (only for the failure message)
	 * @param entityType the collection
	 * @param primaryKey the entity
	 * @param change     the change to apply
	 */
	private void update(
		@Nonnull TypeCase typeCase,
		@Nonnull String entityType,
		int primaryKey,
		@Nonnull Consumer<EntityBuilder> change
	) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(entityType, primaryKey, entityFetchAllContent())
					.orElseThrow(() -> new AssertionError(typeCase + ": entity " + entityType + ":" + primaryKey));
				final EntityBuilder builder = entity.openForWrite();
				change.accept(builder);
				builder.upsertVia(session);
			}
		);
	}

	/**
	 * Runs `filter` (in `locale` when given) within `collection`, or across the catalog when `collection` is `null`,
	 * and renders the result as `type:pk` strings.
	 *
	 * @param collection the queried collection, or `null` for a catalog-wide query
	 * @param locale     the query locale, or `null`
	 * @param filter     the equality constraint
	 * @return the found entities as `type:pk`
	 */
	@Nonnull
	private List<String> find(@Nullable String collection, @Nullable Locale locale, @Nonnull FilterConstraint filter) {
		final FilterConstraint constraint = locale == null ? filter : and(filter, entityLocaleEquals(locale));
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return findIn(session, collection, constraint);
			}
		);
	}

	/**
	 * Runs `constraint` within `collection`, or across the catalog when `collection` is `null`.
	 */
	@Nonnull
	private static List<String> findIn(
		@Nonnull EvitaSessionContract session,
		@Nullable String collection,
		@Nonnull FilterConstraint constraint
	) {
		return session.queryListOfEntityReferences(
			collection == null
				? query(filterBy(constraint))
				: query(collection(collection), filterBy(constraint))
		).stream().map(UniqueAttributeNormalizationFunctionalTest::describe).sorted().toList();
	}

	/**
	 * Renders a reference as `type:pk` so a mismatch reads directly in the assertion message.
	 */
	@Nonnull
	private static String describe(@Nonnull EntityReferenceContract reference) {
		return reference.getType() + ":" + reference.getPrimaryKey();
	}

	/**
	 * Asserts that value `index` in the other spelling finds exactly `alpha:index` through every standalone unique
	 * index, with `attributeEquals` and `attributeInSet`, within `alpha` and - for the catalog attributes -
	 * catalog-wide. Every lookup is reported on its own, so one failing route does not hide the others.
	 *
	 * @param typeCase the attribute type under test
	 * @param index    the entity primary key (and value index)
	 */
	private void assertEveryIndexFinds(@Nonnull TypeCase typeCase, int index) {
		final Serializable value = typeCase.spelling(index);
		final Serializable element = typeCase.spelling(index + SECOND_ELEMENT - 1);
		final List<String> expected = List.of(ALPHA + ":" + index);
		final List<Executable> lookups = new ArrayList<>(16);
		for (String collection : new String[]{ALPHA, null}) {
			final String where = collection == null ? " catalog-wide" : " in " + collection;
			lookups.add(() -> assertEquals(
				expected, find(collection, null, attributeEquals(GLOBAL, value)), GLOBAL + where
			));
			lookups.add(() -> assertEquals(
				expected, find(collection, null, attributeInSet(GLOBAL, value)), GLOBAL + " inSet" + where
			));
			lookups.add(() -> assertEquals(
				expected, find(collection, Locale.ENGLISH, attributeEquals(GLOBAL_LOCALIZED, value)),
				GLOBAL_LOCALIZED + where
			));
			lookups.add(() -> assertEquals(
				expected, find(collection, Locale.ENGLISH, attributeInSet(GLOBAL_LOCALIZED, value)),
				GLOBAL_LOCALIZED + " inSet" + where
			));
			lookups.add(() -> assertEquals(
				expected, find(collection, null, attributeEquals(GLOBAL_ARRAY, element)), GLOBAL_ARRAY + where
			));
		}
		lookups.add(() -> assertEquals(
			expected, find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER, value)), OWNER
		));
		lookups.add(() -> assertEquals(
			expected, find(ALPHA, Locale.ENGLISH, attributeInSet(OWNER, value)), OWNER + " inSet"
		));
		lookups.add(() -> assertEquals(
			expected, find(ALPHA, Locale.ENGLISH, attributeEquals(OWNER_ARRAY, element)), OWNER_ARRAY
		));
		lookups.add(() -> assertEquals(
			expected, find(ALPHA, Locale.ENGLISH, attributeInSet(OWNER_ARRAY, value, element)), OWNER_ARRAY + " inSet"
		));
		assertAll(lookups);
	}

	/**
	 * Asserts that value `index` in the other spelling is refused for every entity it does not belong to: a `beta`
	 * entity for the catalog attributes, and another `alpha` entity - in either locale - for the owner attributes. A
	 * refused write changes nothing, so the assertions can run one after another.
	 *
	 * @param typeCase the attribute type under test
	 * @param index    the value already held by `alpha:index`
	 */
	private void assertEveryIndexRefusesAnotherOwner(@Nonnull TypeCase typeCase, int index) {
		final Serializable value = typeCase.spelling(index);
		final Serializable element = typeCase.spelling(index + SECOND_ELEMENT - 1);
		final List<Executable> refusals = new ArrayList<>(8);
		refusals.add(() -> assertRefused(GLOBAL, session -> session.createNewEntity(BETA, 1000)
			.setAttribute(GLOBAL, value).upsertVia(session)));
		refusals.add(() -> assertRefused(GLOBAL_LOCALIZED, session -> session.createNewEntity(BETA, 1000)
			.setAttribute(GLOBAL_LOCALIZED, Locale.ENGLISH, value).upsertVia(session)));
		refusals.add(() -> assertRefused(GLOBAL_ARRAY, session -> session.createNewEntity(BETA, 1000)
			.setAttribute(GLOBAL_ARRAY, typeCase.array(element)).upsertVia(session)));
		for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
			refusals.add(() -> assertRefused(OWNER + " " + locale, session -> session.createNewEntity(ALPHA, 10_000)
				.setAttribute(OWNER, locale, value).upsertVia(session)));
			refusals.add(() -> assertRefused(OWNER_ARRAY + " " + locale, session -> session.createNewEntity(ALPHA, 10_000)
				.setAttribute(OWNER_ARRAY, locale, typeCase.array(element)).upsertVia(session)));
		}
		assertAll(refusals);
	}

	/**
	 * Asserts that `write` is refused with a {@link UniqueValueViolationException}.
	 *
	 * @param description what is written, for the failure message
	 * @param write       the competing write
	 */
	private void assertRefused(@Nonnull String description, @Nonnull Consumer<EvitaSessionContract> write) {
		assertThrows(
			UniqueValueViolationException.class,
			() -> this.evita.updateCatalog(TEST_CATALOG, write::accept),
			description + " should be refused"
		);
	}

	/**
	 * One attribute type whose filter normalizer is not the identity, with two spellings of each of its values: the
	 * one the fixture writes ({@link #value(int)}) and one the filter index treats as the same value
	 * ({@link #spelling(int)}).
	 */
	enum TypeCase {
		/**
		 * Keyed by its millisecond instant: the same instant at another offset is the same value.
		 */
		OFFSET_DATE_TIME(OffsetDateTime.class, OffsetDateTime[].class, 0, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1)).plusMinutes(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return ((OffsetDateTime) value(index)).withOffsetSameInstant(ZoneOffset.ofHoursMinutes(-5, -30));
			}
		},
		/**
		 * Anchored at UTC and keyed by its millisecond instant: a value differing below the millisecond is the same.
		 */
		LOCAL_DATE_TIME(LocalDateTime.class, LocalDateTime[].class, 0, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return LocalDateTime.of(2026, 1, 1, 10, 0, 0, 123_000_000).plusMinutes(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return ((LocalDateTime) value(index)).plusNanos(456_789);
			}
		},
		/**
		 * Truncated to the millisecond: a value differing below the millisecond is the same.
		 */
		LOCAL_TIME(LocalTime.class, LocalTime[].class, 0, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return LocalTime.of(10, 0, 0, 123_000_000).plusSeconds(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return ((LocalTime) value(index)).plusNanos(456_789);
			}
		},
		/**
		 * Keyed by its Unicode NFD form: the precomposed and the decomposed spelling are the same value.
		 */
		STRING(String.class, String[].class, 0, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return Normalizer.normalize("čaj-é-" + index, Normalizer.Form.NFC);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return Normalizer.normalize((String) value(index), Normalizer.Form.NFD);
			}
		},
		/**
		 * Keyed by its scaled `int` at the indexed decimal places: two values equal at that scale are the same.
		 */
		BIG_DECIMAL(BigDecimal.class, BigDecimal[].class, 2, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return new BigDecimal(index + ".235");
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return new BigDecimal(index + ".2440");
			}
		},
		/**
		 * Rescaled to the indexed decimal places: two ranges equal at that scale are the same.
		 */
		BIG_DECIMAL_RANGE(BigDecimalNumberRange.class, BigDecimalNumberRange[].class, 2, Integer.MAX_VALUE) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return BigDecimalNumberRange.between(new BigDecimal(index + ".234"), new BigDecimal(index + ".678"));
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return BigDecimalNumberRange.between(new BigDecimal(index + ".2341"), new BigDecimal(index + ".6779"));
			}
		},
		/**
		 * Wrapped in its comparable counterpart; a currency has a single spelling.
		 */
		CURRENCY(Currency.class, Currency[].class, 0, Currency.getAvailableCurrencies().size()) {
			private static final List<Currency> CURRENCIES = Currency.getAvailableCurrencies()
				.stream()
				.sorted(Comparator.comparing(Currency::getCurrencyCode))
				.toList();

			@Nonnull
			@Override
			Serializable value(int index) {
				return CURRENCIES.get(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return Currency.getInstance(CURRENCIES.get(index).getCurrencyCode());
			}
		},
		/**
		 * Wrapped in its comparable counterpart; a locale has a single spelling.
		 */
		LOCALE(Locale.class, Locale[].class, 0, PlainLocales.LOCALES.size()) {
			@Nonnull
			@Override
			Serializable value(int index) {
				return PlainLocales.LOCALES.get(index);
			}

			@Nonnull
			@Override
			Serializable spelling(int index) {
				return Locale.forLanguageTag(PlainLocales.LOCALES.get(index).toLanguageTag());
			}
		};

		private final Class<? extends Serializable> type;
		private final Class<? extends Serializable> arrayType;
		private final int indexedDecimalPlaces;
		private final int distinctValueCount;

		TypeCase(
			@Nonnull Class<? extends Serializable> type,
			@Nonnull Class<? extends Serializable> arrayType,
			int indexedDecimalPlaces,
			int distinctValueCount
		) {
			this.type = type;
			this.arrayType = arrayType;
			this.indexedDecimalPlaces = indexedDecimalPlaces;
			this.distinctValueCount = distinctValueCount;
		}

		/**
		 * Returns value `index` in the spelling the fixture writes.
		 */
		@Nonnull
		abstract Serializable value(int index);

		/**
		 * Returns value `index` in the other spelling the filter index treats as the same value.
		 */
		@Nonnull
		abstract Serializable spelling(int index);

		/**
		 * Returns the attribute type.
		 */
		@Nonnull
		Class<? extends Serializable> type() {
			return this.type;
		}

		/**
		 * Returns the array type of the attribute type.
		 */
		@Nonnull
		Class<? extends Serializable> arrayType() {
			return this.arrayType;
		}

		/**
		 * Returns the indexed decimal places the attributes declare.
		 */
		int indexedDecimalPlaces() {
			return this.indexedDecimalPlaces;
		}

		/**
		 * Returns how many distinct values {@link #value(int)} can produce.
		 */
		int distinctValueCount() {
			return this.distinctValueCount;
		}

		/**
		 * Returns whether the other spelling differs from the written one at all.
		 */
		boolean hasDistinctSpelling() {
			return !value(1).equals(spelling(1));
		}

		/**
		 * Builds an array of the attribute type out of `values`.
		 */
		@Nonnull
		Serializable array(@Nonnull Serializable... values) {
			final Object array = Array.newInstance(this.type, values.length);
			for (int i = 0; i < values.length; i++) {
				Array.set(array, i, values[i]);
			}
			return (Serializable) array;
		}
	}

	/**
	 * Available locales made of a language and an optional region, in a stable order. Kryo persists a `Locale` as its
	 * language, country and variant, so a script or an extension would not survive a reload and two locales differing
	 * only there would come back as one.
	 */
	private static final class PlainLocales {
		private static final List<Locale> LOCALES = Arrays.stream(Locale.getAvailableLocales())
			.filter(locale -> !locale.getLanguage().isEmpty())
			.filter(locale -> locale.getScript().isEmpty() && locale.getVariant().isEmpty() && !locale.hasExtensions())
			.map(Locale::toLanguageTag)
			.distinct()
			.sorted()
			.map(Locale::forLanguageTag)
			.toList();
	}

}
