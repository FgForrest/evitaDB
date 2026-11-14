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

package io.evitadb.test.upgrade;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.U1;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.U2;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.ZWSP;

/**
 * Recipes of the release fixtures that the v2026.1.20 engine writes. This class compiles against the 2026.1 API, the
 * 2026.2 API and the current one, so it may use nothing newer than 2026.1.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class Release2026_1FixtureRecipes {
	/** Sort-only open-bound twins retained under the 2026.1 sort-part UID. */
	public static final String INHERITED_SORT_TWINS = "inheritedSortTwins";

	/**
	 * Fixture `d6c` of the `d6` shape written by 2026.1 and never opened by 2026.2 (a chained upgrade, protocol 4).
	 */
	public static final String D6C = "d6c";
	/**
	 * Fixture of a sort-only localized `String` whose 2026.1 cardinality section is keyed by the inserting spelling,
	 * ZWSP spelling inserted first.
	 */
	public static final String D12 = "d12";
	/**
	 * Fixture `d12b` of the `d12` shape with the plain spelling inserted first.
	 */
	public static final String D12B = "d12b";
	/**
	 * Fixture of a paged `BigDecimalNumberRange` FILTER whose 2026.1 keys are not monotone under rescaling.
	 */
	public static final String D13 = "d13";
	/**
	 * Fixture of an inline `BigDecimalNumberRange` FILTER in which 2026.1 folded `[12, 90]` and `[1.2, 9]`.
	 */
	public static final String D15 = "d15";

	/**
	 * A reference filter matching no existing reference, so `setOrUpdateReference` always adds a new duplicate.
	 */
	static final Predicate<ReferenceContract> NEW_DUPLICATE = reference -> false;

	private static final String PRODUCT = "product";
	private static final String BRAND = "brand";

	private Release2026_1FixtureRecipes() {
		// recipes only
	}

	/**
	 * Returns the recipes of every fixture the v2026.1.20 engine writes.
	 *
	 * @return the recipes
	 */
	@Nonnull
	public static List<ReleaseFixtureRecipe> all() {
		return Arrays.asList(d6c(), d12(), d12b(), d13(), d15(), inheritedSortTwins());
	}

	/**
	 * Writes unequal sort blocks whose distinct released range values are equal in the current engine.
	 *
	 * @return the recipe, left at protocol 4 to exercise the complete migration chain
	 */
	@Nonnull
	public static ReleaseFixtureRecipe inheritedSortTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_1(
				INHERITED_SORT_TWINS,
				"sort-only open-bound range twins in blocks of two and three, written by 2026.1",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("sortValidity", DateTimeRange.class, thatIs -> thatIs.sortable())
						.updateVia(session);
					for (int i = 1; i <= 5; i++) {
						session.createNewEntity(PRODUCT, i)
							.setAttribute("sortValidity", i <= 2 ? U2 : U1)
							.upsertVia(session);
					}
				}
			)
			.withProbe("ordered twins", query(collection(PRODUCT), orderBy(attributeNatural("sortValidity"))));
	}

	/**
	 * Defines `brand` and the `product.brand` reference of the `d6`, `d7` and `d8` shapes: it allows duplicates, is
	 * partitioned, and its representative attribute `validity` is a filterable `DateTimeRange`; `rank` is a plain
	 * filterable reference attribute. Shared with the 2026.2 recipes of the same shapes.
	 *
	 * @param session the warming-up session
	 */
	static void defineRepresentativeReference(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(BRAND).updateVia(session);
		session.defineEntitySchema(PRODUCT)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.withAttribute("validity", DateTimeRange.class, thatIs -> thatIs.filterable().representative())
					.withAttribute("rank", Integer.class, thatIs -> thatIs.filterable())
			)
			.updateVia(session);
		session.createNewEntity(BRAND, 1).upsertVia(session);
	}

	/**
	 * Adds a new duplicate reference to brand 1 with the given representative `validity` and `rank` to the entity
	 * being built.
	 *
	 * @param session    the session
	 * @param productPk  the product primary key
	 * @param validities the representative values, one reference to brand 1 each; the n-th gets rank n + 1
	 */
	static void upsertProductReferencingBrandOne(
		@Nonnull EvitaSessionContract session,
		int productPk,
		@Nonnull DateTimeRange... validities
	) {
		final EntityBuilder product = session.createNewEntity(PRODUCT, productPk);
		for (int i = 0; i < validities.length; i++) {
			final DateTimeRange validity = validities[i];
			final int rank = i + 1;
			product.setOrUpdateReference(
				BRAND, 1, NEW_DUPLICATE, ref -> ref.setAttribute("validity", validity).setAttribute("rank", rank)
			);
		}
		product.upsertVia(session);
	}

	/**
	 * Writes the `d6` shape: product 1 references brand 1 with the representative value {@link ReleaseFixtureValues#U1}
	 * and product 2 with {@link ReleaseFixtureValues#U2} — two reduced indexes whose keys were distinct in the
	 * release and are equal today. Shared by {@link #d6c()} and the 2026.2 recipe `d6`.
	 *
	 * @param session the warming-up session
	 */
	static void writeRepresentativeOpenBoundTwins(@Nonnull EvitaSessionContract session) {
		defineRepresentativeReference(session);
		upsertProductReferencingBrandOne(session, 1, U1);
		upsertProductReferencingBrandOne(session, 2, U2);
	}

	/**
	 * Fixture `d6c`: the shape of {@link #writeRepresentativeOpenBoundTwins(EvitaSessionContract)} written by 2026.1
	 * and left at storage protocol 4, so the current engine runs every storage migration over it in one boot.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe d6c() {
		return ReleaseFixtureRecipe.writtenBy2026_1(
				D6C,
				"two partitions of a duplicate reference keyed by open-bound DateTimeRange twins, written by 2026.1",
				Release2026_1FixtureRecipes::writeRepresentativeOpenBoundTwins
			)
			.withProbe(
				"brand 1",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, entityPrimaryKeyInSet(1))))
			);
	}

	/**
	 * Fixture `d12`: a sort-only localized `label` whose spellings `"a<ZWSP>b"`, `"ab"`, `"ab"` are inserted in this
	 * order, plus five other values. The 2026.1 sort writer keys the cardinality section by the inserting record's
	 * spelling and emits it in hash order; 2026.2 opens the catalog without touching the index.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe d12() {
		return localizedSortTwins(D12, ZWSP, "ab");
	}

	/**
	 * Fixture `d12b`: the spellings `"ab"`, `"a<ZWSP>b"`, `"ab"` in this order.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe d12b() {
		return localizedSortTwins(D12B, "ab", ZWSP);
	}

	/**
	 * Fixture `d13`: a filterable `BigDecimalNumberRange` with zero indexed decimal places holding 300 whole-number
	 * ranges and 32 ranges with one decimal place — among them `[1.2, 9]` and `[1.4, 2]` — written by 2026.1, whose
	 * keys are raw and compared at each value's own precision. 2026.2 opens the catalog and adds one entity,
	 * re-flushing the paged FILTER with the raw keys kept.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe d13() {
		return ReleaseFixtureRecipe.writtenBy2026_1(
				D13,
				"paged BigDecimalNumberRange FILTER with raw 2026.1 keys that are not monotone under rescaling",
				session -> {
					defineRangeSchema(session, "range");
					for (int i = 1; i <= 300; i++) {
						session.createNewEntity(PRODUCT, i)
							.setAttribute("range", range(String.valueOf(i * 10), String.valueOf(i * 10 + 5)))
							.upsertVia(session);
					}
					session.createNewEntity(PRODUCT, 301).setAttribute("range", range("1.2", "9")).upsertVia(session);
					session.createNewEntity(PRODUCT, 302).setAttribute("range", range("1.4", "2")).upsertVia(session);
					for (int j = 1; j <= 30; j++) {
						session.createNewEntity(PRODUCT, 302 + j)
							.setAttribute("range", range(j + ".5", (j + 3) + ".5"))
							.upsertVia(session);
					}
				}
			)
			.thenOpenedBy2026_2(
				session -> session.createNewEntity(PRODUCT, 400)
					.setAttribute("range", range("5000", "5005"))
					.upsertVia(session)
			)
			.withProbe(
				"inRange 1.5",
				query(collection(PRODUCT), filterBy(attributeInRange("range", new BigDecimal("1.5"))))
			)
			.withProbe(
				"inRange 3",
				query(collection(PRODUCT), filterBy(attributeInRange("range", new BigDecimal("3"))))
			)
			.withProbe(
				"inRange 12",
				query(collection(PRODUCT), filterBy(attributeInRange("range", new BigDecimal("12"))))
			);
	}

	/**
	 * Fixture `d15`: a filterable `BigDecimalNumberRange` `size` with zero indexed decimal places; entity 1 holds
	 * `[12, 90]` and entity 2 `[1.2, 9]`, which 2026.1 considered equal and folded into one inline FILTER key. 2026.2
	 * opens the catalog and changes another entity.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe d15() {
		return ReleaseFixtureRecipe.writtenBy2026_1(
				D15,
				"inline BigDecimalNumberRange FILTER in which 2026.1 folded [12, 90] and [1.2, 9] into one key",
				session -> {
					defineRangeSchema(session, "size");
					session.createNewEntity(PRODUCT, 1).setAttribute("size", range("12", "90")).upsertVia(session);
					session.createNewEntity(PRODUCT, 2).setAttribute("size", range("1.2", "9")).upsertVia(session);
					session.createNewEntity(PRODUCT, 3).setAttribute("size", range("100", "200")).upsertVia(session);
				}
			)
			.thenOpenedBy2026_2(
				session -> session.getEntity(PRODUCT, 3, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setAttribute("size", range("100", "300"))
					.upsertVia(session)
			)
			.withProbe("inRange 5", query(collection(PRODUCT), filterBy(attributeInRange("size", new BigDecimal("5")))))
			.withProbe(
				"inRange 50",
				query(collection(PRODUCT), filterBy(attributeInRange("size", new BigDecimal("50"))))
			);
	}

	/**
	 * Builds a localized sort recipe: products 1 to 3 hold `first`, `second` and `"ab"` as their English `label`,
	 * products 4 to 8 five other values.
	 *
	 * @param name   the fixture name
	 * @param first  the spelling of product 1
	 * @param second the spelling of product 2
	 * @return the recipe
	 */
	@Nonnull
	private static ReleaseFixtureRecipe localizedSortTwins(
		@Nonnull String name,
		@Nonnull String first,
		@Nonnull String second
	) {
		final String[] labels = {first, second, "ab", "aa", "ac", "b", "c", "d"};
		return ReleaseFixtureRecipe.writtenBy2026_1(
				name,
				"sort-only localized String with collator-equal spellings, " +
					"cardinalities keyed by the inserting spelling",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("label", String.class, whichIs -> whichIs.localized().sortable())
						.updateVia(session);
					for (int i = 0; i < labels.length; i++) {
						session.createNewEntity(PRODUCT, i + 1)
							.setAttribute("label", Locale.ENGLISH, labels[i])
							.upsertVia(session);
					}
				}
			)
			.thenOpenedBy2026_2()
			.withProbe(
				"order by label",
				query(
					collection(PRODUCT),
					filterBy(entityLocaleEquals(Locale.ENGLISH)),
					orderBy(attributeNatural("label"))
				)
			);
	}

	/**
	 * Defines `product` with one filterable `BigDecimalNumberRange` attribute indexed with zero decimal places.
	 *
	 * @param session       the session
	 * @param attributeName the attribute name
	 */
	private static void defineRangeSchema(@Nonnull EvitaSessionContract session, @Nonnull String attributeName) {
		session.defineEntitySchema(PRODUCT)
			.withAttribute(
				attributeName, BigDecimalNumberRange.class, whichIs -> whichIs.filterable().indexDecimalPlaces(0)
			)
			.updateVia(session);
	}

	/**
	 * Creates a closed `BigDecimalNumberRange` from two decimal literals, keeping their scale.
	 *
	 * @param from the lower bound
	 * @param to   the upper bound
	 * @return the range
	 */
	@Nonnull
	static BigDecimalNumberRange range(@Nonnull String from, @Nonnull String to) {
		return BigDecimalNumberRange.between(new BigDecimal(from), new BigDecimal(to));
	}

}
