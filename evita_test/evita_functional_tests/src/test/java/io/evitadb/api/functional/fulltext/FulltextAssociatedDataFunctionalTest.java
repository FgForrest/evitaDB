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

package io.evitadb.api.functional.fulltext;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies that `textMatches` finds text living in searchable associated data - a `String` body stored as HTML, a
 * `String[]` and the text a complex data object's expression selects - by default, next to searchable attributes, on
 * both the index-scan and the prefetch plan.
 *
 * The fixture (all English): product 1 `Garden chair` has an HTML body `Comfortable teak furniture` wrapped in markup
 * carrying a `data-` JSON payload; 2 `Kitchen table` has keywords `oak` and `rustic`; 3 `Office lamp` has an article
 * whose blocks read `bright LED` and `adjustable arm` under the title `Brilliant`; 4 `Sofa` has `teak` only in a memo
 * that is not searchable and in the title of its article, which the expression does not select; archived 5 `Bench`
 * has the body `teak bench`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext query over searchable associated data")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(FILTER)
@Tag(FULLTEXT)
public class FulltextAssociatedDataFunctionalTest {
	private static final String DATASET = "fulltextAssociatedData";
	private static final String PRODUCT = "product";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String BODY = "body";
	private static final String KEYWORDS = "keywords";
	private static final String ARTICLE = "article";
	private static final String MEMO = "memo";
	private static final int[] LIVE_PRODUCTS = {1, 2, 3, 4};
	private static final int[] ALL_PRODUCTS = {1, 2, 3, 4, 5};

	/**
	 * Builds an article: a title and blocks of text.
	 *
	 * @param title  the title
	 * @param blocks the texts of the blocks
	 * @return the article as a complex data object
	 */
	@Nonnull
	private static ComplexDataObject article(@Nonnull String title, @Nonnull String... blocks) {
		final DataItem[] blockItems = new DataItem[blocks.length];
		for (int i = 0; i < blocks.length; i++) {
			final Map<String, DataItem> block = CollectionUtils.createLinkedHashMap(1);
			block.put("text", new DataItemValue(blocks[i]));
			blockItems[i] = new DataItemMap(block);
		}
		final Map<String, DataItem> root = CollectionUtils.createLinkedHashMap(2);
		root.put("title", new DataItemValue(title));
		root.put("blocks", new DataItemArray(blockItems));
		return new ComplexDataObject(new DataItemMap(root));
	}

	/**
	 * Creates the fixture - see the class documentation.
	 *
	 * @param evita the evita instance
	 */
	@DataSet(value = DATASET, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().searchable())
					.withAssociatedData(
						BODY, String.class,
						whichIs -> whichIs.localized().nullable().searchableInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.withAssociatedData(
						KEYWORDS, String[].class, whichIs -> whichIs.localized().nullable().searchable()
					)
					.withAssociatedData(
						ARTICLE, ComplexDataObject.class,
						whichIs -> whichIs.localized().nullable()
							.searchable(ExpressionFactory.parse("$value.blocks.*[$.text]"))
					)
					.withAssociatedData(MEMO, String.class, whichIs -> whichIs.localized().nullable())
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Garden chair")
						.setAssociatedData(
							BODY, Locale.ENGLISH,
							"<p class=\"intro\" data-value='{\"value\": false}'>Comfortable <b>teak</b> furniture</p>"
						)
				);
				session.upsertEntity(
					session.createNewEntity(PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Kitchen table")
						.setAssociatedData(KEYWORDS, Locale.ENGLISH, new String[]{"oak", "rustic"})
				);
				session.upsertEntity(
					session.createNewEntity(PRODUCT, 3)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Office lamp")
						.setAssociatedData(
							ARTICLE, Locale.ENGLISH, article("Brilliant", "bright LED", "adjustable arm")
						)
				);
				session.upsertEntity(
					session.createNewEntity(PRODUCT, 4)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Sofa")
						.setAssociatedData(MEMO, Locale.ENGLISH, "teak secret")
						.setAssociatedData(ARTICLE, Locale.ENGLISH, article("teak", "leather"))
				);
				session.upsertEntity(
					session.createNewEntity(PRODUCT, 5)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Bench")
						.setAssociatedData(BODY, Locale.ENGLISH, "teak bench")
				);
				session.archiveEntity(PRODUCT, 5);
			}
		);
	}

	@DisplayName("Should find text living only in a String, a String[] or the selected text of a complex data object")
	@UseDataSet(DATASET)
	@Test
	void shouldFindTextInSearchableAssociatedData(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertMatches(session, LIVE_PRODUCTS, new int[]{1}, textMatches("teak"));
				assertMatches(session, LIVE_PRODUCTS, new int[]{1}, textMatches("teak furniture"));
				assertMatches(session, LIVE_PRODUCTS, new int[]{2}, textMatches("rustic"));
				assertMatches(session, LIVE_PRODUCTS, new int[]{3}, textMatches("adjustable"));
				// an attribute and associated data of one entity are searched together
				assertMatches(session, LIVE_PRODUCTS, new int[]{3}, textMatches("lamp arm"));
				return null;
			}
		);
	}

	@DisplayName("Should not find markup, a non-searchable item or text the expression does not select")
	@UseDataSet(DATASET)
	@Test
	void shouldNotFindTextOutsideTheSearchableText(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertMatches(session, LIVE_PRODUCTS, new int[0], textMatches("intro"));
				assertMatches(session, LIVE_PRODUCTS, new int[0], textMatches("class"));
				assertMatches(session, LIVE_PRODUCTS, new int[0], textMatches("false"));
				assertMatches(session, LIVE_PRODUCTS, new int[0], textMatches("secret"));
				assertMatches(session, LIVE_PRODUCTS, new int[0], textMatches("brilliant"));
				return null;
			}
		);
	}

	@DisplayName("Should find associated data searchable in the archive in the archived scope")
	@UseDataSet(DATASET)
	@Test
	void shouldFindArchivedAssociatedData(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertMatches(session, ALL_PRODUCTS, new int[]{5}, textMatches("teak"), scope(Scope.ARCHIVED));
				assertMatches(
					session, ALL_PRODUCTS, new int[]{1, 5}, textMatches("teak"), scope(Scope.LIVE, Scope.ARCHIVED)
				);
				return null;
			}
		);
	}

	/**
	 * Asserts the English query with the filter, ordered by relevance, matches exactly the expected products on both
	 * plans.
	 *
	 * @param session    the session
	 * @param candidates every product that may match, for the prefetch plan
	 * @param expected   the expected primary keys, ascending
	 * @param filter     the filter constraints besides the locale
	 */
	private static void assertMatches(
		@Nonnull EvitaSessionContract session,
		@Nonnull int[] candidates,
		@Nonnull int[] expected,
		@Nonnull FilterConstraint... filter
	) {
		final FilterConstraint[] localized = new FilterConstraint[filter.length + 1];
		localized[0] = entityLocaleEquals(Locale.ENGLISH);
		System.arraycopy(filter, 0, localized, 1, filter.length);
		for (PlanPreference plan : PlanPreference.values()) {
			final EvitaResponse<EntityReference> response = session.query(
				query(
					collection(PRODUCT),
					filterBy(plan.filter(candidates, localized)),
					orderBy(relevance()),
					require(page(1, 100), plan.debug(), queryTelemetry())
				),
				EntityReference.class
			);
			final int[] answered = response.getRecordData()
				.stream()
				.mapToInt(EntityReference::getPrimaryKey)
				.sorted()
				.toArray();
			assertArrayEquals(
				expected, answered,
				() -> "Match set on the " + plan + " plan: expected " + Arrays.toString(expected) + " but was " +
					Arrays.toString(answered)
			);
		}
	}

}
