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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContains;
import static io.evitadb.api.query.QueryConstraints.attributeEndsWith;
import static io.evitadb.api.query.QueryConstraints.attributeStartsWith;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * End-to-end proof that declaring {@link FilterIndexCapability#SUBSTRING} on an attribute changes only HOW FAST
 * `attributeContains` and `attributeEndsWith` are answered, never WHAT they answer.
 *
 * Every case runs the identical corpus and the identical query twice - once against a collection whose attribute
 * declares the capability (and therefore has a trigram index behind it) and once against one that does not (and is
 * therefore answered by the bucket scan that predates it) - and asserts the two agree. That comparison is what makes
 * the test a parity test rather than a restatement of whatever the accelerated path happens to produce.
 *
 * The corpus is deliberately larger than
 * {@link io.evitadb.index.trigram.TrigramSubstringSearch#MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT}: below that floor
 * the accelerated collection would decline and quietly compare the scan against itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute substring filtering — trigram index parity")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class AttributeSubstringIndexFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ATTR_NAME = "name";

	/**
	 * Filler products, sharing no trigram with any searched pattern. Their count is what carries the corpus past the
	 * floor below which the accelerated path declines outright.
	 */
	private static final int FILLER_PRODUCTS = 400;

	/**
	 * Products carrying the widely-planted `zebra` pattern.
	 */
	private static final int ZEBRA_PRODUCTS = 30;

	/**
	 * Precomposed (NFC) `café` — the form a user types, and the form this corpus stores.
	 */
	private static final String NFC_CAFE = "café";

	/**
	 * Decomposed (NFD) `café` — the form the shared value tree normalizes it to.
	 */
	private static final String NFD_CAFE = Normalizer.normalize(NFC_CAFE, Normalizer.Form.NFD);

	/**
	 * Locale of the localized variant. French maps to a real non-default `Collator`, so the localized filter index
	 * orders its buckets by collation rather than by code point - the branch under which the scan cannot early-break
	 * and the reverse lookup has to resolve through the value id directory instead of through key order.
	 */
	private static final Locale SEARCH_LOCALE = Locale.FRENCH;

	/**
	 * Builds the corpus every case searches, in one deterministic order; the product primary key is the position plus
	 * one.
	 *
	 * @return the product names
	 */
	@Nonnull
	private static List<String> corpus() {
		final List<String> names = new ArrayList<>(FILLER_PRODUCTS + ZEBRA_PRODUCTS + 8);
		for (int i = 0; i < FILLER_PRODUCTS; i++) {
			names.add(String.format("item-%04d", i));
		}
		for (int i = 0; i < ZEBRA_PRODUCTS; i++) {
			names.add(String.format("widget zebra %03d", i));
		}
		names.add("omega");
		names.add("tail omega");
		names.add("omega leads here");
		names.add("xxabcdxx");
		names.add("bcd then abc");
		names.add(NFC_CAFE + " noir");
		names.add("decaf latte");
		return names;
	}

	/**
	 * @param paths the per-test storage and export directories
	 * @return the standard test configuration with the session inactivity timeout disabled
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Installs the schema and seeds the corpus.
	 *
	 * @param session     the writable session
	 * @param accelerated whether the attribute declares the SUBSTRING capability
	 * @param localized   whether the attribute is localized, and the values written under {@link #SEARCH_LOCALE}
	 */
	private static void seed(@Nonnull EvitaSessionContract session, boolean accelerated, boolean localized) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_NAME, String.class,
				whichIs -> {
					if (localized) {
						whichIs.localized();
					}
					if (accelerated) {
						whichIs.filterable(FilterIndexCapability.SUBSTRING);
					} else {
						whichIs.filterable();
					}
				}
			)
			.updateVia(session);
		final List<String> names = corpus();
		for (int i = 0; i < names.size(); i++) {
			final var entity = session.createNewEntity(ENTITY_PRODUCT, i + 1);
			if (localized) {
				entity.setAttribute(ATTR_NAME, SEARCH_LOCALE, names.get(i));
			} else {
				entity.setAttribute(ATTR_NAME, names.get(i));
			}
			entity.upsertVia(session);
		}
	}

	/**
	 * @param session   an open read session
	 * @param filter    the filter to run
	 * @param localized whether the query must scope itself to {@link #SEARCH_LOCALE}
	 * @return the matched primary keys, sorted so the comparison is order-independent
	 */
	@Nonnull
	private static Set<Integer> matchedPrimaryKeys(
		@Nonnull EvitaSessionContract session, @Nonnull FilterConstraint filter, boolean localized
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				localized ? filterBy(entityLocaleEquals(SEARCH_LOCALE), filter) : filterBy(filter),
				require(page(1, Integer.MAX_VALUE))
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
	 * Spins up two independent catalogs over the same corpus - one with the accelerator, one without - and hands both
	 * read sessions to `comparison`, in that order.
	 *
	 * @param label      the per-test directory label
	 * @param localized  whether the attribute is localized
	 * @param comparison receives the accelerated session and the scanning session
	 */
	private void runAgainstBothCatalogs(
		@Nonnull String label,
		boolean localized,
		@Nonnull BiConsumer<EvitaSessionContract, EvitaSessionContract> comparison
	) {
		final TestPaths acceleratedPaths = createTestPaths(label + "-accelerated");
		final TestPaths scanningPaths = createTestPaths(label + "-scanning");
		try (
			Evita accelerated = new Evita(createConfiguration(acceleratedPaths));
			Evita scanning = new Evita(createConfiguration(scanningPaths))
		) {
			accelerated.defineCatalog(TEST_CATALOG);
			accelerated.updateCatalog(TEST_CATALOG, session -> {
				seed(session, true, localized);
				session.goLiveAndClose();
			});
			scanning.defineCatalog(TEST_CATALOG);
			scanning.updateCatalog(TEST_CATALOG, session -> {
				seed(session, false, localized);
				session.goLiveAndClose();
			});
			// the two lambdas are declared rather than inlined: `queryCatalog` is overloaded on Consumer and Function,
			// and a nested lambda leaves the compiler unable to tell which overload the outer one targets
			final Consumer<EvitaSessionContract> withBothSessions = acceleratedSession -> {
				final Consumer<EvitaSessionContract> withScanningSession =
					scanningSession -> comparison.accept(acceleratedSession, scanningSession);
				scanning.queryCatalog(TEST_CATALOG, withScanningSession);
			};
			accelerated.queryCatalog(TEST_CATALOG, withBothSessions);
		} finally {
			cleanupTestPaths(acceleratedPaths);
			cleanupTestPaths(scanningPaths);
		}
	}

	/**
	 * Asserts both catalogs answer `filter` identically, and that the answer is not empty - an empty answer would make
	 * the comparison hold for the wrong reason.
	 *
	 * @param accelerated the session over the capability-declaring collection
	 * @param scanning    the session over the plain collection
	 * @param filter      the filter to compare
	 * @param localized   whether the query must scope itself to {@link #SEARCH_LOCALE}
	 * @param expectedSize how many products the filter must match
	 */
	private static void assertSameAnswer(
		@Nonnull EvitaSessionContract accelerated,
		@Nonnull EvitaSessionContract scanning,
		@Nonnull FilterConstraint filter,
		boolean localized,
		int expectedSize
	) {
		final Set<Integer> scanned = matchedPrimaryKeys(scanning, filter, localized);
		assertEquals(expectedSize, scanned.size(), "the scan's own answer to " + filter + " is not what was planted");
		assertEquals(scanned, matchedPrimaryKeys(accelerated, filter, localized), "answers diverge for " + filter);
	}

	@Test
	@DisplayName("contains answers identically with and without the substring index")
	void shouldAnswerContainsIdenticallyWithAndWithoutTheIndex() {
		runAgainstBothCatalogs(
			"substringIndex-contains", false,
			(accelerated, scanning) -> {
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, "zebra"), false, ZEBRA_PRODUCTS);
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, "omega"), false, 3);
				// `bcd then abc` carries both of `abcd`'s trigrams and none of its order - only exact verification
				// keeps it out of the answer
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, "abcd"), false, 1);
				// short of a trigram, so the accelerated collection falls back to the scan
				assertSameAnswer(
					accelerated, scanning, attributeContains(ATTR_NAME, "it"), false, FILLER_PRODUCTS);
				// carried by every filler value, so the selectivity gate declines it
				assertSameAnswer(
					accelerated, scanning, attributeContains(ATTR_NAME, "item"), false, FILLER_PRODUCTS);
				// nothing carries it at all
				assertEquals(
					Set.of(), matchedPrimaryKeys(accelerated, attributeContains(ATTR_NAME, "xyzzy"), false));
			}
		);
	}

	@Test
	@DisplayName("endsWith answers identically with and without the substring index")
	void shouldAnswerEndsWithIdenticallyWithAndWithoutTheIndex() {
		runAgainstBothCatalogs(
			"substringIndex-endsWith", false,
			(accelerated, scanning) -> {
				// two of the three `omega` products end with it, which is what tells the two constraints apart
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, "omega"), false, 2);
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, "abcdxx"), false, 1);
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, "here"), false, 1);
				assertEquals(
					Set.of(), matchedPrimaryKeys(accelerated, attributeEndsWith(ATTR_NAME, "zebra"), false));
			}
		);
	}

	@Test
	@DisplayName("startsWith is untouched by the substring index")
	void shouldLeaveStartsWithUntouched() {
		// startsWith keeps its anchored walk; the declaration must not change its answer either
		runAgainstBothCatalogs(
			"substringIndex-startsWith", false,
			(accelerated, scanning) -> {
				assertSameAnswer(
					accelerated, scanning, attributeStartsWith(ATTR_NAME, "widget"), false, ZEBRA_PRODUCTS);
				assertSameAnswer(accelerated, scanning, attributeStartsWith(ATTR_NAME, "omega"), false, 2);
			}
		);
	}

	@Test
	@DisplayName("a precomposed term matches a decomposed value through the substring index")
	void shouldMatchAcrossUnicodeNormalizationForms() {
		runAgainstBothCatalogs(
			"substringIndex-unicode", false,
			(accelerated, scanning) -> {
				assertFalse(NFC_CAFE.equals(NFD_CAFE), "the two normalization forms must really differ");
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, NFC_CAFE), false, 1);
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, NFD_CAFE), false, 1);
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, NFC_CAFE + " noir"), false, 1);
			}
		);
	}

	@Test
	@DisplayName("a collation-ordered localized attribute answers identically too")
	void shouldAnswerIdenticallyUnderACollationComparator() {
		runAgainstBothCatalogs(
			"substringIndex-localized", true,
			(accelerated, scanning) -> {
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, "zebra"), true, ZEBRA_PRODUCTS);
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, "omega"), true, 2);
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, NFC_CAFE), true, 1);
			}
		);
	}

}
