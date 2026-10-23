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
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a numeric delta applied to a unique attribute moves its unique key exactly as an upsert of the resulting
 * value would - in the catalog-wide unique index for a `uniqueGlobally` / `uniqueGloballyWithinLocale` attribute, and
 * in the collection's unique index for a plain `unique` one, which serves as the control.
 *
 * After `code = 1` receives a delta of `+1`, the entity must be found by `2` and no longer by `1`, another entity must
 * be able to claim the released `1` and must be refused claiming `2`, and the key must go when the attribute or the
 * entity goes. A globally unique key is shared by every collection of the catalog, so the claimants of a global
 * attribute live in the second collection; the claimants of the control live in the same collection.
 *
 * Every scenario runs in both catalog states, because a warming-up catalog writes its indexes in place while an alive
 * one writes them in a transaction, and once more after the catalog is reloaded from disk, because the catalog-wide
 * unique index is persisted on its own.
 *
 * ## The fixture
 *
 * | attribute | declared on | declared as | written | delta | result |
 * |---|---|---|---|---|---|
 * | `globalCode` | catalog | `uniqueGlobally` | `1` | `+1` | `2` |
 * | `globalLocalCode` | catalog | localized, `uniqueGloballyWithinLocale` | `1` | `+1` | `2` |
 * | `globalPrice` | catalog | `uniqueGlobally`, 2 decimal places | `1.50` | `+1` | `2.50` |
 * | `globalLocalPrice` | catalog | localized, `uniqueGloballyWithinLocale`, 2 decimal places | `1.50` | `+1` | `2.50` |
 * | `ownCode` | collection `alpha` | `unique` | `1` | `+1` | `2` |
 * | `ownPrice` | collection `alpha` | `unique`, 2 decimal places | `1.50` | `+1` | `2.50` |
 *
 * The decimal values carry a trailing zero, and every lookup of them is made by both the written spelling and the
 * stripped one, because the unique indexes key a `BigDecimal` with its trailing zeros stripped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Numeric delta on a unique attribute moves its unique key")
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(STORAGE)
class GlobalUniqueAttributeDeltaFunctionalTest implements EvitaTestSupport {
	private static final String ALPHA = "alpha";
	private static final String BETA = "beta";
	private static final int INDEXED_DECIMAL_PLACES = 2;
	/**
	 * Primary key of the entity in {@link #ALPHA} the delta is applied to.
	 */
	private static final int DELTA_PK = 1;
	/**
	 * Primary key of the entity claiming the value the delta released.
	 */
	private static final int OLD_VALUE_CLAIMANT_PK = 11;
	/**
	 * Primary key of the entity claiming the value the delta produced.
	 */
	private static final int NEW_VALUE_CLAIMANT_PK = 12;

	/**
	 * One unique attribute of the fixture, with the value written, the delta applied and the spellings by which
	 * the value before and after the delta are looked up.
	 */
	enum UniqueAttribute {
		GLOBAL_INTEGER("globalCode", true, false, 1, 1, new Serializable[]{1}, new Serializable[]{2}),
		GLOBAL_LOCALIZED_INTEGER("globalLocalCode", true, true, 1, 1, new Serializable[]{1}, new Serializable[]{2}),
		GLOBAL_DECIMAL(
			"globalPrice", true, false, new BigDecimal("1.50"), BigDecimal.ONE,
			new Serializable[]{new BigDecimal("1.50"), new BigDecimal("1.5")},
			new Serializable[]{new BigDecimal("2.50"), new BigDecimal("2.5")}
		),
		GLOBAL_LOCALIZED_DECIMAL(
			"globalLocalPrice", true, true, new BigDecimal("1.50"), BigDecimal.ONE,
			new Serializable[]{new BigDecimal("1.50"), new BigDecimal("1.5")},
			new Serializable[]{new BigDecimal("2.50"), new BigDecimal("2.5")}
		),
		COLLECTION_INTEGER("ownCode", false, false, 1, 1, new Serializable[]{1}, new Serializable[]{2}),
		COLLECTION_DECIMAL(
			"ownPrice", false, false, new BigDecimal("1.50"), BigDecimal.ONE,
			new Serializable[]{new BigDecimal("1.50"), new BigDecimal("1.5")},
			new Serializable[]{new BigDecimal("2.50"), new BigDecimal("2.5")}
		);

		private final String attributeName;
		private final boolean global;
		private final boolean localized;
		private final Serializable writtenValue;
		private final Number delta;
		private final Serializable[] oldValueProbes;
		private final Serializable[] newValueProbes;

		UniqueAttribute(
			@Nonnull String attributeName,
			boolean global,
			boolean localized,
			@Nonnull Serializable writtenValue,
			@Nonnull Number delta,
			@Nonnull Serializable[] oldValueProbes,
			@Nonnull Serializable[] newValueProbes
		) {
			this.attributeName = attributeName;
			this.global = global;
			this.localized = localized;
			this.writtenValue = writtenValue;
			this.delta = delta;
			this.oldValueProbes = oldValueProbes;
			this.newValueProbes = newValueProbes;
		}

		/**
		 * Returns the locale the attribute is written in, `null` when it is not localized.
		 *
		 * @return the locale of the attribute value
		 */
		@Nullable
		Locale locale() {
			return this.localized ? Locale.ENGLISH : null;
		}

		/**
		 * Returns the collection the claimants of the attribute's values are written to - the other collection for
		 * a globally unique attribute, the delta entity's own for a collection-unique one.
		 *
		 * @return the claimant collection
		 */
		@Nonnull
		String claimantCollection() {
			return this.global ? BETA : ALPHA;
		}

		/**
		 * Returns the value the delta produces, spelled as written.
		 *
		 * @return the value after the delta
		 */
		@Nonnull
		Serializable newValue() {
			return this.newValueProbes[0];
		}
	}

	private TestPaths paths;
	private Evita evita;

	/**
	 * Returns every attribute of the fixture combined with every catalog state the delta is applied in.
	 *
	 * @return the arguments of the parameterized tests
	 */
	@Nonnull
	static Stream<Arguments> attributesAndStates() {
		return Arrays.stream(UniqueAttribute.values())
			.flatMap(
				attribute -> Stream.of(CatalogState.WARMING_UP, CatalogState.ALIVE)
					.map(state -> Arguments.of(attribute, state))
			);
	}

	/**
	 * Starts an engine on fresh storage.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("GlobalUniqueAttributeDelta");
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
	 * The delta moves the key: the produced value finds the entity, the replaced one finds nothing.
	 */
	@DisplayName("Should find the entity by the value the delta produced and no longer by the one it replaced")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("attributesAndStates")
	void shouldMoveUniqueKeyWithDelta(@Nonnull UniqueAttribute attribute, @Nonnull CatalogState state) {
		prepareCatalog(attribute, state, false);
		applyDelta(attribute);

		final List<Executable> checks = new ArrayList<>(16);
		checks.add(() -> assertBodyHolds(attribute, attribute.newValue()));
		checks.addAll(lookupsFind(attribute, attribute.newValueProbes, ALPHA, DELTA_PK));
		checks.addAll(lookupsFindNothing(attribute, attribute.oldValueProbes));
		assertAll(checks);
	}

	/**
	 * The replaced value is free for another entity to claim, the produced one is guarded against it.
	 */
	@DisplayName("Should let another entity claim the released value and refuse it the produced one")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("attributesAndStates")
	void shouldReleaseOldValueAndGuardNewOne(@Nonnull UniqueAttribute attribute, @Nonnull CatalogState state) {
		prepareCatalog(attribute, state, false);
		applyDelta(attribute);

		assertClaimAccepted(attribute, OLD_VALUE_CLAIMANT_PK, attribute.writtenValue);
		assertAll(
			lookupsFind(attribute, attribute.oldValueProbes, attribute.claimantCollection(), OLD_VALUE_CLAIMANT_PK)
		);
		// a refused write leaves a warming-up catalog unusable, so it comes last
		assertClaimRefused(attribute, NEW_VALUE_CLAIMANT_PK, attribute.newValue());
	}

	/**
	 * A delta is refused when the value it produces is held by another entity.
	 */
	@DisplayName("Should refuse a delta producing a value another entity holds")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("attributesAndStates")
	void shouldRefuseDeltaIntoTakenValue(@Nonnull UniqueAttribute attribute, @Nonnull CatalogState state) {
		prepareCatalog(attribute, state, true);

		assertThrows(
			UniqueValueViolationException.class,
			() -> applyDelta(attribute),
			"The delta on `" + attribute.attributeName + "` produced a value entity " + NEW_VALUE_CLAIMANT_PK +
				" in `" + attribute.claimantCollection() + "` already holds!"
		);
	}

	/**
	 * Removing the attribute after the delta frees the produced value, and leaves the replaced one free.
	 */
	@DisplayName("Should drop the produced value when the attribute is removed after the delta")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("attributesAndStates")
	void shouldDropUniqueKeyWhenAttributeRemovedAfterDelta(
		@Nonnull UniqueAttribute attribute,
		@Nonnull CatalogState state
	) {
		prepareCatalog(attribute, state, false);
		applyDelta(attribute);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					new EntityUpsertMutation(
						ALPHA, DELTA_PK, EntityExistence.MUST_EXIST,
						attribute.localized
							? new RemoveAttributeMutation(attribute.attributeName, Locale.ENGLISH)
							: new RemoveAttributeMutation(attribute.attributeName)
					)
				);
			}
		);

		final List<Executable> checks = new ArrayList<>(lookupsFindNothing(attribute, attribute.newValueProbes));
		checks.addAll(lookupsFindNothing(attribute, attribute.oldValueProbes));
		assertAll(checks);
		assertClaimAccepted(attribute, NEW_VALUE_CLAIMANT_PK, attribute.newValue());
		assertClaimAccepted(attribute, OLD_VALUE_CLAIMANT_PK, attribute.writtenValue);
	}

	/**
	 * Removing the entity after the delta frees the produced value, and leaves the replaced one free.
	 */
	@DisplayName("Should drop the produced value when the entity is removed after the delta")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("attributesAndStates")
	void shouldDropUniqueKeyWhenEntityRemovedAfterDelta(
		@Nonnull UniqueAttribute attribute,
		@Nonnull CatalogState state
	) {
		prepareCatalog(attribute, state, false);
		applyDelta(attribute);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.deleteEntity(ALPHA, DELTA_PK), "The entity the delta was applied to is gone!");
			}
		);

		final List<Executable> checks = new ArrayList<>(lookupsFindNothing(attribute, attribute.newValueProbes));
		checks.addAll(lookupsFindNothing(attribute, attribute.oldValueProbes));
		assertAll(checks);
		assertClaimAccepted(attribute, NEW_VALUE_CLAIMANT_PK, attribute.newValue());
		assertClaimAccepted(attribute, OLD_VALUE_CLAIMANT_PK, attribute.writtenValue);
	}

	/**
	 * The moved key is what the catalog persisted: after a reload the indexes answer as they did before it.
	 */
	@DisplayName("Should keep the moved key after the catalog is reloaded")
	@ParameterizedTest(name = "{0} in {1}")
	@MethodSource("attributesAndStates")
	void shouldKeepMovedUniqueKeyAfterReload(@Nonnull UniqueAttribute attribute, @Nonnull CatalogState state) {
		prepareCatalog(attribute, state, false);
		applyDelta(attribute);

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// the catalog is loaded in the background - its load is what reads the unique indexes back
		this.evita.waitUntilFullyInitialized();

		final List<Executable> checks = new ArrayList<>(16);
		checks.add(() -> assertBodyHolds(attribute, attribute.newValue()));
		checks.addAll(lookupsFind(attribute, attribute.newValueProbes, ALPHA, DELTA_PK));
		checks.addAll(lookupsFindNothing(attribute, attribute.oldValueProbes));
		assertAll(checks);
		assertClaimAccepted(attribute, OLD_VALUE_CLAIMANT_PK, attribute.writtenValue);
		// a refused write leaves a warming-up catalog unusable, so it comes last
		assertClaimRefused(attribute, NEW_VALUE_CLAIMANT_PK, attribute.newValue());
	}

	/**
	 * Defines the schema, writes `attribute.writtenValue` to entity {@link #DELTA_PK} of {@link #ALPHA} and leaves
	 * the catalog in `state`.
	 *
	 * @param attribute          the attribute under test
	 * @param state              the state the catalog is left in
	 * @param newValueTakenAlready when `true`, entity {@link #NEW_VALUE_CLAIMANT_PK} of the claimant collection is
	 *                           written holding the value the delta would produce
	 */
	private void prepareCatalog(
		@Nonnull UniqueAttribute attribute,
		@Nonnull CatalogState state,
		boolean newValueTakenAlready
	) {
		defineSchema();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				writeValue(session, attribute, ALPHA, DELTA_PK, attribute.writtenValue);
				if (newValueTakenAlready) {
					writeValue(
						session, attribute, attribute.claimantCollection(), NEW_VALUE_CLAIMANT_PK, attribute.newValue()
					);
				}
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
				UniqueAttribute.GLOBAL_INTEGER.attributeName, Integer.class,
				thatIs -> thatIs.uniqueGlobally().nullable()
			)
			.withAttribute(
				UniqueAttribute.GLOBAL_LOCALIZED_INTEGER.attributeName, Integer.class,
				thatIs -> thatIs.localized().uniqueGloballyWithinLocale().nullable()
			)
			.withAttribute(
				UniqueAttribute.GLOBAL_DECIMAL.attributeName, BigDecimal.class,
				thatIs -> thatIs.uniqueGlobally().nullable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
			)
			.withAttribute(
				UniqueAttribute.GLOBAL_LOCALIZED_DECIMAL.attributeName, BigDecimal.class,
				thatIs -> thatIs.localized().uniqueGloballyWithinLocale().nullable()
					.indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
			)
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ALPHA)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withGlobalAttribute(UniqueAttribute.GLOBAL_INTEGER.attributeName)
					.withGlobalAttribute(UniqueAttribute.GLOBAL_LOCALIZED_INTEGER.attributeName)
					.withGlobalAttribute(UniqueAttribute.GLOBAL_DECIMAL.attributeName)
					.withGlobalAttribute(UniqueAttribute.GLOBAL_LOCALIZED_DECIMAL.attributeName)
					.withAttribute(
						UniqueAttribute.COLLECTION_INTEGER.attributeName, Integer.class,
						thatIs -> thatIs.unique().nullable()
					)
					.withAttribute(
						UniqueAttribute.COLLECTION_DECIMAL.attributeName, BigDecimal.class,
						thatIs -> thatIs.unique().nullable().indexDecimalPlaces(INDEXED_DECIMAL_PLACES)
					)
					.updateVia(session);
				session.defineEntitySchema(BETA)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withGlobalAttribute(UniqueAttribute.GLOBAL_INTEGER.attributeName)
					.withGlobalAttribute(UniqueAttribute.GLOBAL_LOCALIZED_INTEGER.attributeName)
					.withGlobalAttribute(UniqueAttribute.GLOBAL_DECIMAL.attributeName)
					.withGlobalAttribute(UniqueAttribute.GLOBAL_LOCALIZED_DECIMAL.attributeName)
					.updateVia(session);
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
	private static void writeValue(
		@Nonnull EvitaSessionContract session,
		@Nonnull UniqueAttribute attribute,
		@Nonnull String collection,
		int pk,
		@Nonnull Serializable value
	) {
		if (attribute.localized) {
			session.createNewEntity(collection, pk)
				.setAttribute(attribute.attributeName, Locale.ENGLISH, value)
				.upsertVia(session);
		} else {
			session.createNewEntity(collection, pk)
				.setAttribute(attribute.attributeName, value)
				.upsertVia(session);
		}
	}

	/**
	 * Applies `attribute.delta` to the attribute under test of entity {@link #DELTA_PK} in {@link #ALPHA}, in its own
	 * session.
	 *
	 * @param attribute the attribute under test
	 */
	private void applyDelta(@Nonnull UniqueAttribute attribute) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					new EntityUpsertMutation(
						ALPHA, DELTA_PK, EntityExistence.MUST_EXIST,
						new ApplyDeltaAttributeMutation<>(attribute.attributeName, attribute.locale(), attribute.delta)
					)
				);
			}
		);
	}

	/**
	 * Asserts that entity `pk` can be written to the claimant collection holding `value`.
	 *
	 * @param attribute the attribute under test
	 * @param pk        the primary key of the claimant
	 * @param value     the claimed value
	 */
	private void assertClaimAccepted(@Nonnull UniqueAttribute attribute, int pk, @Nonnull Serializable value) {
		assertDoesNotThrow(
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					writeValue(session, attribute, attribute.claimantCollection(), pk, value);
				}
			),
			"Entity " + pk + " in `" + attribute.claimantCollection() + "` was refused `" + value + "` in `" +
				attribute.attributeName + "`, which no entity holds!"
		);
	}

	/**
	 * Asserts that writing entity `pk` to the claimant collection holding `value` is refused as a unique value
	 * violation.
	 *
	 * @param attribute the attribute under test
	 * @param pk        the primary key of the claimant
	 * @param value     the claimed value
	 */
	private void assertClaimRefused(@Nonnull UniqueAttribute attribute, int pk, @Nonnull Serializable value) {
		assertThrows(
			UniqueValueViolationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					writeValue(session, attribute, attribute.claimantCollection(), pk, value);
				}
			),
			"Entity " + pk + " in `" + attribute.claimantCollection() + "` was accepted with `" + value + "` in `" +
				attribute.attributeName + "`, which entity " + DELTA_PK + " in `" + ALPHA + "` holds!"
		);
	}

	/**
	 * Asserts that the body of entity {@link #DELTA_PK} in {@link #ALPHA} holds a value numerically equal to
	 * `expected` in the attribute under test.
	 *
	 * @param attribute the attribute under test
	 * @param expected  the expected value
	 */
	private void assertBodyHolds(@Nonnull UniqueAttribute attribute, @Nonnull Serializable expected) {
		final Serializable stored = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(
					ALPHA, DELTA_PK, attributeContentAll(), dataInLocalesAll()
				).orElseThrow();
				return attribute.localized
					? entity.getAttribute(attribute.attributeName, Locale.ENGLISH)
					: entity.getAttribute(attribute.attributeName);
			}
		);
		assertEquals(
			canonical(expected), stored == null ? null : canonical(stored),
			"The body of the delta entity holds `" + stored + "` in `" + attribute.attributeName + "`!"
		);
	}

	/**
	 * Returns checks that each of `probes` finds exactly entity `expectedPk` of `expectedCollection` - within that
	 * collection, and across the whole catalog for a globally unique attribute.
	 *
	 * @param attribute          the attribute under test
	 * @param probes             the looked-up spellings of one value
	 * @param expectedCollection the collection of the entity expected
	 * @param expectedPk         the primary key of the entity expected
	 * @return one check per lookup
	 */
	@Nonnull
	private List<Executable> lookupsFind(
		@Nonnull UniqueAttribute attribute,
		@Nonnull Serializable[] probes,
		@Nonnull String expectedCollection,
		int expectedPk
	) {
		final List<String> expected = List.of(expectedCollection + ":" + expectedPk);
		final List<Executable> checks = new ArrayList<>(probes.length * 2);
		for (final Serializable probe : probes) {
			checks.add(() -> assertEquals(
				expected, lookUp(attribute, expectedCollection, probe),
				"Collection lookup by `" + attribute.attributeName + "` = `" + probe + "` missed the entity!"
			));
			if (attribute.global) {
				checks.add(() -> assertEquals(
					expected, lookUp(attribute, null, probe),
					"Catalog-wide lookup by `" + attribute.attributeName + "` = `" + probe + "` missed the entity!"
				));
			}
		}
		return checks;
	}

	/**
	 * Returns checks that none of `probes` finds any entity - within {@link #ALPHA}, and across the whole catalog for
	 * a globally unique attribute.
	 *
	 * @param attribute the attribute under test
	 * @param probes    the looked-up spellings of one value
	 * @return one check per lookup
	 */
	@Nonnull
	private List<Executable> lookupsFindNothing(@Nonnull UniqueAttribute attribute, @Nonnull Serializable[] probes) {
		final List<Executable> checks = new ArrayList<>(probes.length * 2);
		for (final Serializable probe : probes) {
			checks.add(() -> assertEquals(
				List.of(), lookUp(attribute, ALPHA, probe),
				"Collection lookup by `" + attribute.attributeName + "` = `" + probe + "` found a stale entity!"
			));
			if (attribute.global) {
				checks.add(() -> assertEquals(
					List.of(), lookUp(attribute, null, probe),
					"Catalog-wide lookup by `" + attribute.attributeName + "` = `" + probe + "` found a stale entity!"
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
					.map(GlobalUniqueAttributeDeltaFunctionalTest::describe)
					.toList();
			}
		);
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
