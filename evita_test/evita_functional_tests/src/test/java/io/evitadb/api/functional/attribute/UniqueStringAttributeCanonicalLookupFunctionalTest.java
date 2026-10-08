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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins that an equality lookup finds an entity by a unique `String` attribute whatever Unicode spelling the value was
 * written in and whatever spelling the lookup uses, as long as the two are canonically equivalent.
 *
 * The query translators normalize the probe value with the filter normalizer, which re-composes a `String` to Unicode
 * NFD. A non-localized `unique` attribute keeps its values in the shared filter tree, which stores the same NFD form,
 * so the two always meet. A `uniqueGlobally` / `uniqueGloballyWithinLocale` catalog attribute lives in the catalog's
 * standalone unique tree, and a localized attribute unique across locales in the collection's standalone owner tree.
 * The lookup value must be normalized the same way as the stored values there too, or a match is impossible.
 *
 * ## The fixture
 *
 * Every collection holds three entities: entity 1 written with a precomposed (NFC) value, entity 2 with a decomposed
 * (NFD) value and entity 3 with a plain ASCII value.
 *
 * | collection | attribute | declared as |
 * |---|---|---|
 * | `globalUrl` | `globalUrl` | catalog, `uniqueGlobally` |
 * | `globalLocalUrl` | `globalLocalUrl` | catalog, localized, `uniqueGloballyWithinLocale` |
 * | `ownerUrl` | `localizedUrl` | entity, localized, `unique` |
 * | `ownerUrl` | `foldedUrl` | entity, `unique` |
 *
 * The last row is the control: its values live in the shared filter tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Equality lookup of a unique String attribute in either Unicode spelling")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class UniqueStringAttributeCanonicalLookupFunctionalTest {
	private static final String CANONICAL_LOOKUP = "uniqueStringCanonicalLookup";
	private static final String GLOBAL_URL_ENTITY = "globalUrl";
	private static final String GLOBAL_LOCAL_URL_ENTITY = "globalLocalUrl";
	private static final String OWNER_ENTITY = "ownerUrl";
	private static final String GLOBAL_URL = "globalUrl";
	private static final String GLOBAL_LOCAL_URL = "globalLocalUrl";
	private static final String LOCALIZED_URL = "localizedUrl";
	private static final String FOLDED_URL = "foldedUrl";
	/**
	 * "/čaj" with U+010D LATIN SMALL LETTER C WITH CARON - the spelling a keyboard produces.
	 */
	private static final String TEA_NFC = "/čaj";
	/**
	 * "/čaj" with `c` followed by U+030C COMBINING CARON.
	 */
	private static final String TEA_NFD = "/čaj";
	/**
	 * "/řeka" with U+0159 LATIN SMALL LETTER R WITH CARON.
	 */
	private static final String RIVER_NFC = "/řeka";
	/**
	 * "/řeka" with `r` followed by U+030C COMBINING CARON.
	 */
	private static final String RIVER_NFD = "/řeka";
	private static final String ASCII_VALUE = "/tea";
	private static final int WRITTEN_NFC = 1;
	private static final int WRITTEN_NFD = 2;
	private static final int WRITTEN_ASCII = 3;

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = CANONICAL_LOOKUP, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		// the spellings must differ as strings and agree once normalized, or every assertion below proves nothing
		assertNotEquals(TEA_NFC, TEA_NFD);
		assertEquals(TEA_NFC, Normalizer.normalize(TEA_NFD, Normalizer.Form.NFC));
		assertEquals(TEA_NFD, Normalizer.normalize(TEA_NFC, Normalizer.Form.NFD));
		assertNotEquals(RIVER_NFC, RIVER_NFD);
		assertEquals(RIVER_NFD, Normalizer.normalize(RIVER_NFC, Normalizer.Form.NFD));

		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(GLOBAL_URL, String.class, thatIs -> thatIs.uniqueGlobally())
			.withAttribute(GLOBAL_LOCAL_URL, String.class, thatIs -> thatIs.localized().uniqueGloballyWithinLocale())
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(GLOBAL_URL_ENTITY)
					.withoutGeneratedPrimaryKey().withGlobalAttribute(GLOBAL_URL).updateVia(session);
				session.defineEntitySchema(GLOBAL_LOCAL_URL_ENTITY)
					.withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH).withGlobalAttribute(GLOBAL_LOCAL_URL)
					.updateVia(session);
				session.defineEntitySchema(OWNER_ENTITY)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(LOCALIZED_URL, String.class, thatIs -> thatIs.localized().unique().nullable())
					.withAttribute(FOLDED_URL, String.class, thatIs -> thatIs.unique().nullable())
					.updateVia(session);

				final String[] written = {TEA_NFC, RIVER_NFD, ASCII_VALUE};
				for (int pk = 1; pk <= written.length; pk++) {
					final String value = written[pk - 1];
					session.createNewEntity(GLOBAL_URL_ENTITY, pk).setAttribute(GLOBAL_URL, value).upsertVia(session);
					session.createNewEntity(GLOBAL_LOCAL_URL_ENTITY, pk)
						.setAttribute(GLOBAL_LOCAL_URL, Locale.ENGLISH, value).upsertVia(session);
					session.createNewEntity(OWNER_ENTITY, pk)
						.setAttribute(LOCALIZED_URL, Locale.ENGLISH, value)
						.setAttribute(FOLDED_URL, value)
						.upsertVia(session);
				}
			}
		);
	}

	/**
	 * Asserts that the query, run within `collection` (or across the catalog when `null`) and optionally in `locale`,
	 * returns exactly the entity `expectedPk` of `expectedType`.
	 */
	private static void assertFinds(
		@Nonnull EvitaSessionContract session,
		@Nullable String collection,
		@Nullable Locale locale,
		@Nonnull FilterConstraint filter,
		@Nonnull String expectedType,
		int expectedPk,
		@Nonnull String route
	) {
		final FilterConstraint constraint = locale == null ? filter : and(filter, entityLocaleEquals(locale));
		final List<String> found = session.queryListOfEntityReferences(
			collection == null
				? Query.query(filterBy(constraint))
				: Query.query(collection(collection), filterBy(constraint))
		).stream().map(UniqueStringAttributeCanonicalLookupFunctionalTest::describe).toList();
		assertEquals(
			List.of(expectedType + ":" + expectedPk), found,
			route + " lookup by " + describeValue(filter) + " missed entity " + expectedPk + "!"
		);
	}

	/**
	 * Renders a reference as `type:pk` so a mismatch reads directly in the assertion message.
	 */
	@Nonnull
	private static String describe(@Nonnull EntityReferenceContract reference) {
		return reference.getType() + ":" + reference.getPrimaryKey();
	}

	/**
	 * Renders the constraint with its non-ASCII characters escaped, so the spelling it probed with is visible.
	 */
	@Nonnull
	private static String describeValue(@Nonnull FilterConstraint filter) {
		final StringBuilder sb = new StringBuilder();
		for (char c : filter.toString().toCharArray()) {
			if (c < 128) {
				sb.append(c);
			} else {
				sb.append(String.format("\\u%04X", (int) c));
			}
		}
		return sb.toString();
	}

	/**
	 * Runs `attributeEquals` and `attributeInSet` for `probe` within the collection and, where `catalogWide` is set,
	 * across the whole catalog as well, expecting entity `expectedPk`. Every lookup is reported on its own, so one
	 * failing route does not hide the outcome of the others.
	 */
	private static void assertEqualityLookupsFind(
		@Nonnull Evita evita,
		@Nonnull String collection,
		@Nullable Locale locale,
		@Nonnull String attributeName,
		@Nonnull String probe,
		int expectedPk,
		boolean catalogWide
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<Executable> lookups = new ArrayList<>(4);
				lookups.add(() -> assertFinds(
					session, collection, locale, attributeEquals(attributeName, probe), collection, expectedPk,
					"collection"
				));
				lookups.add(() -> assertFinds(
					session, collection, locale, attributeInSet(attributeName, probe), collection, expectedPk,
					"collection"
				));
				if (catalogWide) {
					lookups.add(() -> assertFinds(
						session, null, locale, attributeEquals(attributeName, probe), collection, expectedPk,
						"catalog-wide"
					));
					lookups.add(() -> assertFinds(
						session, null, locale, attributeInSet(attributeName, probe), collection, expectedPk,
						"catalog-wide"
					));
				}
				assertAll(lookups);
				return null;
			}
		);
	}

	/**
	 * Catalog attribute held in the catalog's standalone unique tree.
	 */
	@DisplayName("Globally unique attribute")
	@Nested
	class GloballyUniqueAttribute {

		@DisplayName("Should find a value written precomposed by a precomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindPrecomposedValueByPrecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, GLOBAL_URL_ENTITY, null, GLOBAL_URL, TEA_NFC, WRITTEN_NFC, true);
		}

		@DisplayName("Should find a value written precomposed by a decomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindPrecomposedValueByDecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, GLOBAL_URL_ENTITY, null, GLOBAL_URL, TEA_NFD, WRITTEN_NFC, true);
		}

		@DisplayName("Should find a value written decomposed by a precomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindDecomposedValueByPrecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, GLOBAL_URL_ENTITY, null, GLOBAL_URL, RIVER_NFC, WRITTEN_NFD, true);
		}

		@DisplayName("Should find a value written decomposed by a decomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindDecomposedValueByDecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, GLOBAL_URL_ENTITY, null, GLOBAL_URL, RIVER_NFD, WRITTEN_NFD, true);
		}

		@DisplayName("Should find an ASCII value")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindAsciiValue(Evita evita) {
			assertEqualityLookupsFind(evita, GLOBAL_URL_ENTITY, null, GLOBAL_URL, ASCII_VALUE, WRITTEN_ASCII, true);
		}
	}

	/**
	 * Localized catalog attribute held in the catalog's standalone unique tree, one per locale.
	 */
	@DisplayName("Globally unique attribute within locale")
	@Nested
	class GloballyUniqueWithinLocaleAttribute {

		@DisplayName("Should find a value written precomposed by a precomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindPrecomposedValueByPrecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_LOCAL_URL_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_URL, TEA_NFC, WRITTEN_NFC, true
			);
		}

		@DisplayName("Should find a value written precomposed by a decomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindPrecomposedValueByDecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_LOCAL_URL_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_URL, TEA_NFD, WRITTEN_NFC, true
			);
		}

		@DisplayName("Should find a value written decomposed by a precomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindDecomposedValueByPrecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_LOCAL_URL_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_URL, RIVER_NFC, WRITTEN_NFD, true
			);
		}

		@DisplayName("Should find an ASCII value")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindAsciiValue(Evita evita) {
			assertEqualityLookupsFind(
				evita, GLOBAL_LOCAL_URL_ENTITY, Locale.ENGLISH, GLOBAL_LOCAL_URL, ASCII_VALUE, WRITTEN_ASCII, true
			);
		}
	}

	/**
	 * Localized entity attribute unique across locales, held in the collection's standalone owner tree.
	 */
	@DisplayName("Localized attribute unique within the collection")
	@Nested
	class LocalizedCollectionUniqueAttribute {

		@DisplayName("Should find a value written precomposed by a precomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindPrecomposedValueByPrecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_URL, TEA_NFC, WRITTEN_NFC, false);
		}

		@DisplayName("Should find a value written precomposed by a decomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindPrecomposedValueByDecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_URL, TEA_NFD, WRITTEN_NFC, false);
		}

		@DisplayName("Should find a value written decomposed by a precomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindDecomposedValueByPrecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_URL, RIVER_NFC, WRITTEN_NFD, false
			);
		}

		@DisplayName("Should find an ASCII value")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindAsciiValue(Evita evita) {
			assertEqualityLookupsFind(
				evita, OWNER_ENTITY, Locale.ENGLISH, LOCALIZED_URL, ASCII_VALUE, WRITTEN_ASCII, false
			);
		}
	}

	/**
	 * Non-localized entity attribute, its values held in the shared filter tree.
	 */
	@DisplayName("Non-localized attribute unique within the collection")
	@Nested
	class FoldedCollectionUniqueAttribute {

		@DisplayName("Should find a value written precomposed by a precomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindPrecomposedValueByPrecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, OWNER_ENTITY, null, FOLDED_URL, TEA_NFC, WRITTEN_NFC, false);
		}

		@DisplayName("Should find a value written precomposed by a decomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindPrecomposedValueByDecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, OWNER_ENTITY, null, FOLDED_URL, TEA_NFD, WRITTEN_NFC, false);
		}

		@DisplayName("Should find a value written decomposed by a precomposed lookup")
		@UseDataSet(CANONICAL_LOOKUP)
		@Test
		void shouldFindDecomposedValueByPrecomposedLookup(Evita evita) {
			assertEqualityLookupsFind(evita, OWNER_ENTITY, null, FOLDED_URL, RIVER_NFC, WRITTEN_NFD, false);
		}
	}

}
