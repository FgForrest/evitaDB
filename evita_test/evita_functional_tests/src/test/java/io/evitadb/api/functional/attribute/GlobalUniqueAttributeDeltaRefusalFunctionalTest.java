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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins that a write refused because it would move a globally unique key onto a value another entity holds leaves
 * every index exactly as it was before the write - the entity index of the writer, the entity index of the holder and
 * the catalog-wide unique index both of them share.
 *
 * Both write paths remove the writer's old key from the catalog-wide index before they insert the new one, and the
 * insert is where the collision is detected. The removal must therefore be taken back with the rest of the refused
 * write, otherwise the writer keeps its value in the entity body while the catalog no longer guards it, and a third
 * entity could claim it.
 *
 * Entity {@link #WRITER_PK} of {@link #ALPHA} holds `10` and entity {@link #HOLDER_PK} of {@link #BETA} holds `15`;
 * the writer is refused `15`, either as a delta of `+5` or as a plain upsert. Afterwards:
 *
 * - the writer's body still holds `10`, and the lookups find the writer by `10` and the holder by `15`, within its
 *   own collection and across the catalog, and nobody by any other value,
 * - the writer can still take a further delta and a plain upsert that collide with nobody,
 * - a third entity is refused both `10` and `15`,
 * - all of that also holds after the catalog is reloaded from disk.
 *
 * Every scenario runs in both catalog states, because a warming-up catalog takes a refused write back from the
 * journal its structures record into, while an alive one throws away the diff layers of the refused write.
 *
 * ## The fixture
 *
 * | attribute | declared as | writer holds | holder holds | refused delta | further delta | further upsert |
 * |---|---|---|---|---|---|---|
 * | `globalCode` | `uniqueGlobally` | `10` | `15` | `+5` | `+1` → `11` | `20` |
 * | `globalLocalCode` | localized, `uniqueGloballyWithinLocale` | `10` | `15` | `+5` | `+1` → `11` | `20` |
 * | `globalPrice` | `uniqueGlobally`, 2 decimal places | `1.50` | `2.50` | `+1` | `+0.25` → `1.75` | `3.50` |
 * | `globalLocalPrice` | localized, `uniqueGloballyWithinLocale`, 2 decimal places | `1.50` | `2.50` | `+1` | `+0.25` → `1.75` | `3.50` |
 *
 * Every lookup of a decimal value is made by both the written spelling and the stripped one, because the unique
 * indexes key a `BigDecimal` with its trailing zeros stripped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Refused write of a globally unique attribute leaves the unique indexes intact")
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(STORAGE)
class GlobalUniqueAttributeDeltaRefusalFunctionalTest implements EvitaTestSupport {
	private static final String ALPHA = "alpha";
	private static final String BETA = "beta";
	private static final int INDEXED_DECIMAL_PLACES = 2;
	/**
	 * Primary key of the entity in {@link #ALPHA} whose write is refused.
	 */
	private static final int WRITER_PK = 1;
	/**
	 * Primary key of the entity in {@link #BETA} holding the value the writer is refused.
	 */
	private static final int HOLDER_PK = 2;
	/**
	 * Primary key of the entity in {@link #BETA} claiming the values the writer and the holder hold.
	 */
	private static final int CLAIMANT_PK = 3;

	/**
	 * The write the writer is refused.
	 */
	enum RefusedWrite {
		/**
		 * A numeric delta producing the holder's value.
		 */
		DELTA,
		/**
		 * A plain upsert of the holder's value.
		 */
		UPSERT
	}

	/**
	 * One globally unique attribute of the fixture, with the values its entities hold and the values written to it.
	 */
	enum UniqueAttribute {
		INTEGER("globalCode", false, 10, 15, 5, 1, 11, 20),
		LOCALIZED_INTEGER("globalLocalCode", true, 10, 15, 5, 1, 11, 20),
		DECIMAL(
			"globalPrice", false, new BigDecimal("1.50"), new BigDecimal("2.50"), BigDecimal.ONE,
			new BigDecimal("0.25"), new BigDecimal("1.75"), new BigDecimal("3.50")
		),
		LOCALIZED_DECIMAL(
			"globalLocalPrice", true, new BigDecimal("1.50"), new BigDecimal("2.50"), BigDecimal.ONE,
			new BigDecimal("0.25"), new BigDecimal("1.75"), new BigDecimal("3.50")
		);

		private final String attributeName;
		private final boolean localized;
		private final Serializable writerValue;
		private final Serializable holderValue;
		private final Number refusedDelta;
		private final Number furtherDelta;
		private final Serializable furtherDeltaValue;
		private final Serializable furtherUpsertValue;

		UniqueAttribute(
			@Nonnull String attributeName,
			boolean localized,
			@Nonnull Serializable writerValue,
			@Nonnull Serializable holderValue,
			@Nonnull Number refusedDelta,
			@Nonnull Number furtherDelta,
			@Nonnull Serializable furtherDeltaValue,
			@Nonnull Serializable furtherUpsertValue
		) {
			this.attributeName = attributeName;
			this.localized = localized;
			this.writerValue = writerValue;
			this.holderValue = holderValue;
			this.refusedDelta = refusedDelta;
			this.furtherDelta = furtherDelta;
			this.furtherDeltaValue = furtherDeltaValue;
			this.furtherUpsertValue = furtherUpsertValue;
		}

		/**
		 * Returns the key of the attribute, carrying the locale it is written in when it is localized.
		 *
		 * @return the attribute key
		 */
		@Nonnull
		AttributeKey key() {
			return new AttributeKey(this.attributeName, this.localized ? Locale.ENGLISH : null);
		}
	}

	private TestPaths paths;
	private Evita evita;

	/**
	 * Returns every attribute of the fixture combined with every refused write and every catalog state.
	 *
	 * @return the arguments of the parameterized tests
	 */
	@Nonnull
	static Stream<Arguments> attributesWritesAndStates() {
		return Arrays.stream(UniqueAttribute.values())
			.flatMap(
				attribute -> Arrays.stream(RefusedWrite.values())
					.flatMap(
						write -> Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE)
							.map(state -> Arguments.of(attribute, write, state))
					)
			);
	}

	/**
	 * Starts an engine on fresh storage.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("GlobalUniqueAttributeDeltaRefusal");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	/**
	 * Closes the engine and removes its storage.
	 */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * The refused write changes nothing: both entities keep their values in their bodies and in every index.
	 */
	@DisplayName("Should leave both entities findable by the values they held")
	@ParameterizedTest(name = "{0} {1} in {2}")
	@MethodSource("attributesWritesAndStates")
	void shouldKeepBothKeysAfterRefusal(
		@Nonnull UniqueAttribute attribute,
		@Nonnull RefusedWrite write,
		@Nonnull CatalogState state
	) {
		prepareCatalog(attribute, state);
		assertRefused(attribute, write);

		assertAll(intactState(attribute));
	}

	/**
	 * The refused write leaves the writer's key where it was, so the writer can move it on by a write that collides
	 * with nobody - by a delta, and by a plain upsert after it.
	 */
	@DisplayName("Should let the writer move its key on after the refusal")
	@ParameterizedTest(name = "{0} {1} in {2}")
	@MethodSource("attributesWritesAndStates")
	void shouldAcceptFurtherWritesAfterRefusal(
		@Nonnull UniqueAttribute attribute,
		@Nonnull RefusedWrite write,
		@Nonnull CatalogState state
	) {
		prepareCatalog(attribute, state);
		assertRefused(attribute, write);

		assertDoesNotThrow(
			() -> writeToWriter(new ApplyDeltaAttributeMutation<>(attribute.key(), attribute.furtherDelta)),
			"The writer was refused a delta to `" + attribute.furtherDeltaValue + "`, which no entity holds!"
		);
		final List<Executable> afterDelta = new ArrayList<>(16);
		afterDelta.add(() -> assertBodyHolds(attribute, ALPHA, WRITER_PK, attribute.furtherDeltaValue));
		afterDelta.addAll(lookupsFind(attribute, attribute.furtherDeltaValue, ALPHA, WRITER_PK));
		afterDelta.addAll(lookupsFind(attribute, attribute.holderValue, BETA, HOLDER_PK));
		afterDelta.addAll(lookupsFindNothing(attribute, attribute.writerValue));
		assertAll(afterDelta);

		assertDoesNotThrow(
			() -> writeToWriter(new UpsertAttributeMutation(attribute.key(), attribute.furtherUpsertValue)),
			"The writer was refused an upsert of `" + attribute.furtherUpsertValue + "`, which no entity holds!"
		);
		final List<Executable> afterUpsert = new ArrayList<>(16);
		afterUpsert.add(() -> assertBodyHolds(attribute, ALPHA, WRITER_PK, attribute.furtherUpsertValue));
		afterUpsert.addAll(lookupsFind(attribute, attribute.furtherUpsertValue, ALPHA, WRITER_PK));
		afterUpsert.addAll(lookupsFind(attribute, attribute.holderValue, BETA, HOLDER_PK));
		afterUpsert.addAll(lookupsFindNothing(attribute, attribute.furtherDeltaValue));
		afterUpsert.addAll(lookupsFindNothing(attribute, attribute.writerValue));
		assertAll(afterUpsert);
	}

	/**
	 * The refused write leaves both keys guarded: a third entity can claim neither the writer's value nor the
	 * holder's.
	 */
	@DisplayName("Should refuse a third entity both held values after the refusal")
	@ParameterizedTest(name = "{0} {1} in {2}")
	@MethodSource("attributesWritesAndStates")
	void shouldGuardBothKeysAfterRefusal(
		@Nonnull UniqueAttribute attribute,
		@Nonnull RefusedWrite write,
		@Nonnull CatalogState state
	) {
		prepareCatalog(attribute, state);
		assertRefused(attribute, write);

		assertClaimRefused(attribute, attribute.writerValue, "the writer");
		assertClaimRefused(attribute, attribute.holderValue, "the holder");
		assertAll(intactState(attribute));
	}

	/**
	 * What the catalog persisted after the refused write is the state before it: after a reload both entities keep
	 * their values in their bodies and in every index, and both keys stay guarded.
	 */
	@DisplayName("Should keep both keys after the catalog is reloaded")
	@ParameterizedTest(name = "{0} {1} in {2}")
	@MethodSource("attributesWritesAndStates")
	void shouldKeepBothKeysAfterRefusalAndReload(
		@Nonnull UniqueAttribute attribute,
		@Nonnull RefusedWrite write,
		@Nonnull CatalogState state
	) {
		prepareCatalog(attribute, state);
		assertRefused(attribute, write);

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// the catalog is loaded in the background - its load is what reads the unique indexes back
		this.evita.waitUntilFullyInitialized();

		assertAll(intactState(attribute));
		assertClaimRefused(attribute, attribute.writerValue, "the writer");
		assertClaimRefused(attribute, attribute.holderValue, "the holder");
	}

	/**
	 * Defines the schema, writes `attribute.writerValue` to the writer and `attribute.holderValue` to the holder and
	 * leaves the catalog in `state`.
	 *
	 * @param attribute the attribute under test
	 * @param state     the state the catalog is left in
	 */
	private void prepareCatalog(@Nonnull UniqueAttribute attribute, @Nonnull CatalogState state) {
		defineSchema();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				writeNewEntity(session, attribute, ALPHA, WRITER_PK, attribute.writerValue);
				writeNewEntity(session, attribute, BETA, HOLDER_PK, attribute.holderValue);
			}
		);
		if (state == CatalogState.ALIVE) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
	}

	/**
	 * Defines the catalog attributes and the two collections described on the class.
	 */
	private void defineSchema() {
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(
				UniqueAttribute.INTEGER.attributeName, Integer.class,
				thatIs -> thatIs.uniqueGlobally().nullable()
			)
			.withAttribute(
				UniqueAttribute.LOCALIZED_INTEGER.attributeName, Integer.class,
				thatIs -> thatIs.localized().uniqueGloballyWithinLocale().nullable()
			)
			.withAttribute(
				UniqueAttribute.DECIMAL.attributeName, BigDecimal.class,
				thatIs -> thatIs.uniqueGlobally().nullable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
			)
			.withAttribute(
				UniqueAttribute.LOCALIZED_DECIMAL.attributeName, BigDecimal.class,
				thatIs -> thatIs.localized().uniqueGloballyWithinLocale().nullable()
					.indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
			)
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (final String collection : List.of(ALPHA, BETA)) {
					session.defineEntitySchema(collection)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withGlobalAttribute(UniqueAttribute.INTEGER.attributeName)
						.withGlobalAttribute(UniqueAttribute.LOCALIZED_INTEGER.attributeName)
						.withGlobalAttribute(UniqueAttribute.DECIMAL.attributeName)
						.withGlobalAttribute(UniqueAttribute.LOCALIZED_DECIMAL.attributeName)
						.updateVia(session);
				}
			}
		);
	}

	/**
	 * Writes a new entity `pk` into `collection` holding `value` in the attribute under test.
	 *
	 * @param session    the read-write session
	 * @param attribute  the attribute under test
	 * @param collection the collection of the new entity
	 * @param pk         the primary key of the new entity
	 * @param value      the value of the attribute
	 */
	private static void writeNewEntity(
		@Nonnull EvitaSessionContract session,
		@Nonnull UniqueAttribute attribute,
		@Nonnull String collection,
		int pk,
		@Nonnull Serializable value
	) {
		session.upsertEntity(
			new EntityUpsertMutation(
				collection, pk, EntityExistence.MUST_NOT_EXIST,
				new UpsertAttributeMutation(attribute.key(), value)
			)
		);
	}

	/**
	 * Applies `mutation` to the writer, in its own session.
	 *
	 * @param mutation the attribute mutation to apply
	 */
	private void writeToWriter(@Nonnull AttributeMutation mutation) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(new EntityUpsertMutation(ALPHA, WRITER_PK, EntityExistence.MUST_EXIST, mutation));
			}
		);
	}

	/**
	 * Asserts that the writer is refused `write`, which would move its key onto the holder's value.
	 *
	 * @param attribute the attribute under test
	 * @param write     the refused write
	 */
	private void assertRefused(@Nonnull UniqueAttribute attribute, @Nonnull RefusedWrite write) {
		final AttributeMutation mutation = switch (write) {
			case DELTA -> new ApplyDeltaAttributeMutation<>(attribute.key(), attribute.refusedDelta);
			case UPSERT -> new UpsertAttributeMutation(attribute.key(), attribute.holderValue);
		};
		assertThrows(
			UniqueValueViolationException.class,
			() -> writeToWriter(mutation),
			"The " + write + " on `" + attribute.attributeName + "` was accepted, though it produced `" +
				attribute.holderValue + "` entity " + HOLDER_PK + " in `" + BETA + "` holds!"
		);
	}

	/**
	 * Asserts that writing entity {@link #CLAIMANT_PK} to {@link #BETA} holding `value` is refused as a unique value
	 * violation.
	 *
	 * @param attribute the attribute under test
	 * @param value     the claimed value
	 * @param owner     the entity holding `value`, for the failure message
	 */
	private void assertClaimRefused(
		@Nonnull UniqueAttribute attribute,
		@Nonnull Serializable value,
		@Nonnull String owner
	) {
		assertThrows(
			UniqueValueViolationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					writeNewEntity(session, attribute, BETA, CLAIMANT_PK, value);
				}
			),
			"Entity " + CLAIMANT_PK + " in `" + BETA + "` was accepted with `" + value + "` in `" +
				attribute.attributeName + "`, which " + owner + " holds!"
		);
	}

	/**
	 * Returns checks that the bodies and every lookup show the state before the refused write: the writer holds
	 * `attribute.writerValue`, the holder `attribute.holderValue`, and the values the writer was or will be offered
	 * find nobody.
	 *
	 * @param attribute the attribute under test
	 * @return the checks
	 */
	@Nonnull
	private List<Executable> intactState(@Nonnull UniqueAttribute attribute) {
		final List<Executable> checks = new ArrayList<>(32);
		checks.add(() -> assertBodyHolds(attribute, ALPHA, WRITER_PK, attribute.writerValue));
		checks.add(() -> assertBodyHolds(attribute, BETA, HOLDER_PK, attribute.holderValue));
		checks.addAll(lookupsFind(attribute, attribute.writerValue, ALPHA, WRITER_PK));
		checks.addAll(lookupsFind(attribute, attribute.holderValue, BETA, HOLDER_PK));
		checks.addAll(lookupsFindNothing(attribute, attribute.furtherDeltaValue));
		checks.addAll(lookupsFindNothing(attribute, attribute.furtherUpsertValue));
		return checks;
	}

	/**
	 * Asserts that the body of entity `pk` in `collection` holds a value numerically equal to `expected` in the
	 * attribute under test.
	 *
	 * @param attribute  the attribute under test
	 * @param collection the collection of the entity
	 * @param pk         the primary key of the entity
	 * @param expected   the expected value
	 */
	private void assertBodyHolds(
		@Nonnull UniqueAttribute attribute,
		@Nonnull String collection,
		int pk,
		@Nonnull Serializable expected
	) {
		final Serializable stored = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(
					collection, pk, attributeContentAll(), dataInLocalesAll()
				).orElseThrow();
				return attribute.localized
					? entity.getAttribute(attribute.attributeName, Locale.ENGLISH)
					: entity.getAttribute(attribute.attributeName);
			}
		);
		assertEquals(
			canonical(expected), stored == null ? null : canonical(stored),
			"The body of `" + collection + ":" + pk + "` holds `" + stored + "` in `" + attribute.attributeName + "`!"
		);
	}

	/**
	 * Returns checks that every spelling of `value` finds exactly entity `expectedPk` of `expectedCollection` -
	 * within that collection and across the whole catalog.
	 *
	 * @param attribute          the attribute under test
	 * @param value              the looked-up value
	 * @param expectedCollection the collection of the entity expected
	 * @param expectedPk         the primary key of the entity expected
	 * @return one check per lookup
	 */
	@Nonnull
	private List<Executable> lookupsFind(
		@Nonnull UniqueAttribute attribute,
		@Nonnull Serializable value,
		@Nonnull String expectedCollection,
		int expectedPk
	) {
		final List<String> expected = List.of(expectedCollection + ":" + expectedPk);
		final List<Executable> checks = new ArrayList<>(8);
		for (final Serializable probe : spellings(value)) {
			for (final String collection : new String[]{expectedCollection, null}) {
				checks.add(() -> assertEquals(
					expected, lookUp(attribute, collection, probe),
					describeLookup(attribute, collection, probe) + " found the wrong entities!"
				));
			}
		}
		return checks;
	}

	/**
	 * Returns checks that no spelling of `value` finds any entity - within each collection of the catalog and across
	 * the whole catalog.
	 *
	 * @param attribute the attribute under test
	 * @param value     the looked-up value
	 * @return one check per lookup
	 */
	@Nonnull
	private List<Executable> lookupsFindNothing(@Nonnull UniqueAttribute attribute, @Nonnull Serializable value) {
		final List<Executable> checks = new ArrayList<>(12);
		for (final Serializable probe : spellings(value)) {
			for (final String collection : new String[]{ALPHA, BETA, null}) {
				checks.add(() -> assertEquals(
					List.of(), lookUp(attribute, collection, probe),
					describeLookup(attribute, collection, probe) + " found a stale entity!"
				));
			}
		}
		return checks;
	}

	/**
	 * Looks up entities by `attributeEquals` on the attribute under test, within `collection` or across the whole
	 * catalog when it is `null`.
	 *
	 * @param attribute  the attribute under test
	 * @param collection the collection to look in, `null` for the whole catalog
	 * @param probe      the looked-up value
	 * @return the found entities rendered as `type:pk`
	 */
	@Nonnull
	private List<String> lookUp(
		@Nonnull UniqueAttribute attribute,
		@Nullable String collection,
		@Nonnull Serializable probe
	) {
		final FilterConstraint equals = attributeEquals(attribute.attributeName, probe);
		final FilterConstraint filter = attribute.localized ? and(equals, entityLocaleEquals(Locale.ENGLISH)) : equals;
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfEntityReferences(
						collection == null
							? Query.query(filterBy(filter))
							: Query.query(collection(collection), filterBy(filter))
					)
					.stream()
					.map(GlobalUniqueAttributeDeltaRefusalFunctionalTest::describe)
					.toList();
			}
		);
	}

	/**
	 * Describes a lookup for a failure message.
	 *
	 * @param attribute  the attribute under test
	 * @param collection the collection looked in, `null` for the whole catalog
	 * @param probe      the looked-up value
	 * @return the description
	 */
	@Nonnull
	private static String describeLookup(
		@Nonnull UniqueAttribute attribute,
		@Nullable String collection,
		@Nonnull Serializable probe
	) {
		return (collection == null ? "Catalog-wide lookup" : "Lookup in `" + collection + "`") +
			" by `" + attribute.attributeName + "` = `" + probe + "`";
	}

	/**
	 * Returns the spellings `value` is looked up by - the written one, and for a `BigDecimal` with trailing zeros
	 * also the stripped one.
	 *
	 * @param value the value
	 * @return the distinct spellings of the value
	 */
	@Nonnull
	private static List<Serializable> spellings(@Nonnull Serializable value) {
		return value instanceof BigDecimal decimal && !decimal.equals(decimal.stripTrailingZeros())
			? List.of(decimal, decimal.stripTrailingZeros())
			: List.of(value);
	}

	/**
	 * Renders a reference as `type:pk` so a mismatch reads directly in the assertion message.
	 *
	 * @param reference the found entity
	 * @return the rendered reference
	 */
	@Nonnull
	private static String describe(@Nonnull EntityReferenceContract reference) {
		return reference.getType() + ":" + reference.getPrimaryKey();
	}

	/**
	 * Returns `value` in a form equal for numerically equal values - a `BigDecimal` with its trailing zeros stripped.
	 *
	 * @param value the value to compare
	 * @return the comparable form
	 */
	@Nonnull
	private static Serializable canonical(@Nonnull Serializable value) {
		return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value;
	}

}
