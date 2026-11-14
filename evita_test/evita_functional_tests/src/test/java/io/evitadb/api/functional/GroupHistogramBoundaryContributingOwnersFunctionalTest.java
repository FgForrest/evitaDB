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

package io.evitadb.api.functional;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.functional.GroupHistogramTestSupport.addReference;
import static io.evitadb.api.functional.GroupHistogramTestSupport.anchorShares;
import static io.evitadb.api.functional.GroupHistogramTestSupport.anchoredRange;
import static io.evitadb.api.functional.GroupHistogramTestSupport.createAnchor;
import static io.evitadb.api.functional.GroupHistogramTestSupport.defineProduct;
import static io.evitadb.api.functional.GroupHistogramTestSupport.describeBoundary;
import static io.evitadb.api.functional.GroupHistogramTestSupport.everyReferenceAttribute;
import static io.evitadb.api.functional.GroupHistogramTestSupport.plain;
import static io.evitadb.api.functional.GroupHistogramTestSupport.restart;
import static io.evitadb.api.functional.GroupHistogramTestSupport.withBothCatalogStates;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a boundary of the histogram of a group is picked among the referenced entities an owner the histogram
 * counts at that boundary references whenever one of them holds the boundary value, and then not at one whose
 * reference carrying the boundary value belongs to an owner the query leaves out or is rejected by the histogram's
 * condition - in the shape every scenario builds, where no owner the histogram counts at the boundary references such
 * an entity through another reference of the same reduced index. The engine checks the owners of an entity, not the
 * reference carrying the value (see `ReferenceHistogramAccumulator#isReferencedByCountedOwner`), so an entity a counted
 * owner references there anchors the boundary even when the reference carrying the value is left out; no scenario
 * builds that case. When no
 * entity a counted owner references holds the boundary value - the default of the histogram's expression gives a
 * counted owner the value through a reference or a brand carrying no attribute, which no filter index holds - the
 * boundary is anchored among every brand holding the value, so a brand no counted owner references may anchor it. Two
 * scenarios build such a boundary with two brands holding the value, both referenced only by an owner the query leaves
 * out, so the order alone decides which of them anchors it; the others build it with a single brand holding the value:
 * over the share the brand of the defaulted reference or one only an owner the query leaves out references, over the
 * weight the only brand weighing the value, since the brand giving the value has no weight and so is no candidate. The
 * boundary is picked by the primary keys of the candidates before any brand is loaded. When the brand picked among
 * those a counted owner references cannot be loaded - no entity is stored for it, it lies outside the scopes the query
 * asks for, or it lacks the locale of the query - the boundary is anchored at the brand picked among every brand
 * holding the value, if that one loads, and at no other brand; when neither loads, the histogram has no boundary
 * entity at all.
 *
 * The fixture is the one of {@link GroupHistogramTestSupport}: the reference to a brand allows duplicates, is grouped,
 * and its representative attribute `rank` decides which reduced index of the group a reference sits in. The product
 * references the brand 10, the anchor product the brands 12 and 13, all in the group 1. The reference of the product
 * and the reference of the anchor product to the brand 12 carry the share 10, the one to the brand 13 the share 20;
 * likewise the brands 10 and 12 weigh 10 and the brand 13 weighs 20. Several scenarios vary one of these, as their
 * provider describes. The boundaries are checked after the last upsert and again after a restart, without an order of
 * the reference summary - the lowest primary key wins a tie - and with the descending order of primary keys - the
 * highest one wins. So a brand anchoring a boundary it must not anchor shows without an order when its primary key is
 * lower than the one of the right brand, and with the descending order when it is higher.
 *
 * - The query keeps only the anchor product (scenarios `ANCHOR_KEPT_*`): a brand the reference of the product alone
 *   carries a boundary value to must anchor nothing while a brand of the anchor product holds the value, unless the one
 *   picked among them cannot be loaded. Over the share for both orders in which the reduced indexes are created, with
 *   every owner at one rank, with the anchor product referencing the brand 12 twice, with the product tying at the
 *   largest value, with the product referencing the brand 13 of the higher primary key - also with a default the
 *   smallest value equals though no reference is given it - with the smallest value of the anchor product given by the
 *   default of the histogram's expression, over an `Integer` share and over a `BigDecimal` one, and with a `BigDecimal`
 *   default too large for the index to key, which no reference is given; over the weight of the brand, also with the
 *   smallest value - or both - given by the default for a brand without a weight; over the share and over the weight
 *   with the histogram defined in both scopes, a different default in each, the owners in the archived scope and the
 *   smallest value of the anchor product given by the default of the archived scope; over the share and over the weight
 *   with two brands only the product references holding a value the anchor product gets from the default; over the
 *   share with the anchor product referencing a brand no entity is stored for at the smallest value, once with a stored
 *   brand of the product holding the value too and once with the lowest and the highest brand holding it unstored as
 *   well, while a stored one between them holds it; over the share with the anchor product referencing at the smallest
 *   value a brand in the archived scope, which the query does not ask for; and over the weight with the brand of the
 *   anchor product at the smallest value lacking the locale of the query.
 * - The histogram admits only the references above the first rank (scenarios `CONDITION_*`): a brand whose reference
 *   carrying a boundary value the condition rejects must anchor nothing while a brand whose reference the condition
 *   admits holds the value. Once with every reference of the anchor product rejected, once with its reference to the
 *   brand 13, carrying another share, admitted, and twice with the reference of the product to the brand 10, of the
 *   lower primary key, rejected instead - at the smallest and at the largest value.
 * - The controls keep every owner (scenarios `CONTROL_*`): over the weight of the brand the brands 10 and 12 both
 *   contribute the smallest value, and the order alone decides which of them anchors it; over the share and over the
 *   weight in both scopes the histogram of the archived scope is anchored as it is when the query keeps only the
 *   anchor product.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The boundaries of a group histogram preferring the referenced entities of counted owners")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(HISTOGRAM)
class GroupHistogramBoundaryContributingOwnersFunctionalTest implements EvitaTestSupport, GroupHistogramTestSupport {

	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String HISTOGRAM_WEIGHT = "weightHistogram";
	/**
	 * The decimal places a `BigDecimal` share is indexed with.
	 */
	private static final int SHARE_DECIMAL_PLACES = 2;
	/**
	 * A default of the `BigDecimal` share beyond the range the index keys a decimal in: written with
	 * {@link #SHARE_DECIMAL_PLACES} decimal places it is 30000000.00, which scales to 3000000000 - more than an `int`
	 * holds.
	 */
	private static final int DEFAULT_BEYOND_INDEXED_RANGE = 30_000_000;
	/**
	 * The default of the histogram's expression in the archived scope of the scenarios spanning both scopes, which
	 * differs from the default {@link #ANCHOR_VALUE} of the live scope.
	 */
	private static final int ARCHIVED_VALUE_DEFAULT = 20;
	/**
	 * The largest value of the histogram of the scenarios spanning both scopes.
	 */
	private static final int ARCHIVED_LARGEST_VALUE = 30;
	/**
	 * The primary key of a brand no entity is stored under, so a reference to it points at an entity the query cannot
	 * load.
	 */
	private static final int UNSTORED_BRAND_PK = 11;
	/**
	 * The primary key of a brand no entity is stored under, lower than {@link #UNSTORED_BRAND_PK}.
	 */
	private static final int UNSTORED_LOWER_BRAND_PK = 9;
	/**
	 * The primary key of a brand no entity is stored under, higher than {@link #UNSTORED_BRAND_PK}.
	 */
	private static final int UNSTORED_HIGHER_BRAND_PK = 14;
	/**
	 * The localized attribute of the brand and the product in the scenarios with a locale of the query.
	 */
	private static final String ATTRIBUTE_NAME = "name";
	/**
	 * The locale the query asks for in the scenarios with a locale of the query.
	 */
	private static final Locale QUERY_LOCALE = Locale.ENGLISH;
	/**
	 * A locale the query does not ask for, the only one a brand lacking the locale of the query is named in.
	 */
	private static final Locale OTHER_LOCALE = Locale.forLanguageTag("cs");
	/**
	 * The histogram condition admitting only the references above the first rank.
	 */
	private static final String CONDITION_ABOVE_FIRST_RANK =
		"($reference.attributes['" + ATTRIBUTE_RANK + "'] ?? 0) > " + FIRST_RANK;

	/**
	 * A catalog set up in warm-up, a sequence of upserts applied to it in the catalog state under test, the query and
	 * the histogram condition, and the boundaries the histogram of the group must have afterwards with either order of
	 * the reference summary.
	 *
	 * @param name                    the name of the scenario
	 * @param source                  the source of the values of the histogram of the scenario
	 * @param keptOwnerPk             the primary key of the only owner the query keeps, or `null` to keep all
	 * @param histogramCondition      the condition a reference must satisfy to give the histogram its value, or `null`
	 *                                for none
	 * @param valueDefault            the value a reference without the source attribute gives the histogram, or `null`
	 *                                when such a reference gives it none - in the live scope when
	 *                                `archivedValueDefault` is set
	 * @param archivedValueDefault    the value a reference without the source attribute gives the histogram in the
	 *                                archived scope, or `null` for a scenario confined to the live scope; with it, the
	 *                                reference, the weight of the brand and the histogram are defined in both scopes,
	 *                                and the query asks for both
	 * @param shareDecimalPlaces      the decimal places the `share` attribute is indexed with: `0` for an `Integer`
	 *                                share, more for a `BigDecimal` one, whose default is then written with as many
	 *                                decimal places
	 * @param setUp                   the upserts storing the owners in warm-up, in the order they are applied
	 * @param steps                   the upserts writing the values of the histogram, in the order they are applied
	 * @param expectedWithoutOrder    the expected description of the histogram of the group when the reference summary
	 *                                has no order, see {@link #describeHistogram}
	 * @param expectedDescendingByKey the expected description of the histogram of the group when the reference summary
	 *                                orders the brands by their primary keys in descending order
	 * @param queryLocale             the locale the query asks for, or `null` for none; with it the brand and the
	 *                                product carry the localized attribute `name`, and the query keeps only the owners
	 *                                named in the locale and loads only the brands named in it
	 */
	private record Scenario(
		@Nonnull String name,
		@Nonnull HistogramValueSource source,
		@Nullable Integer keptOwnerPk,
		@Nullable String histogramCondition,
		@Nullable Integer valueDefault,
		@Nullable Integer archivedValueDefault,
		int shareDecimalPlaces,
		@Nonnull List<Consumer<EvitaSessionContract>> setUp,
		@Nonnull List<Consumer<EvitaSessionContract>> steps,
		@Nonnull String expectedWithoutOrder,
		@Nonnull String expectedDescendingByKey,
		@Nullable Locale queryLocale
	) {

		/**
		 * Creates a scenario whose query asks for no locale.
		 *
		 * @param name                    the name of the scenario
		 * @param source                  the source of the values of the histogram of the scenario
		 * @param keptOwnerPk             the primary key of the only owner the query keeps, or `null` to keep all
		 * @param histogramCondition      the condition a reference must satisfy to give the histogram its value, or
		 *                                `null` for none
		 * @param valueDefault            the value a reference without the source attribute gives the histogram, or
		 *                                `null` when such a reference gives it none
		 * @param archivedValueDefault    the value a reference without the source attribute gives the histogram in the
		 *                                archived scope, or `null` for a scenario confined to the live scope
		 * @param shareDecimalPlaces      the decimal places the `share` attribute is indexed with
		 * @param setUp                   the upserts storing the owners in warm-up, in the order they are applied
		 * @param steps                   the upserts writing the values of the histogram, in the order they are applied
		 * @param expectedWithoutOrder    the expected description of the histogram of the group without an order
		 * @param expectedDescendingByKey the expected description of the histogram of the group with the descending
		 *                                order of primary keys
		 */
		Scenario(
			@Nonnull String name,
			@Nonnull HistogramValueSource source,
			@Nullable Integer keptOwnerPk,
			@Nullable String histogramCondition,
			@Nullable Integer valueDefault,
			@Nullable Integer archivedValueDefault,
			int shareDecimalPlaces,
			@Nonnull List<Consumer<EvitaSessionContract>> setUp,
			@Nonnull List<Consumer<EvitaSessionContract>> steps,
			@Nonnull String expectedWithoutOrder,
			@Nonnull String expectedDescendingByKey
		) {
			this(
				name, source, keptOwnerPk, histogramCondition, valueDefault, archivedValueDefault, shareDecimalPlaces,
				setUp, steps, expectedWithoutOrder, expectedDescendingByKey, null
			);
		}

		/**
		 * Creates a scenario whose references without the source attribute give the histogram no value, whose share is
		 * an `Integer`, and whose histogram is described alike with either order of the reference summary.
		 *
		 * @param name               the name of the scenario
		 * @param source             the source of the values of the histogram of the scenario
		 * @param keptOwnerPk        the primary key of the only owner the query keeps, or `null` to keep all
		 * @param histogramCondition the condition a reference must satisfy to give the histogram its value, or `null`
		 *                           for none
		 * @param setUp              the upserts storing the owners in warm-up, in the order they are applied
		 * @param steps              the upserts writing the values of the histogram, in the order they are applied
		 * @param expected           the expected description of the histogram of the group with either order
		 */
		Scenario(
			@Nonnull String name,
			@Nonnull HistogramValueSource source,
			@Nullable Integer keptOwnerPk,
			@Nullable String histogramCondition,
			@Nonnull List<Consumer<EvitaSessionContract>> setUp,
			@Nonnull List<Consumer<EvitaSessionContract>> steps,
			@Nonnull String expected
		) {
			this(name, source, keptOwnerPk, histogramCondition, null, null, 0, setUp, steps, expected, expected);
		}

		/**
		 * Returns the scopes the scenario defines its reference and histogram in and queries: the live scope, and the
		 * archived one too when the scenario has a default for it.
		 *
		 * @return the scopes of the scenario
		 */
		@Nonnull
		Scope[] scopes() {
			return this.archivedValueDefault == null ?
				new Scope[]{Scope.LIVE} : new Scope[]{Scope.LIVE, Scope.ARCHIVED};
		}

		/**
		 * Returns the value a reference without the source attribute gives the histogram in `scope`.
		 *
		 * @param scope the scope
		 * @return the default of the histogram's expression in the scope, or `null` when it has none
		 */
		@Nullable
		Integer valueDefaultIn(@Nonnull Scope scope) {
			return scope == Scope.ARCHIVED ? this.archivedValueDefault : this.valueDefault;
		}

		/**
		 * Returns the name of the scenario, which the parameterized test shows as the name of its case.
		 *
		 * @return the name of the scenario
		 */
		@Nonnull
		@Override
		public String toString() {
			return this.name;
		}
	}

	/**
	 * The scenarios of a query keeping only the anchor product, see
	 * {@link #shouldAnchorBoundariesAtContributingReferencesWhenQueryNarrowsOwners}.
	 *
	 * Except in the controls, the histogram counts only the values of the anchor product - in the scenarios confined to
	 * the live scope the smallest one 10 and the largest one 20, unless the scenario below says otherwise. The
	 * reference of the product carries a boundary value too, but the brand it references anchors no boundary unless a
	 * reference of the anchor product to it carries that value, no brand the anchor product references holds it, or
	 * the brand picked among those the anchor product references holding it cannot be loaded.
	 *
	 * - Over the share, the product creates its reduced index first or after the anchor product created its own, or
	 *   every owner sits at the first rank.
	 * - Over the share, the anchor product references the brand 12 twice: at the first rank with the share 20 and at
	 *   the second rank - the rank of the product - with the share 10, so the tie sits in one reduced index and both
	 *   boundaries are anchored at the brand 12.
	 * - Over the weight of the brand, the anchor product sits at the first rank and the product, referencing the brand
	 *   10 of the same weight as the brand 12, at the second.
	 * - Over the share with the default 10 for a reference without one, the reference of the anchor product to the
	 *   brand 12 carries no share and gives the histogram its smallest value by the default. The product references the
	 *   brand 12 at the second rank with the share 10, so the only reference a reduced index holds at the value 10
	 *   belongs to an owner the query leaves out, yet the brand 12 anchors the smallest value: no reduced index holds
	 *   the value at a brand the anchor product references there, so the bound is anchored among every brand holding
	 *   it, and the brand 12 is the only one. The same once with a `BigDecimal` share indexed with two decimal places
	 *   and the default 10.00.
	 * - Over a `BigDecimal` share whose default 30000000.00 lies beyond the range the index keys a decimal in, every
	 *   reference carries a share - those of the anchor product to the brands 12 and 13 at the first rank 10.00 and
	 *   20.00, the one of the product to the brand 13 at the second rank 10.00 - so no reference is given the default
	 *   and a query is not affected by it: the brand 12 of the anchor product holds the smallest value, and the brand
	 *   13, tying with it there through the product only, anchors nothing.
	 * - Over the weight of the brand with the default 10 for a brand without one, the brand 12 has no weight, so the
	 *   anchor product is counted at the smallest value 10 through the default; the product references the brand 10,
	 *   weighing 10, at the second rank. No brand the anchor product references weighs 10, so the bound is anchored
	 *   among every brand weighing 10, and the brand 10 - the only one, though referenced only by the product the query
	 *   leaves out - anchors it, while the brand 13 anchors the largest value 20. Once more with the anchor product
	 *   referencing only the brand 12, so both boundaries are the default and the brand 10 anchors both. The brand 12,
	 *   the one giving the value, anchors nothing: an entity without the weight is no candidate.
	 * - Over the share and over the weight with the histogram defined in both scopes - the default 10 in the live scope
	 *   and 20 in the archived one - the owners and the brands archived and the query asking for both scopes, the
	 *   anchor product gets its smallest value 20 from the default of the archived scope, and the brand 10, referenced
	 *   only by the product the query leaves out, anchors it (see {@link #archivedScopeScenario}).
	 * - Over the share, the reference of the product to the brand 10 carries the share 20 instead, so the brand 10 ties
	 *   with the brand 13 at the largest value.
	 * - Over the share, the product references the brand 13 at the second rank with the share 10, so the brand 13 ties
	 *   with the brand 12 at the smallest value; the anchor product references the brand 13 as well, but in the reduced
	 *   index of the first rank and with the share 20. Once more with the default 10 for a reference without a share,
	 *   which no reference is given: the smallest value equals the default, and the brand 12, which the anchor product
	 *   references at that value, still anchors it in both orders.
	 * - Over the share with the default 10, the reference of the anchor product to the brand 12 carries no share and
	 *   its reference to the brand 13 the share 20, both at the first rank, while the product references the brands 10
	 *   and 13 at the second rank, both with the share 10. The brand 13 holds the value 10 only in the reduced index of
	 *   the second rank, where only the product references it, so no reduced index holds the value at a brand the
	 *   anchor product references there, and the bound is anchored among the brands 10 and 13: the brand 10 without an
	 *   order, the brand 13 with the descending one. The brand 13 anchors the largest value 20 in both orders.
	 * - Over the weight with the default 10, the brand 12 has no weight and the brand 13 weighs 10; the anchor product
	 *   references only the brand 12, the product the brands 10 and 13 at the second rank. Both boundaries are the
	 *   default, no brand the anchor product references weighs 10, and both are anchored among the brands 10 and 13:
	 *   at the brand 10 without an order, at the brand 13 with the descending one.
	 * - Over the share, the anchor product references the brand 11, which no entity is stored for, with the share 10
	 *   and the brand 13 with the share 20, while the product references the brand 10 with the share 10, all at the
	 *   first rank. The boundary is picked by the primary keys of the candidates before any brand is loaded: the brand
	 *   11 is the only brand the anchor product references at the smallest value, so the smallest value is picked at
	 *   it, and the query cannot load it. The bound then takes the brand picked among every brand holding the value,
	 *   the brands 10 and 11: without an order the brand 10, which loads, so the histogram is anchored at the brands 10
	 *   and 13; with the descending order the brand 11 again, so the histogram has neither boundary entity - the brand
	 *   10 does not take its place there.
	 * - Over the share, the same owners with the anchor product referencing the brand 12 instead of the brand 11, and
	 *   the brand 12 moved to the archived scope, which the query does not ask for. The smallest value is picked at the
	 *   brand 12, the only brand the anchor product references holding it, and the query cannot load it. Among every
	 *   brand holding the value - the brands 10 and 12 - the bound takes the brand 10 without an order, which loads, so
	 *   the histogram is anchored at the brands 10 and 13; with the descending order the brand 12 again, so the
	 *   histogram has neither boundary entity.
	 * - Over the share, the anchor product references the brand 11 with the share 10 and the brand 13 with the share
	 *   20, while the product references the brands 9, 10 and 14 with the share 10, all at the first rank; no entity is
	 *   stored for the brands 9, 11 and 14. The smallest value is picked at the brand 11 of the anchor product, and
	 *   among every brand holding it at the brand 9 without an order and at the brand 14 with the descending one.
	 *   Neither loads, so the histogram has neither boundary entity in both orders: the brand 10, which holds the value
	 *   and loads, does not take their place, because the bound falls back only to the brand picked among every brand
	 *   holding the value.
	 * - Over the weight of the brand, the query asks for the English locale and both owners are named in it, the brands
	 *   10 and 13 too, while the brand 12 is named only in Czech. The anchor product references the brands 12 and 13 at
	 *   the first rank, the product the brand 10 at the second one. The smallest weight 10 is picked at the brand 12,
	 *   the only brand the anchor product references weighing it, which the query cannot load in English. Among every
	 *   brand weighing 10 - the brands 10 and 12 - the bound takes the brand 10 without an order, which loads, and the
	 *   brand 12 with the descending order, which does not, so the histogram has neither boundary entity there.
	 * - The control keeps every owner over the weight of the brand, so the brand 10, referenced only in the reduced
	 *   index of the second rank, and the brand 12 both anchor the smallest value: the brand 10 without an order, the
	 *   brand 12 with the descending one. The controls of the scenarios in both scopes keep every owner, so the product
	 *   is counted at 20 through the brand 10 too.
	 *
	 * @return arguments of {@link #shouldAnchorBoundariesAtContributingReferencesWhenQueryNarrowsOwners}
	 */
	@Nonnull
	static Stream<Arguments> narrowedOwnerScenarios() {
		final String expected = anchoredRange(
			ANCHOR_VALUE, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK
		);
		final String withoutBoundaryEntities =
			"min=" + ANCHOR_VALUE + "@none max=" + SECOND_ANCHOR_VALUE + "@none";
		return withBothCatalogStates(
			List.of(
				new Scenario(
					"ANCHOR_KEPT_PRODUCT_INDEX_CREATED_FIRST",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null,
					List.of(createProductWithoutShare(SECOND_RANK), createAnchor(FIRST_RANK, false)),
					tiedShares(),
					expected
				),
				new Scenario(
					"ANCHOR_KEPT_ANCHOR_INDEX_CREATED_FIRST",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null,
					List.of(createAnchor(FIRST_RANK, false), createProductWithoutShare(SECOND_RANK)),
					tiedShares(),
					expected
				),
				new Scenario(
					"ANCHOR_KEPT_CONTROL_EVERYONE_AT_ONE_RANK",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null,
					List.of(createProductWithoutShare(FIRST_RANK), createAnchor(FIRST_RANK, false)),
					tiedShares(),
					expected
				),
				new Scenario(
					"ANCHOR_KEPT_ANCHOR_REFERENCES_BRAND_TWICE",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null,
					List.of(createProductWithoutShare(SECOND_RANK)),
					List.of(productShare(ANCHOR_VALUE), createAnchorReferencingFirstBrandTwice()),
					anchoredRange(ANCHOR_VALUE, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_VALUE, FIRST_ANCHOR_BRAND_PK)
				),
				new Scenario(
					"ANCHOR_KEPT_OVER_BRAND_WEIGHT",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, ANCHOR_PK, null,
					List.of(createAnchor(FIRST_RANK, false)),
					List.of(createProductWithoutShare(SECOND_RANK)),
					expected
				),
				new Scenario(
					"ANCHOR_KEPT_SMALLEST_VALUE_DEFAULTED",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null, ANCHOR_VALUE, null, 0,
					List.of(createProductWithoutShare(FIRST_ANCHOR_BRAND_PK, SECOND_RANK)),
					List.of(productShare(ANCHOR_VALUE), createAnchorWithShares(null, FIRST_RANK, SECOND_ANCHOR_VALUE)),
					expected, expected
				),
				new Scenario(
					"ANCHOR_KEPT_SMALLEST_DECIMAL_VALUE_DEFAULTED",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null, ANCHOR_VALUE, null, SHARE_DECIMAL_PLACES,
					List.of(createProductWithoutShare(FIRST_ANCHOR_BRAND_PK, SECOND_RANK)),
					List.of(
						productShare(decimalShare(ANCHOR_VALUE)),
						createAnchorWithShares(null, FIRST_RANK, decimalShare(SECOND_ANCHOR_VALUE))
					),
					expected, expected
				),
				new Scenario(
					"ANCHOR_KEPT_DECIMAL_DEFAULT_BEYOND_INDEXED_RANGE",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null,
					DEFAULT_BEYOND_INDEXED_RANGE, null, SHARE_DECIMAL_PLACES,
					List.of(),
					List.of(
						createProduct(SECOND_ANCHOR_BRAND_PK, SECOND_RANK, decimalShare(ANCHOR_VALUE)),
						createAnchorWithShares(
							decimalShare(ANCHOR_VALUE), FIRST_RANK, decimalShare(SECOND_ANCHOR_VALUE)
						)
					),
					expected, expected
				),
				new Scenario(
					"ANCHOR_KEPT_OVER_BRAND_WEIGHT_SMALLEST_VALUE_DEFAULTED",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, ANCHOR_PK, null, ANCHOR_VALUE, null, 0,
					List.of(removeBrandWeight(FIRST_ANCHOR_BRAND_PK)),
					List.of(createProductWithoutShare(SECOND_RANK), createAnchor(FIRST_RANK, false)),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK)
				),
				new Scenario(
					"ANCHOR_KEPT_OVER_BRAND_WEIGHT_ONLY_DEFAULTED_VALUE",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, ANCHOR_PK, null, ANCHOR_VALUE, null, 0,
					List.of(removeBrandWeight(FIRST_ANCHOR_BRAND_PK)),
					List.of(createProductWithoutShare(SECOND_RANK), createAnchorReferencingFirstBrandOnly()),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, ANCHOR_VALUE, BRAND_PK),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, ANCHOR_VALUE, BRAND_PK)
				),
				archivedScopeScenario(
					"ANCHOR_KEPT_SMALLEST_VALUE_DEFAULTED_IN_ARCHIVED_SCOPE",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK
				),
				archivedScopeScenario(
					"ANCHOR_KEPT_OVER_BRAND_WEIGHT_SMALLEST_VALUE_DEFAULTED_IN_ARCHIVED_SCOPE",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, ANCHOR_PK
				),
				new Scenario(
					"ANCHOR_KEPT_NON_CONTRIBUTOR_AT_DISTINCT_MAXIMUM",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null,
					List.of(createProductWithoutShare(SECOND_RANK), createAnchor(FIRST_RANK, false)),
					List.of(anchorShares(ANCHOR_VALUE, SECOND_ANCHOR_VALUE), productShare(SECOND_ANCHOR_VALUE)),
					expected
				),
				new Scenario(
					"ANCHOR_KEPT_NON_CONTRIBUTOR_WITH_HIGHER_KEY",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null,
					List.of(
						createProductWithoutShare(SECOND_ANCHOR_BRAND_PK, SECOND_RANK), createAnchor(FIRST_RANK, false)
					),
					tiedShares(),
					expected
				),
				new Scenario(
					"ANCHOR_KEPT_NON_CONTRIBUTOR_WITH_HIGHER_KEY_AT_DEFAULT",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null, ANCHOR_VALUE, null, 0,
					List.of(
						createProductWithoutShare(SECOND_ANCHOR_BRAND_PK, SECOND_RANK), createAnchor(FIRST_RANK, false)
					),
					tiedShares(),
					expected, expected
				),
				new Scenario(
					"ANCHOR_KEPT_SMALLEST_VALUE_DEFAULTED_TWO_OTHER_BRANDS_HOLD_IT",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null, ANCHOR_VALUE, null, 0,
					List.of(
						createOwnerReferencingTwoBrands(
							PRODUCT_PK, SECOND_RANK, BRAND_PK, null, SECOND_ANCHOR_BRAND_PK, null
						)
					),
					List.of(productShare(ANCHOR_VALUE), createAnchorWithShares(null, FIRST_RANK, SECOND_ANCHOR_VALUE)),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK),
					anchoredRange(ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK)
				),
				new Scenario(
					"ANCHOR_KEPT_OVER_BRAND_WEIGHT_ONLY_DEFAULTED_VALUE_TWO_OTHER_BRANDS_HOLD_IT",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, ANCHOR_PK, null, ANCHOR_VALUE, null, 0,
					List.of(
						removeBrandWeight(FIRST_ANCHOR_BRAND_PK), setBrandWeight(SECOND_ANCHOR_BRAND_PK, ANCHOR_VALUE)
					),
					List.of(
						createOwnerReferencingTwoBrands(
							PRODUCT_PK, SECOND_RANK, BRAND_PK, null, SECOND_ANCHOR_BRAND_PK, null
						),
						createAnchorReferencingFirstBrandOnly()
					),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, ANCHOR_VALUE, BRAND_PK),
					anchoredRange(ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK, ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK)
				),
				new Scenario(
					"ANCHOR_KEPT_CONTRIBUTOR_NOT_LOADABLE",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null, null, null, 0,
					List.of(),
					List.of(
						createProduct(BRAND_PK, FIRST_RANK, ANCHOR_VALUE),
						createOwnerReferencingTwoBrands(
							ANCHOR_PK, FIRST_RANK, UNSTORED_BRAND_PK, ANCHOR_VALUE,
							SECOND_ANCHOR_BRAND_PK, SECOND_ANCHOR_VALUE
						)
					),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK),
					withoutBoundaryEntities
				),
				new Scenario(
					"ANCHOR_KEPT_CONTRIBUTOR_OUTSIDE_REQUESTED_SCOPES",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null, null, null, 0,
					List.of(archiveBrand(FIRST_ANCHOR_BRAND_PK, ANCHOR_VALUE)),
					List.of(createProduct(BRAND_PK, FIRST_RANK, ANCHOR_VALUE), createAnchor(FIRST_RANK, true)),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK),
					withoutBoundaryEntities
				),
				new Scenario(
					"ANCHOR_KEPT_CONTRIBUTOR_AND_ALTERNATIVE_NOT_LOADABLE",
					HistogramValueSource.REFERENCE_ATTRIBUTE, ANCHOR_PK, null,
					List.of(),
					List.of(
						createOwnerReferencingBrands(
							PRODUCT_PK, FIRST_RANK, ANCHOR_VALUE,
							UNSTORED_LOWER_BRAND_PK, BRAND_PK, UNSTORED_HIGHER_BRAND_PK
						),
						createOwnerReferencingTwoBrands(
							ANCHOR_PK, FIRST_RANK, UNSTORED_BRAND_PK, ANCHOR_VALUE,
							SECOND_ANCHOR_BRAND_PK, SECOND_ANCHOR_VALUE
						)
					),
					withoutBoundaryEntities
				),
				new Scenario(
					"ANCHOR_KEPT_OVER_BRAND_WEIGHT_CONTRIBUTOR_LACKS_QUERY_LOCALE",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, ANCHOR_PK, null, null, null, 0,
					List.of(
						nameEntity(ENTITY_BRAND, BRAND_PK, QUERY_LOCALE),
						nameEntity(ENTITY_BRAND, FIRST_ANCHOR_BRAND_PK, OTHER_LOCALE),
						nameEntity(ENTITY_BRAND, SECOND_ANCHOR_BRAND_PK, QUERY_LOCALE)
					),
					List.of(
						createProductWithoutShare(SECOND_RANK),
						createAnchor(FIRST_RANK, false),
						nameEntity(ENTITY_PRODUCT, PRODUCT_PK, QUERY_LOCALE),
						nameEntity(ENTITY_PRODUCT, ANCHOR_PK, QUERY_LOCALE)
					),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK),
					withoutBoundaryEntities,
					QUERY_LOCALE
				),
				new Scenario(
					"CONTROL_EVERY_OWNER_KEPT_OVER_BRAND_WEIGHT",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, null, null, null, null, 0,
					List.of(createAnchor(FIRST_RANK, false)),
					List.of(createProductWithoutShare(SECOND_RANK)),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK),
					expected
				),
				archivedScopeScenario(
					"CONTROL_EVERY_OWNER_KEPT_IN_ARCHIVED_SCOPE",
					HistogramValueSource.REFERENCE_ATTRIBUTE, null
				),
				archivedScopeScenario(
					"CONTROL_EVERY_OWNER_KEPT_OVER_BRAND_WEIGHT_IN_ARCHIVED_SCOPE",
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, null
				)
			)
		);
	}

	/**
	 * Creates a scenario whose owners and brands sit in the archived scope, whose histogram has the default
	 * {@link #ANCHOR_VALUE} in the live scope and {@link #ARCHIVED_VALUE_DEFAULT} in the archived one, and whose query
	 * asks for both scopes. The live scope holds no reference, so the histogram of the group is the one of the archived
	 * scope, from 20 to 30.
	 *
	 * In the warm-up the brand 10 gets the weight 20, the brand 12 loses its weight and the brand 13 gets the weight
	 * 30, and all three move to the archived scope before any reference to them exists. The steps then create, directly
	 * in the archived scope, the product referencing the brand 10 at the second rank and the anchor product referencing
	 * the brands 12 and 13 at the first rank. Over the share the reference of the product carries the share 20, the one
	 * of the anchor product to the brand 13 the share 30, and its reference to the brand 12 none; over the weight no
	 * reference carries a share. Either way the anchor product is counted at 20 through the default of the archived
	 * scope, given by a reference no filter index holds, and at 30 through the brand 13, while the product is counted
	 * at 20 through the brand 10. The only brand any index holds at 20 is the brand 10, and the only one at 30 the
	 * brand 13, so both orders anchor the histogram alike.
	 *
	 * Where the query keeps only the anchor product, the brand 10 anchors the smallest value although only the product
	 * the query leaves out references it: the anchor product gets 20 from the default, so no brand an owner the
	 * histogram counts at 20 references holds that value in any index, and with no such candidate every brand holding
	 * the value is one.
	 *
	 * @param name        the name of the scenario
	 * @param source      the source of the values of the histogram of the scenario
	 * @param keptOwnerPk the primary key of the only owner the query keeps, or `null` to keep all
	 * @return the scenario
	 */
	@Nonnull
	private static Scenario archivedScopeScenario(
		@Nonnull String name,
		@Nonnull HistogramValueSource source,
		@Nullable Integer keptOwnerPk
	) {
		final boolean overShare = source == HistogramValueSource.REFERENCE_ATTRIBUTE;
		final String expected = anchoredRange(
			ARCHIVED_VALUE_DEFAULT, BRAND_PK, ARCHIVED_LARGEST_VALUE, SECOND_ANCHOR_BRAND_PK
		);
		return new Scenario(
			name, source, keptOwnerPk, null, ANCHOR_VALUE, ARCHIVED_VALUE_DEFAULT, 0,
			List.of(
				archiveBrand(BRAND_PK, ARCHIVED_VALUE_DEFAULT),
				archiveBrand(FIRST_ANCHOR_BRAND_PK, null),
				archiveBrand(SECOND_ANCHOR_BRAND_PK, ARCHIVED_LARGEST_VALUE)
			),
			List.of(
				createProduct(BRAND_PK, SECOND_RANK, overShare ? ARCHIVED_VALUE_DEFAULT : null, Scope.ARCHIVED),
				createAnchorWithShares(null, FIRST_RANK, overShare ? ARCHIVED_LARGEST_VALUE : null, Scope.ARCHIVED)
			),
			expected, expected
		);
	}

	/**
	 * The scenarios of a histogram condition admitting only the references above the first rank, see
	 * {@link #shouldAnchorBoundariesAtContributingReferencesWhenConditionLeavesOwnersOut}.
	 *
	 * In the first two scenarios the product references the brand 10 at the second rank with the share 10, which the
	 * condition admits. The reference of the anchor product to the brand 12 sits at the first rank with the same share,
	 * and the condition rejects it, so the brand 12 anchors no boundary.
	 *
	 * - Both references of the anchor product sit at the first rank: the histogram holds only the share 10 of the
	 *   product, which anchors both boundaries at the brand 10.
	 * - The reference of the anchor product to the brand 13 sits at the second rank: the condition admits it, so the
	 *   anchor product is counted, though only at its share 20, which anchors the largest value at the brand 13.
	 *
	 * In the other two the ranks are swapped: the product references the brand 10 at the first rank and the condition
	 * rejects it, while both references of the anchor product sit at the second rank and anchor the boundaries at the
	 * brands 12 and 13. The rejected brand 10 has the lower primary key and carries the share 10, tying with the brand
	 * 12 at the smallest value, or the share 20, tying with the brand 13 at the largest one.
	 *
	 * @return arguments of {@link #shouldAnchorBoundariesAtContributingReferencesWhenConditionLeavesOwnersOut}
	 */
	@Nonnull
	static Stream<Arguments> conditionedOwnerScenarios() {
		return withBothCatalogStates(
			List.of(
				new Scenario(
					"CONDITION_ADMITS_ONLY_PRODUCT",
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, CONDITION_ABOVE_FIRST_RANK,
					List.of(createProductWithoutShare(SECOND_RANK), createAnchor(FIRST_RANK, false)),
					tiedShares(),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, ANCHOR_VALUE, BRAND_PK)
				),
				new Scenario(
					"CONDITION_ADMITS_ONE_ANCHOR_REFERENCE",
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, CONDITION_ABOVE_FIRST_RANK,
					List.of(createProductWithoutShare(SECOND_RANK), createAnchorAcrossRanks()),
					tiedShares(),
					anchoredRange(ANCHOR_VALUE, BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK)
				),
				new Scenario(
					"CONDITION_REJECTED_BRAND_WITH_LOWER_KEY_AT_MINIMUM",
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, CONDITION_ABOVE_FIRST_RANK,
					List.of(createProductWithoutShare(FIRST_RANK), createAnchor(SECOND_RANK, false)),
					tiedShares(),
					anchoredRange(ANCHOR_VALUE, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK)
				),
				new Scenario(
					"CONDITION_REJECTED_BRAND_WITH_LOWER_KEY_AT_MAXIMUM",
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, CONDITION_ABOVE_FIRST_RANK,
					List.of(createProductWithoutShare(FIRST_RANK), createAnchor(SECOND_RANK, false)),
					List.of(anchorShares(ANCHOR_VALUE, SECOND_ANCHOR_VALUE), productShare(SECOND_ANCHOR_VALUE)),
					anchoredRange(ANCHOR_VALUE, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_VALUE, SECOND_ANCHOR_BRAND_PK)
				)
			)
		);
	}

	/**
	 * Applies the upserts of the scenario and checks, after the last of them and after a restart, that a boundary
	 * value is picked among the brands referenced by an owner the query keeps whenever such a brand holds the value,
	 * even when a reference of an owner the query leaves out carries the same value in the group - and, where no such
	 * brand holds it because the default of the histogram's expression gives the value, among every brand holding the
	 * value. A boundary picked at a brand the query cannot load is anchored at the brand picked among every brand
	 * holding the value when that one loads; when neither loads, the histogram has no boundary entities.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("narrowedOwnerScenarios")
	@DisplayName("a boundary is picked among the referenced entities of kept owners whenever one holds the value")
	void shouldAnchorBoundariesAtContributingReferencesWhenQueryNarrowsOwners(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Applies the upserts of the scenario and checks, after the last of them and after a restart, that a boundary
	 * value is picked among the brands whose reference carrying it the histogram condition admits whenever such a
	 * reference carries the value, even when a rejected reference carries the same value in the group.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("conditionedOwnerScenarios")
	@DisplayName("a boundary is picked among the entities of admitted references whenever one holds the value")
	void shouldAnchorBoundariesAtContributingReferencesWhenConditionLeavesOwnersOut(
		@Nonnull CatalogState state,
		@Nonnull Scenario scenario
	) {
		runScenario(state, scenario);
	}

	/**
	 * Runs one scenario: sets the catalog up, applies each upsert, checks the histogram of the group with both orders
	 * of the reference summary, restarts the engine and checks it again. All failed checks are reported together.
	 *
	 * @param state    the state the catalog is in when the upserts are applied
	 * @param scenario the scenario
	 */
	private void runScenario(@Nonnull CatalogState state, @Nonnull Scenario scenario) {
		final TestPaths paths = createTestPaths("GroupHistogramContributingOwners_" + state + "_" + scenario.name());
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final List<Executable> checks = new ArrayList<>(8);
		Evita evita = new Evita(configuration);
		try {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpCatalog(session, scenario);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});

			int stepNumber = 0;
			for (Consumer<EvitaSessionContract> step : scenario.steps()) {
				stepNumber++;
				final String stage = "upsert " + stepNumber;
				try {
					evita.updateCatalog(TEST_CATALOG, step);
				} catch (RuntimeException ex) {
					checks.add(
						() -> fail(
							stage + " threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex
						)
					);
				}
			}
			recordState(evita, "after the last upsert", scenario, checks);
			evita = restart(evita, configuration);
			recordState(evita, "after a restart", scenario, checks);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Records the checks that the histogram of the group is described as the scenario expects, without an order of the
	 * reference summary and with the descending order of primary keys, at the moment `stage` names.
	 *
	 * @param evita    the engine
	 * @param stage    names the moment of the check
	 * @param scenario the scenario
	 * @param checks   the collected checks
	 */
	private static void recordState(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scenario scenario,
		@Nonnull List<Executable> checks
	) {
		final String histogramName = histogramName(scenario.source());
		try {
			final String withoutOrder = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return describeHistogram(
						session, histogramName, scenario.scopes(), scenario.keptOwnerPk(), scenario.queryLocale(), null
					);
				}
			);
			checks.add(
				() -> assertEquals(scenario.expectedWithoutOrder(), withoutOrder, stage + ", without order")
			);
			final String descendingByKey = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return describeHistogram(
						session, histogramName, scenario.scopes(), scenario.keptOwnerPk(), scenario.queryLocale(),
						orderBy(entityPrimaryKeyNatural(OrderDirection.DESC))
					);
				}
			);
			checks.add(
				() -> assertEquals(
					scenario.expectedDescendingByKey(), descendingByKey, stage + ", descending by primary key"
				)
			);
		} catch (RuntimeException ex) {
			checks.add(() -> fail(stage + ": a query threw " + ex, ex));
		}
	}

	/**
	 * Describes the histogram `histogramName` of the group the reference summary of `scopes` computes: its smallest and
	 * largest value, each followed by the primary key of the brand anchoring it, or the absence of the histogram.
	 *
	 * @param session       the session to query
	 * @param histogramName the name of the histogram
	 * @param scopes        the scopes the query asks for
	 * @param keptOwnerPk   the primary key of the only owner the query keeps, or `null` to keep all
	 * @param locale        the locale the query asks for, or `null` for none
	 * @param order         the order of the brands in the reference summary, or `null` for none
	 * @return the description, see {@link GroupHistogramTestSupport#anchoredRange}
	 */
	@Nonnull
	private static String describeHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull String histogramName,
		@Nonnull Scope[] scopes,
		@Nullable Integer keptOwnerPk,
		@Nullable Locale locale,
		@Nullable OrderBy order
	) {
		final List<FilterConstraint> constraints = new ArrayList<>(3);
		constraints.add(scope(scopes));
		if (locale != null) {
			constraints.add(entityLocaleEquals(locale));
		}
		if (keptOwnerPk != null) {
			constraints.add(entityPrimaryKeyInSet(keptOwnerPk));
		}
		final FilterBy filter = filterBy(constraints.toArray(FilterConstraint[]::new));
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filter,
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null, order, null, null, null,
						histogramStatistics(BUCKET_COUNT, entityFetch(), histogramName)
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		final ReferenceGroupStatistics statistics = summary == null ?
			null : summary.getReferenceGroupStatistics(REFERENCE_BRAND, GROUP_PK);
		final HistogramContract histogram = statistics == null ?
			null : statistics.getHistogramStatistics(histogramName);
		if (histogram == null) {
			return NO_HISTOGRAM;
		}
		return "min=" + plain(histogram.getMin()) + "@" +
			describeBoundary(histogram.getMinReferencedEntity().orElse(null)) +
			" max=" + plain(histogram.getMax()) + "@" +
			describeBoundary(histogram.getMaxReferencedEntity().orElse(null));
	}

	/**
	 * Creates the upsert storing the product with one reference to the brand 10 in the group 1 at `rank`, without a
	 * share.
	 *
	 * @param rank the rank of the reference
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProductWithoutShare(int rank) {
		return createProductWithoutShare(BRAND_PK, rank);
	}

	/**
	 * Creates the upsert storing the product with one reference to the brand `brandPk` in the group 1 at `rank`,
	 * without a share.
	 *
	 * @param brandPk the primary key of the referenced brand
	 * @param rank    the rank of the reference
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProductWithoutShare(int brandPk, int rank) {
		return createProduct(brandPk, rank, null);
	}

	/**
	 * Creates the upsert storing the product with one reference to the brand `brandPk` in the group 1 at `rank`,
	 * with the share `share`.
	 *
	 * @param brandPk the primary key of the referenced brand
	 * @param rank    the rank of the reference
	 * @param share   the share of the reference, or `null` for none
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProduct(int brandPk, int rank, @Nullable Number share) {
		return createProduct(brandPk, rank, share, Scope.LIVE);
	}

	/**
	 * Creates the upsert storing the product in `scope` with one reference to the brand `brandPk` in the group 1 at
	 * `rank`, with the share `share`.
	 *
	 * @param brandPk the primary key of the referenced brand
	 * @param rank    the rank of the reference
	 * @param share   the share of the reference, or `null` for none
	 * @param scope   the scope the product is created in
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createProduct(
		int brandPk,
		int rank,
		@Nullable Number share,
		@Nonnull Scope scope
	) {
		return session -> {
			final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK);
			product.setScope(scope);
			addReference(product, brandPk, GROUP_PK, rank, share);
			product.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert storing the anchor product with its reference to the brand 12 at the first rank and its
	 * reference to the brand 13 at the second rank, both in the group 1, without a share.
	 *
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createAnchorAcrossRanks() {
		return session -> {
			final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ANCHOR_PK);
			addReference(anchor, FIRST_ANCHOR_BRAND_PK, GROUP_PK, FIRST_RANK, null);
			addReference(anchor, SECOND_ANCHOR_BRAND_PK, GROUP_PK, SECOND_RANK, null);
			anchor.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert storing the anchor product with its only reference, to the brand 12 at the first rank in the
	 * group 1, without a share.
	 *
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createAnchorReferencingFirstBrandOnly() {
		return session -> {
			final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ANCHOR_PK);
			addReference(anchor, FIRST_ANCHOR_BRAND_PK, GROUP_PK, FIRST_RANK, null);
			anchor.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert removing the weight of the stored brand `brandPk`, so a reference to it gives a histogram over
	 * the weight the default of its expression.
	 *
	 * @param brandPk the primary key of the brand
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> removeBrandWeight(int brandPk) {
		return session -> session.getEntity(ENTITY_BRAND, brandPk, attributeContentAll())
			.orElseThrow()
			.openForWrite()
			.removeAttribute(ATTRIBUTE_WEIGHT)
			.upsertVia(session);
	}

	/**
	 * Creates the upsert setting the weight of the stored brand `brandPk` to `weight`.
	 *
	 * @param brandPk the primary key of the brand
	 * @param weight  the new weight of the brand
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> setBrandWeight(int brandPk, int weight) {
		return session -> session.getEntity(ENTITY_BRAND, brandPk, attributeContentAll())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTRIBUTE_WEIGHT, weight)
			.upsertVia(session);
	}

	/**
	 * Creates the upsert storing the anchor product with two references to the brand 12 in the group 1: one at the
	 * first rank with the share 20, the other at the second rank with the share 10.
	 *
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createAnchorReferencingFirstBrandTwice() {
		return session -> {
			final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ANCHOR_PK);
			addReference(anchor, FIRST_ANCHOR_BRAND_PK, GROUP_PK, FIRST_RANK, SECOND_ANCHOR_VALUE);
			addReference(anchor, FIRST_ANCHOR_BRAND_PK, GROUP_PK, SECOND_RANK, ANCHOR_VALUE);
			anchor.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert storing the anchor product with its references to the brands 12 and 13 in the group 1: the
	 * one to the brand 12 at the first rank with the share `firstShare`, the one to the brand 13 at `secondRank` with
	 * the share `secondShare`.
	 *
	 * @param firstShare  the share of the reference to the brand 12, or `null` for none
	 * @param secondRank  the rank of the reference to the brand 13
	 * @param secondShare the share of the reference to the brand 13
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createAnchorWithShares(
		@Nullable Number firstShare,
		int secondRank,
		@Nonnull Number secondShare
	) {
		return createAnchorWithShares(firstShare, secondRank, secondShare, Scope.LIVE);
	}

	/**
	 * Creates the upsert storing the anchor product in `scope` with its references to the brands 12 and 13 in the
	 * group 1: the one to the brand 12 at the first rank with the share `firstShare`, the one to the brand 13 at
	 * `secondRank` with the share `secondShare`.
	 *
	 * @param firstShare  the share of the reference to the brand 12, or `null` for none
	 * @param secondRank  the rank of the reference to the brand 13
	 * @param secondShare the share of the reference to the brand 13, or `null` for none
	 * @param scope       the scope the anchor product is created in
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createAnchorWithShares(
		@Nullable Number firstShare,
		int secondRank,
		@Nullable Number secondShare,
		@Nonnull Scope scope
	) {
		return session -> {
			final EntityBuilder anchor = session.createNewEntity(ENTITY_PRODUCT, ANCHOR_PK);
			anchor.setScope(scope);
			addReference(anchor, FIRST_ANCHOR_BRAND_PK, GROUP_PK, FIRST_RANK, firstShare);
			addReference(anchor, SECOND_ANCHOR_BRAND_PK, GROUP_PK, secondRank, secondShare);
			anchor.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert storing the owner `ownerPk` with two references in the group 1 at `rank`: one to the brand
	 * `firstBrandPk` with the share `firstShare`, the other to the brand `secondBrandPk` with the share `secondShare`.
	 *
	 * @param ownerPk       the primary key of the owner
	 * @param rank          the rank of both references
	 * @param firstBrandPk  the primary key of the brand the first reference points to
	 * @param firstShare    the share of the first reference, or `null` for none
	 * @param secondBrandPk the primary key of the brand the second reference points to
	 * @param secondShare   the share of the second reference, or `null` for none
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createOwnerReferencingTwoBrands(
		int ownerPk,
		int rank,
		int firstBrandPk,
		@Nullable Number firstShare,
		int secondBrandPk,
		@Nullable Number secondShare
	) {
		return session -> {
			final EntityBuilder owner = session.createNewEntity(ENTITY_PRODUCT, ownerPk);
			addReference(owner, firstBrandPk, GROUP_PK, rank, firstShare);
			addReference(owner, secondBrandPk, GROUP_PK, rank, secondShare);
			owner.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert storing the owner `ownerPk` with one reference to each of the brands `brandPks` in the group 1
	 * at `rank`, all with the share `share`.
	 *
	 * @param ownerPk  the primary key of the owner
	 * @param rank     the rank of every reference
	 * @param share    the share of every reference
	 * @param brandPks the primary keys of the referenced brands
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> createOwnerReferencingBrands(
		int ownerPk,
		int rank,
		@Nonnull Number share,
		int... brandPks
	) {
		return session -> {
			final EntityBuilder owner = session.createNewEntity(ENTITY_PRODUCT, ownerPk);
			for (final int brandPk : brandPks) {
				addReference(owner, brandPk, GROUP_PK, rank, share);
			}
			owner.upsertVia(session);
		};
	}

	/**
	 * Creates the upsert moving the stored brand `brandPk` to the archived scope with the weight `weight`.
	 *
	 * @param brandPk the primary key of the brand
	 * @param weight  the weight of the brand, or `null` to remove it, so a reference to the brand gives a histogram
	 *                over the weight the default of its expression
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> archiveBrand(int brandPk, @Nullable Integer weight) {
		return session -> {
			final EntityBuilder brand = session.getEntity(ENTITY_BRAND, brandPk, attributeContentAll())
				.orElseThrow()
				.openForWrite();
			if (weight == null) {
				brand.removeAttribute(ATTRIBUTE_WEIGHT);
			} else {
				brand.setAttribute(ATTRIBUTE_WEIGHT, weight);
			}
			brand.setScope(Scope.ARCHIVED);
			brand.upsertVia(session);
		};
	}

	/**
	 * Returns the upserts giving the references of the anchor product to the brands 12 and 13 the shares 10 and 20,
	 * and every reference of the product the share 10, so the brand the product references ties with the brand 12 at
	 * the smallest value whenever both are counted.
	 *
	 * @return the upserts, in the order they are applied
	 */
	@Nonnull
	private static List<Consumer<EvitaSessionContract>> tiedShares() {
		return List.of(anchorShares(ANCHOR_VALUE, SECOND_ANCHOR_VALUE), productShare(ANCHOR_VALUE));
	}

	/**
	 * Creates the upsert setting the share of every reference of the stored product to `share`.
	 *
	 * @param share the share, of the type the scenario defines the share with
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> productShare(@Nonnull Number share) {
		return everyReferenceAttribute(PRODUCT_PK, ATTRIBUTE_SHARE, share);
	}

	/**
	 * Returns `share` as the value of a `BigDecimal` share, written with {@link #SHARE_DECIMAL_PLACES} decimal places.
	 *
	 * @param share the share
	 * @return the decimal share, `10.00` for the share 10
	 */
	@Nonnull
	private static BigDecimal decimalShare(int share) {
		return BigDecimal.valueOf(share).setScale(SHARE_DECIMAL_PLACES);
	}

	/**
	 * Returns the name of the histogram reading values of `source`.
	 *
	 * @param source the source of the values
	 * @return the name of the histogram
	 */
	@Nonnull
	private static String histogramName(@Nonnull HistogramValueSource source) {
		return switch (source) {
			case REFERENCE_ATTRIBUTE -> HISTOGRAM_SHARE;
			case REFERENCED_ENTITY_ATTRIBUTE -> HISTOGRAM_WEIGHT;
		};
	}

	/**
	 * Returns the expression of the values of the histogram of `scenario` in `scope`: the attribute its source reads,
	 * falling back to the default of the scenario in the scope where it has one, written with the decimal places of the
	 * share - `10` for an `Integer` share, `10.00` for a share with two decimal places.
	 *
	 * @param scenario the scenario
	 * @param scope    the scope the histogram is defined in
	 * @return the expression of the values
	 */
	@Nonnull
	private static String histogramValue(@Nonnull Scenario scenario, @Nonnull Scope scope) {
		final String attributeValue = switch (scenario.source()) {
			case REFERENCE_ATTRIBUTE -> "$reference.attributes['" + ATTRIBUTE_SHARE + "']";
			case REFERENCED_ENTITY_ATTRIBUTE -> "$reference.referencedEntity?.attributes['" + ATTRIBUTE_WEIGHT + "']";
		};
		final Integer valueDefault = scenario.valueDefaultIn(scope);
		if (valueDefault == null) {
			return attributeValue;
		}
		return attributeValue + " ?? " +
			BigDecimal.valueOf(valueDefault).setScale(scenario.shareDecimalPlaces()).toPlainString();
	}

	/**
	 * Returns the type of the `share` attribute of `scenario`: a `BigDecimal` when it is indexed with decimal places,
	 * an `Integer` otherwise.
	 *
	 * @param scenario the scenario
	 * @return the type of the share
	 */
	@Nonnull
	private static Class<? extends Number> shareType(@Nonnull Scenario scenario) {
		return scenario.shareDecimalPlaces() == 0 ? Integer.class : BigDecimal.class;
	}

	/**
	 * Returns the weight of the brand `brandPk`: the brands 10 and 12 weigh as much as the share of the reference to
	 * the brand 12, the brand 13 as much as the share of the reference to it.
	 *
	 * @param brandPk the primary key of the brand
	 * @return the weight
	 */
	private static int weightOf(int brandPk) {
		return switch (brandPk) {
			case BRAND_PK, FIRST_ANCHOR_BRAND_PK -> ANCHOR_VALUE;
			case SECOND_ANCHOR_BRAND_PK -> SECOND_ANCHOR_VALUE;
			default -> throw new IllegalArgumentException("Unknown brand " + brandPk + ".");
		};
	}

	/**
	 * Adds the locales {@link #QUERY_LOCALE} and {@link #OTHER_LOCALE} and the localized attribute `name` to the schema
	 * `schema` builds, so an entity of it is loaded in a locale only when it is named in that locale.
	 *
	 * @param schema the builder of the schema
	 * @return the same builder
	 */
	@Nonnull
	private static EntitySchemaBuilder withLocalizedName(@Nonnull EntitySchemaBuilder schema) {
		return schema
			.withLocale(QUERY_LOCALE, OTHER_LOCALE)
			.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable());
	}

	/**
	 * Creates the upsert naming the stored entity `primaryKey` of `entityType` in `locale`, see
	 * {@link #withLocalizedName}.
	 *
	 * @param entityType the type of the entity
	 * @param primaryKey the primary key of the entity
	 * @param locale     the locale of the name
	 * @return the upsert
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> nameEntity(
		@Nonnull String entityType,
		int primaryKey,
		@Nonnull Locale locale
	) {
		return session -> session.getEntity(entityType, primaryKey, attributeContentAll(), dataInLocalesAll())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTRIBUTE_NAME, locale, entityType + "-" + primaryKey)
			.upsertVia(session);
	}

	/**
	 * Defines the schemas, stores the group and the brands with their weights, and applies the set-up upserts of the
	 * scenario.
	 *
	 * The brand carries the `weight` attribute, filterable in every scope of the scenario. The reference of a product
	 * to a brand is the one of {@link GroupHistogramTestSupport#defineProduct}, indexed in every scope of the scenario,
	 * with the one histogram reading the source of the scenario in each of them, admitting only the references the
	 * condition of the scenario accepts where it has one.
	 *
	 * In the scenarios with a locale of the query the brand and the product also carry the localized attribute `name`,
	 * see {@link #withLocalizedName}.
	 *
	 * @param session  the session of the catalog in warm-up
	 * @param scenario the scenario
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session, @Nonnull Scenario scenario) {
		final Scope[] scopes = scenario.scopes();
		final EntitySchemaBuilder brandSchema = session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_WEIGHT, Integer.class, thatIs -> thatIs.filterableInScope(scopes).nullable());
		if (scenario.queryLocale() != null) {
			withLocalizedName(brandSchema);
		}
		brandSchema.updateVia(session);
		session.defineEntitySchema(ENTITY_BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		defineProduct(
			session, shareType(scenario), scenario.shareDecimalPlaces(), scopes,
			whichIs -> {
				for (final Scope scope : scopes) {
					whichIs.bucketedInScope(
						scope, histogramName(scenario.source()),
						ExpressionFactory.parse(histogramValue(scenario, scope))
					);
					if (scenario.histogramCondition() != null) {
						whichIs.bucketedPartiallyInScope(
							scope, ExpressionFactory.parse(scenario.histogramCondition())
						);
					}
				}
			}
		);
		if (scenario.queryLocale() != null) {
			withLocalizedName(session.getEntitySchemaOrThrowException(ENTITY_PRODUCT).openForWrite())
				.updateVia(session);
		}

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND_GROUP, GROUP_PK));
		for (int brandPk : new int[]{BRAND_PK, FIRST_ANCHOR_BRAND_PK, SECOND_ANCHOR_BRAND_PK}) {
			final EntityBuilder brand = session.createNewEntity(ENTITY_BRAND, brandPk);
			brand.setAttribute(ATTRIBUTE_WEIGHT, weightOf(brandPk));
			session.upsertEntity(brand);
		}
		for (Consumer<EvitaSessionContract> upsert : scenario.setUp()) {
			upsert.accept(session);
		}
	}

}
