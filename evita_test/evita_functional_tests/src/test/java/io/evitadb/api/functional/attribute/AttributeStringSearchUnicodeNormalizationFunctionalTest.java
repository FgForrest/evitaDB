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
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContains;
import static io.evitadb.api.query.QueryConstraints.attributeEndsWith;
import static io.evitadb.api.query.QueryConstraints.attributeStartsWith;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end coverage for the Unicode canonical-equivalence contract of the string-search attribute filters
 * (`attributeStartsWith` / `attributeEndsWith` / `attributeContains`).
 *
 * The {@link io.evitadb.index.attribute.FilterIndex} normalizes every stored String key to Unicode **NFD**
 * (decomposed) form and normalizes the incoming search term to NFD as well, so a precomposed (NFC) term typed
 * by a user matches a value stored in any canonically-equivalent form. The query engine, however, can answer
 * the same filter from two interchangeable code paths: the inverted **INDEX** and the **PREFETCH** alternative
 * (a `SelectionFormula` that scans the prefetched raw entity attribute values). Both paths must yield identical
 * results.
 *
 * These tests store the attribute value in **decomposed (NFD)** form and query with the **precomposed (NFC)**
 * equivalent term — the form a user normally types — while forcing the engine onto the PREFETCH path via
 * {@link DebugMode#PREFER_PREFETCHING} plus an explicit `entityPrimaryKeyInSet`. The index path is already
 * canonical-equivalence-correct, so a divergence can only originate from the prefetch predicate comparing raw
 * (non-normalized) strings.
 *
 * The non-localized cases above run the index path through `FilterIndex#getRecordsWhoseValuesStartWith`'s
 * natural-codepoint comparator, which walks one contiguous prefix run off the value cursor and early-breaks at
 * the first miss. A **localized** String attribute instead installs a `LocalizedStringComparator` (a non-default
 * collation comparator), so that prefix run is no longer guaranteed contiguous and the index path falls back to
 * a full predicate scan over every bucket. The localized cases force that collation branch and assert the same
 * NFC/NFD equivalence and index-vs-prefetch interchangeability holds there too.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute string search — Unicode NFC/NFD equivalence on the prefetch path")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class AttributeStringSearchUnicodeNormalizationFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ATTR_NAME = "name";
	private static final int PK = 1;

	/**
	 * Decomposed (NFD) "café": the letters `c`, `a`, `f`, `e` followed by U+0301 COMBINING ACUTE ACCENT —
	 * `é`. This is the value stored on the entity attribute.
	 */
	private static final String NFD_CAFE = "café";
	/**
	 * Precomposed (NFC) "café": the final character is U+00E9 LATIN SMALL LETTER E WITH ACUTE — `é`.
	 * This is the search-term form a user normally types.
	 */
	private static final String NFC_CAFE = "café";
	/**
	 * Precomposed (NFC) "é" — a one-character suffix used by the `endsWith` probe.
	 */
	private static final String NFC_E_ACUTE = "é";
	/**
	 * Precomposed (NFC) "fé" — an inner substring used by the `contains` probe.
	 */
	private static final String NFC_F_E_ACUTE = "fé";

	/**
	 * Locale under which the localized variant stores and queries the `name` attribute. French maps to a real
	 * non-default `Collator`, so the localized `FilterIndex` installs a `LocalizedStringComparator` and the
	 * index-path `startsWith` falls back to the full-scan collation branch (no contiguous-run early break).
	 */
	private static final Locale SEARCH_LOCALE = Locale.FRENCH;

	/**
	 * Installs the single-entity schema whose `name` attribute is a filterable String.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_NAME, String.class,
				whichIs -> whichIs.filterable().nullable()
			)
			.updateVia(session);
	}

	/**
	 * Installs the single-entity schema whose `name` attribute is a filterable **localized** String. A localized
	 * String attribute drives the `FilterIndex` to use a `LocalizedStringComparator` (a non-default collation
	 * comparator) for {@link #SEARCH_LOCALE}, which routes `startsWith` through the full-scan branch of
	 * `FilterIndex#getRecordsWhoseValuesStartWith`.
	 */
	private static void defineLocalizedSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_NAME, String.class,
				whichIs -> whichIs.filterable().localized().nullable()
			)
			.updateVia(session);
	}

	/**
	 * Runs the supplied filter against the catalog while forcing the PREFETCH path via
	 * {@link DebugMode#PREFER_PREFETCHING} and a known `entityPrimaryKeyInSet`, returning the matched primary
	 * keys as a sorted set so assertions are order-independent.
	 */
	@Nonnull
	private static Set<Integer> matchedPrimaryKeysViaPrefetch(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint filter
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(PK),
					filter
				),
				require(
					debug(DebugMode.PREFER_PREFETCHING),
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Runs the supplied filter against the catalog on the regular (index) path, returning the matched primary
	 * keys as a sorted set.
	 */
	@Nonnull
	private static Set<Integer> matchedPrimaryKeysViaIndex(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint filter
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(filter),
				require(
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Localized counterpart of {@link #matchedPrimaryKeysViaPrefetch}: runs the supplied filter under
	 * {@link #SEARCH_LOCALE} (required for a localized attribute) while forcing the PREFETCH path via
	 * {@link DebugMode#PREFER_PREFETCHING} and a known `entityPrimaryKeyInSet`.
	 */
	@Nonnull
	private static Set<Integer> matchedPrimaryKeysViaLocalizedPrefetch(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint filter
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(PK),
					entityLocaleEquals(SEARCH_LOCALE),
					filter
				),
				require(
					debug(DebugMode.PREFER_PREFETCHING),
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Localized counterpart of {@link #matchedPrimaryKeysViaIndex}: runs the supplied filter under
	 * {@link #SEARCH_LOCALE} on the regular (index) path, which selects the localized `FilterIndex` and its
	 * `LocalizedStringComparator` collation branch.
	 */
	@Nonnull
	private static Set<Integer> matchedPrimaryKeysViaLocalizedIndex(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint filter
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					entityLocaleEquals(SEARCH_LOCALE),
					filter
				),
				require(
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Builds an ordered set of the supplied primary keys for comparison against query results.
	 */
	@Nonnull
	private static Set<Integer> setOf(@Nonnull Integer... pks) {
		return new LinkedHashSet<>(Arrays.asList(pks));
	}

	/**
	 * Builds the standard test configuration with a disabled session inactivity timeout and per-test
	 * storage / export directories.
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Spins up a fresh Evita instance bound to per-test directories, seeds the single decomposed-value entity
	 * in a writable catalog, hands an open read session to the caller's assertions, and tears the instance
	 * down afterwards.
	 */
	private void runWithCatalog(
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> assertions
	) {
		final TestPaths paths = createTestPaths(label);
		try (
			Evita evita = new Evita(createConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineSchema(session);
					session.createNewEntity(ENTITY_PRODUCT, PK)
						.setAttribute(ATTR_NAME, NFD_CAFE)
						.upsertVia(session);
					session.goLiveAndClose();
				}
			);
			evita.queryCatalog(TEST_CATALOG, assertions);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Localized counterpart of {@link #runWithCatalog}: seeds the single entity with the decomposed (NFD) value
	 * stored under {@link #SEARCH_LOCALE} against the localized schema, then hands an open read session to the
	 * caller's assertions.
	 */
	private void runWithLocalizedCatalog(
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> assertions
	) {
		final TestPaths paths = createTestPaths(label);
		try (
			Evita evita = new Evita(createConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineLocalizedSchema(session);
					session.createNewEntity(ENTITY_PRODUCT, PK)
						.setAttribute(ATTR_NAME, SEARCH_LOCALE, NFD_CAFE)
						.upsertVia(session);
					session.goLiveAndClose();
				}
			);
			evita.queryCatalog(TEST_CATALOG, assertions);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	@Nested
	@DisplayName("startsWith")
	class StartsWith {

		@Test
		@DisplayName("should match a decomposed value with a precomposed prefix on the prefetch path")
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldMatchPrecomposedPrefixAgainstDecomposedValue() {
			runWithCatalog(
				"unicodeStringSearch-startsWith",
				session -> {
					final FilterConstraint filter = attributeStartsWith(ATTR_NAME, NFC_CAFE);
					// prefetch path must agree with the (already-correct) index path
					assertEquals(setOf(PK), matchedPrimaryKeysViaPrefetch(session, filter));
					// interchangeability: both paths return the same set
					assertEquals(
						matchedPrimaryKeysViaIndex(session, filter),
						matchedPrimaryKeysViaPrefetch(session, filter)
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("endsWith")
	class EndsWith {

		@Test
		@DisplayName("should match a decomposed value with a precomposed suffix on the prefetch path")
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldMatchPrecomposedSuffixAgainstDecomposedValue() {
			runWithCatalog(
				"unicodeStringSearch-endsWith",
				session -> {
					final FilterConstraint filter = attributeEndsWith(ATTR_NAME, NFC_E_ACUTE);
					assertEquals(setOf(PK), matchedPrimaryKeysViaPrefetch(session, filter));
					assertEquals(
						matchedPrimaryKeysViaIndex(session, filter),
						matchedPrimaryKeysViaPrefetch(session, filter)
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("contains")
	class Contains {

		@Test
		@DisplayName("should match a decomposed value with a precomposed substring on the prefetch path")
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldMatchPrecomposedSubstringAgainstDecomposedValue() {
			runWithCatalog(
				"unicodeStringSearch-contains",
				session -> {
					final FilterConstraint filter = attributeContains(ATTR_NAME, NFC_F_E_ACUTE);
					assertEquals(setOf(PK), matchedPrimaryKeysViaPrefetch(session, filter));
					assertEquals(
						matchedPrimaryKeysViaIndex(session, filter),
						matchedPrimaryKeysViaPrefetch(session, filter)
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("localized (collation comparator)")
	class Localized {

		@Test
		@DisplayName("should match a decomposed value with a precomposed prefix under a collation comparator")
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldMatchPrecomposedPrefixAgainstDecomposedValueWhenLocalized() {
			runWithLocalizedCatalog(
				"unicodeStringSearch-localized-startsWith",
				session -> {
					// a localized attribute installs a LocalizedStringComparator, so the index path takes the
					// full-scan collation branch of getRecordsWhoseValuesStartWith (no contiguous-run early break)
					final FilterConstraint filter = attributeStartsWith(ATTR_NAME, NFC_CAFE);
					// the NFC prefix must still match the stored NFD value through the collation branch
					assertEquals(setOf(PK), matchedPrimaryKeysViaLocalizedIndex(session, filter));
					// interchangeability: the collation index branch and the prefetch predicate agree
					assertEquals(
						matchedPrimaryKeysViaLocalizedIndex(session, filter),
						matchedPrimaryKeysViaLocalizedPrefetch(session, filter)
					);
				}
			);
		}

		@Test
		@DisplayName("should match a decomposed value with a precomposed substring under a collation comparator")
		@Tag(ENGINE)
		@Tag(QUERY)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldMatchPrecomposedSubstringAgainstDecomposedValueWhenLocalized() {
			runWithLocalizedCatalog(
				"unicodeStringSearch-localized-contains",
				session -> {
					final FilterConstraint filter = attributeContains(ATTR_NAME, NFC_F_E_ACUTE);
					assertEquals(setOf(PK), matchedPrimaryKeysViaLocalizedIndex(session, filter));
					assertEquals(
						matchedPrimaryKeysViaLocalizedIndex(session, filter),
						matchedPrimaryKeysViaLocalizedPrefetch(session, filter)
					);
				}
			);
		}
	}

}
