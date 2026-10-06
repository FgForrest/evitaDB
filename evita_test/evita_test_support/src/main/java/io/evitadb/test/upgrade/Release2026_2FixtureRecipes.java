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
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static io.evitadb.test.upgrade.Release2026_1FixtureRecipes.range;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.*;

/**
 * Recipes of the release fixtures that the v2026.2.18 engine writes, for attribute, sort, price and multi-collection
 * shapes; {@link Release2026_2ReferenceFixtureRecipes} and {@link Release2026_2UniqueFixtureRecipes} hold the
 * reference and unique shapes. {@link #all()} lists every 2026.2 fixture. This class compiles against the 2026.2 API
 * and the current one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class Release2026_2FixtureRecipes {

	/**
	 * The entity type almost every fixture stores its data in.
	 */
	public static final String PRODUCT = "product";
	/**
	 * The referenced entity type of the reference fixtures.
	 */
	public static final String BRAND = "brand";
	/**
	 * The referenced group type of the reference fixtures.
	 */
	public static final String GROUP = "group";
	/**
	 * The price list of the price fixtures.
	 */
	public static final String PRICE_LIST = "basic";
	/**
	 * The currency of the price fixtures.
	 */
	public static final Currency EUR = Currency.getInstance("EUR");

	private Release2026_2FixtureRecipes() {
		// recipes only
	}

	/**
	 * Returns the recipes of every fixture the v2026.2.18 engine writes.
	 *
	 * @return the recipes
	 */
	@Nonnull
	public static List<ReleaseFixtureRecipe> all() {
		final List<ReleaseFixtureRecipe> recipes = new ArrayList<>(64);
		recipes.add(referenceAttributeOpenBoundTwins());
		recipes.add(sortOpenBoundTwins());
		recipes.add(sortOpenBoundTwinsSingleFirst());
		recipes.add(filterOpenBoundTwins());
		recipes.add(filterFractionalSecondBounds());
		recipes.add(sortFractionalSecondBounds());
		recipes.add(sortFractionalSecondBoundsPaged());
		recipes.add(sortLocalizedCollatorTwins());
		recipes.add(localizedCompoundSort());
		recipes.add(localizedTemporalCompoundSort());
		recipes.add(filterLocalizedCollatorTwins());
		recipes.add(localizedCompoundSortPaged());
		recipes.add(filterWholeSecondEqualRanges());
		recipes.add(sortWholeSecondEqualRanges());
		recipes.add(filterSubMillisecondMomentsPaged());
		recipes.add(sortSubMillisecondMomentsPaged());
		recipes.add(twoCollectionsRangeShapes());
		recipes.add(healthyTemporalAndLocalizedSort());
		recipes.add(healthyDecimalRangeFilter());
		recipes.add(priceValidityExtremeBounds());
		recipes.add(priceValidityFractionalSecondBounds());
		recipes.add(removedValuesTombstones());
		recipes.addAll(Release2026_2ReferenceFixtureRecipes.all());
		recipes.addAll(Release2026_2UniqueFixtureRecipes.all());
		return recipes;
	}

	/**
	 * The referenced-type index counters of a filterable `DateTimeRange` reference attribute holding the
	 * open-bound twins {@link ReleaseFixtureValues#U1} (product 1) and {@link ReleaseFixtureValues#U2} (product 2),
	 * both referencing brand 1.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe referenceAttributeOpenBoundTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"reference-attribute-open-bound-twins",
				"referenced-type counters of a DateTimeRange reference attribute holding open-bound twins",
				session -> {
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFiltering()
								.withAttribute("validity", DateTimeRange.class, thatIs -> thatIs.filterable())
						)
						.updateVia(session);
					session.createNewEntity(BRAND, 1).upsertVia(session);
					session.createNewEntity(PRODUCT, 1)
						.setReference(BRAND, 1, ref -> ref.setAttribute("validity", U1))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setReference(BRAND, 1, ref -> ref.setAttribute("validity", U2))
						.upsertVia(session);
				}
			)
			.withProbe(
				"inRange mid-2025",
				query(
					collection(PRODUCT),
					filterBy(referenceHaving(BRAND, attributeInRange("validity", BASE.minusMonths(7))))
				)
			)
			.withProbe(
				"equals U2",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeEquals("validity", U2))))
			);
	}

	/**
	 * The owner SORT of a sort-only `DateTimeRange` holding the open-bound twins in blocks of two
	 * ({@link ReleaseFixtureValues#U1}) and three ({@link ReleaseFixtureValues#U2}).
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe sortOpenBoundTwins() {
		return sortOnlyDateTimeRanges(
			"sort-open-bound-twins",
			"sort-only DateTimeRange holding open-bound twins in blocks of 2 and 3",
			U1, U1, U2, U2, U2
		);
	}

	/**
	 * The open-bound twins in a sort-only `DateTimeRange` once more: the twin the release orders first
	 * ({@link ReleaseFixtureValues#U2}) has one record, the other ({@link ReleaseFixtureValues#U1}) three.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe sortOpenBoundTwinsSingleFirst() {
		return sortOnlyDateTimeRanges(
			"sort-open-bound-twins-single-first",
			"sort-only DateTimeRange: a cardinality-1 twin before its cardinality-3 twin",
			U2, U1, U1, U1
		);
	}

	/**
	 * The FILTER buckets of a filterable `DateTimeRange` holding both open-bound twins and one unrelated value.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe filterOpenBoundTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"filter-open-bound-twins",
				"filterable DateTimeRange FILTER holding open-bound twins in two buckets",
				session -> {
					defineFilterableValidity(session);
					setValidity(session, 1, U1);
					setValidity(session, 2, U2);
					setValidity(session, 3, FAR);
				}
			)
			.withProbe("inRange mid-2025", inRange("validity", BASE.minusMonths(7)));
	}

	/**
	 * Fractional-second range endpoints in a filterable scalar and array `DateTimeRange`, whose release thresholds
	 * are whole seconds.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe filterFractionalSecondBounds() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"filter-fractional-second-bounds",
				"fractional-second DateTimeRange endpoints, scalar and array, with whole-second release thresholds",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
						.withAttribute("validities", DateTimeRange[].class, whichIs -> whichIs.filterable())
						.updateVia(session);
					session.createNewEntity(PRODUCT, 1)
						.setAttribute("validity", A)
						.setAttribute("validities", new DateTimeRange[]{A})
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setAttribute("validity", FAR)
						.setAttribute("validities", new DateTimeRange[]{FAR})
						.upsertVia(session);
				}
			)
			.withProbe("inRange +0.500", inRange("validity", BASE.plusNanos(500_000_000L)))
			.withProbe("inRange +2.500", inRange("validity", BASE.plusSeconds(2).plusNanos(500_000_000L)))
			.withProbe("validities inRange +2.500", inRange("validities", BASE.plusSeconds(2).plusNanos(500_000_000L)));
	}

	/**
	 * The owner SORT of a sort-only `DateTimeRange` in which the release ordered A before B (whole seconds), while
	 * today B sorts first.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe sortFractionalSecondBounds() {
		return sortOnlyDateTimeRanges(
			"sort-fractional-second-bounds", "sort-only DateTimeRange ordered by whole seconds (inline)", A, B, FAR
		);
	}

	/**
	 * 150 pairs of the A-before-B inversion of {@link #sortFractionalSecondBounds()}, so the owner SORT spans several
	 * pages.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe sortFractionalSecondBoundsPaged() {
		final DateTimeRange[] values = new DateTimeRange[300];
		for (int i = 0; i < 150; i++) {
			final OffsetDateTime base = BASE.plusSeconds(10L * i);
			values[2 * i] = DateTimeRange.between(
				base.plusNanos(900_000_000L), base.plusSeconds(2).plusNanos(900_000_000L)
			);
			values[2 * i + 1] = DateTimeRange.between(
				base.plusNanos(100_000_000L), base.plusSeconds(3).plusNanos(100_000_000L)
			);
		}
		return sortOnlyDateTimeRanges(
			"sort-fractional-second-bounds-paged", "sort-only DateTimeRange ordered by whole seconds (paged)", values
		);
	}

	/**
	 * A sort-only localized plain `String` holding the collator-equal spellings `"a<ZWSP>b"` and `"ab"`.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe sortLocalizedCollatorTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"sort-localized-collator-twins",
				"sort-only localized String holding two collator-equal spellings",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("name", String.class, whichIs -> whichIs.localized().sortable())
						.updateVia(session);
					final String[] names = {ZWSP, "ab", "aa", "ac"};
					for (int i = 0; i < names.length; i++) {
						session.createNewEntity(PRODUCT, i + 1)
							.setAttribute("name", Locale.ENGLISH, names[i])
							.upsertVia(session);
					}
				}
			)
			.withProbe("order by name", orderedInEnglish("name"));
	}

	/**
	 * A sortable compound without a temporal element: `(label, code)` with a localized `label`.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe localizedCompoundSort() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"localized-compound-sort",
				"sortable compound (localized label, code) ordered by the collator only",
				session -> {
					defineLabelCodeCompound(session);
					final String[] labels = {ZWSP, "ab", "aa"};
					for (int i = 0; i < labels.length; i++) {
						session.createNewEntity(PRODUCT, i + 1)
							.setAttribute("label", Locale.ENGLISH, labels[i])
							.setAttribute("code", i + 1)
							.upsertVia(session);
					}
				}
			)
			.withProbe("order by labelCode", orderedInEnglish("labelCode"));
	}

	/**
	 * A sortable compound with a temporal element: `(label, moment)` with a localized `label`.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe localizedTemporalCompoundSort() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"localized-temporal-compound-sort",
				"sortable compound (localized label, moment) ordered by the collator only",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("label", String.class, whichIs -> whichIs.localized().sortable())
						.withAttribute("moment", OffsetDateTime.class, whichIs -> whichIs.sortable())
						.withSortableAttributeCompound(
							"labelMoment", attributeElement("label"), attributeElement("moment")
						)
						.updateVia(session);
					session.createNewEntity(PRODUCT, 1)
						.setAttribute("label", Locale.ENGLISH, ZWSP).setAttribute("moment", BASE)
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setAttribute("label", Locale.ENGLISH, "ab").setAttribute("moment", BASE.plusDays(1))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 3)
						.setAttribute("label", Locale.ENGLISH, "aa").setAttribute("moment", BASE)
						.upsertVia(session);
				}
			)
			.withProbe("order by labelMoment", orderedInEnglish("labelMoment"));
	}

	/**
	 * A filterable localized `String` holding both collator-equal spellings, which the release folded into
	 * one FILTER key.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe filterLocalizedCollatorTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"filter-localized-collator-twins",
				"filterable localized String with two collator-equal spellings folded into one key",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("name", String.class, whichIs -> whichIs.localized().filterable())
						.updateVia(session);
					final String[] names = {ZWSP, "ab", "aa"};
					for (int i = 0; i < names.length; i++) {
						session.createNewEntity(PRODUCT, i + 1)
							.setAttribute("name", Locale.ENGLISH, names[i])
							.upsertVia(session);
					}
				}
			)
			.withProbe("equals ab", equalsInEnglish("name", "ab"))
			.withProbe("equals ZWSP", equalsInEnglish("name", ZWSP));
	}

	/**
	 * A paged sortable compound: 320 `(label, code)` entries alternating between `"ab"` and `"a<ZWSP>b"`, so the
	 * collator ties of the release order span page boundaries, plus two other labels.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe localizedCompoundSortPaged() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"localized-compound-sort-paged",
				"paged sortable compound (localized label, code) whose collator ties span page boundaries",
				session -> {
					defineLabelCodeCompound(session);
					for (int pk = 1; pk <= 320; pk++) {
						session.createNewEntity(PRODUCT, pk)
							.setAttribute("label", Locale.ENGLISH, pk % 2 == 0 ? "ab" : ZWSP)
							.setAttribute("code", pk)
							.upsertVia(session);
					}
					session.createNewEntity(PRODUCT, 321)
						.setAttribute("label", Locale.ENGLISH, "aa").setAttribute("code", 1)
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 322)
						.setAttribute("label", Locale.ENGLISH, "ac").setAttribute("code", 1)
						.upsertVia(session);
				}
			)
			.withProbe(
				"order by labelCode",
				query(
					collection(PRODUCT),
					filterBy(entityLocaleEquals(Locale.ENGLISH)),
					orderBy(attributeNatural("labelCode")),
					require(page(1, 400))
				)
			);
	}

	/**
	 * A filterable `DateTimeRange` holding A and C — equal at whole seconds, so the release folded them into one FILTER
	 * key.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe filterWholeSecondEqualRanges() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"filter-whole-second-equal-ranges",
				"filterable DateTimeRange A and C folded into one key at whole seconds",
				session -> {
					defineFilterableValidity(session);
					setValidity(session, 1, A);
					setValidity(session, 2, C);
					setValidity(session, 3, FAR);
				}
			)
			.withProbe("inRange +2.500", inRange("validity", BASE.plusSeconds(2).plusNanos(500_000_000L)));
	}

	/**
	 * A sort-only `DateTimeRange` holding A and C, folded into one owner SORT value at whole seconds.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe sortWholeSecondEqualRanges() {
		return sortOnlyDateTimeRanges(
			"sort-whole-second-equal-ranges",
			"sort-only DateTimeRange A and C folded into one value at whole seconds",
			A, C, FAR
		);
	}

	/**
	 * 320 `OffsetDateTime` values with sub-millisecond digits — pairs 400 µs apart within one millisecond —
	 * in a filterable attribute and in a filterable and sortable sibling. The release kept nanosecond keys; today the
	 * pairs share one millisecond key.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe filterSubMillisecondMomentsPaged() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"filter-sub-millisecond-moments-paged",
				"paged temporal FILTER with sub-millisecond keys colliding in pairs at millisecond precision",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("moment", OffsetDateTime.class, whichIs -> whichIs.filterable())
						.withAttribute("sortedMoment", OffsetDateTime.class, whichIs -> whichIs.filterable().sortable())
						.updateVia(session);
					for (int pk = 1; pk <= 320; pk++) {
						final OffsetDateTime moment = BASE
							.plusNanos(((pk - 1) / 2) * 1_000_000L + ((pk - 1) % 2) * 400_000L + 100_000L);
						session.createNewEntity(PRODUCT, pk)
							.setAttribute("moment", moment)
							.setAttribute("sortedMoment", moment)
							.upsertVia(session);
					}
				}
			)
			.withProbe(
				"moment equals +5ms",
				query(collection(PRODUCT), filterBy(attributeEquals("moment", BASE.plusNanos(5_000_000L))))
			)
			.withProbe(
				"moment between +10ms and +12ms",
				query(
					collection(PRODUCT),
					filterBy(attributeBetween("moment", BASE.plusNanos(10_000_000L), BASE.plusNanos(12_000_000L)))
				)
			)
			.withProbe(
				"sortedMoment equals +100ms",
				query(collection(PRODUCT), filterBy(attributeEquals("sortedMoment", BASE.plusNanos(100_000_000L))))
			)
			.withProbe(
				"order by sortedMoment",
				query(collection(PRODUCT), orderBy(attributeNatural("sortedMoment")), require(page(1, 400)))
			);
	}

	/**
	 * 320 sort-only `OffsetDateTime` values with sub-millisecond digits in pairs within one millisecond, the
	 * earlier instant of each pair at `+01:00` and stored on the higher primary key, and a sortable compound
	 * `(moment, priority)` holding one instant at two offsets (products 401 and 402). The release ordered raw values
	 * (instant, then local time); today values compare by millisecond instant and tie on the primary key.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe sortSubMillisecondMomentsPaged() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"sort-sub-millisecond-moments-paged",
				"paged sort-only temporal values with sub-millisecond digits and one instant at two offsets",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("moment", OffsetDateTime.class, whichIs -> whichIs.sortable())
						.withAttribute("priority", Integer.class, whichIs -> whichIs.sortable())
						.withSortableAttributeCompound(
							"momentPriority", attributeElement("moment"), attributeElement("priority")
						)
						.updateVia(session);
					for (int pk = 1; pk <= 320; pk++) {
						final long millis = (pk - 1) / 2;
						final OffsetDateTime moment = pk % 2 == 1
							? BASE.plusNanos(millis * 1_000_000L + 700_000L)
							: BASE.plusNanos(millis * 1_000_000L + 100_000L)
								.withOffsetSameInstant(ZoneOffset.ofHours(1));
						session.createNewEntity(PRODUCT, pk)
							.setAttribute("moment", moment)
							.setAttribute("priority", 1)
							.upsertVia(session);
					}
					final OffsetDateTime sharedInstant = BASE.plusDays(1);
					session.createNewEntity(PRODUCT, 401)
						.setAttribute("moment", sharedInstant.withOffsetSameInstant(ZoneOffset.ofHours(1)))
						.setAttribute("priority", 5)
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 402)
						.setAttribute("moment", sharedInstant)
						.setAttribute("priority", 5)
						.upsertVia(session);
				}
			)
			.withProbe(
				"order by moment",
				query(collection(PRODUCT), orderBy(attributeNatural("moment")), require(page(1, 400)))
			)
			.withProbe(
				"order by momentPriority",
				query(collection(PRODUCT), orderBy(attributeNatural("momentPriority")), require(page(1, 400)))
			);
	}

	/**
	 * Two collections with affected indexes: `product` with a filterable `DateTimeRange` folding A and C, `category`
	 * with a sort-only `DateTimeRange` holding the A-before-B inversion of
	 * {@link #sortFractionalSecondBounds()}.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe twoCollectionsRangeShapes() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"two-collections-range-shapes",
				"two collections with release DateTimeRange shapes (FILTER fold and SORT inversion)",
				session -> {
					defineFilterableValidity(session);
					setValidity(session, 1, A);
					setValidity(session, 2, C);
					setValidity(session, 3, FAR);
					session.defineEntitySchema("category")
						.withAttribute("sortValidity", DateTimeRange.class, whichIs -> whichIs.sortable())
						.updateVia(session);
					final DateTimeRange[] values = {A, B, FAR};
					for (int i = 0; i < values.length; i++) {
						session.createNewEntity("category", i + 1)
							.setAttribute("sortValidity", values[i])
							.upsertVia(session);
					}
				}
			)
			.withProbe("product inRange +2.500", inRange("validity", BASE.plusSeconds(2).plusNanos(500_000_000L)))
			.withProbe("category order", query(collection("category"), orderBy(attributeNatural("sortValidity"))));
	}

	/**
	 * Healthy view-mode sort parts: a filterable and sortable `OffsetDateTime` with whole-millisecond values and a
	 * filterable and sortable localized `String` without collator ties. Nothing in it needs a repair.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe healthyTemporalAndLocalizedSort() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"healthy-temporal-and-localized-sort",
				"healthy filterable and sortable OffsetDateTime and localized String (view-mode sort parts)",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("moment", OffsetDateTime.class, whichIs -> whichIs.filterable().sortable())
						.withAttribute("name", String.class, whichIs -> whichIs.localized().filterable().sortable())
						.updateVia(session);
					final String[] names = {"delta", "alpha", "charlie", "bravo"};
					for (int i = 0; i < names.length; i++) {
						session.createNewEntity(PRODUCT, i + 1)
							.setAttribute("moment", BASE.plusSeconds(10L * (names.length - i)))
							.setAttribute("name", Locale.ENGLISH, names[i])
							.upsertVia(session);
					}
				}
			)
			.withProbe("order by moment", query(collection(PRODUCT), orderBy(attributeNatural("moment"))))
			.withProbe("order by name", orderedInEnglish("name"))
			.withProbe("name equals bravo", equalsInEnglish("name", "bravo"));
	}

	/**
	 * Healthy `BigDecimalNumberRange` written by 2026.2, which already rescaled range keys to the attribute's indexed
	 * decimal places.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe healthyDecimalRangeFilter() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"healthy-decimal-range-filter",
				"healthy BigDecimalNumberRange FILTER written by 2026.2 at one indexed decimal place",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute(
							"size", BigDecimalNumberRange.class, whichIs -> whichIs.filterable().indexDecimalPlaces(1)
						)
						.updateVia(session);
					final BigDecimalNumberRange[] values = {range("1.25", "9"), range("2", "3"), range("10.5", "20")};
					for (int i = 0; i < values.length; i++) {
						session.createNewEntity(PRODUCT, i + 1).setAttribute("size", values[i]).upsertVia(session);
					}
				}
			)
			.withProbe("inRange 1.3", inRange("size", new BigDecimal("1.3")))
			.withProbe("inRange 2.5", inRange("size", new BigDecimal("2.5")))
			.withProbe("inRange 15", inRange("size", new BigDecimal("15")));
	}

	/**
	 * Price validities with a fractional closed lower bound and an extreme upper bound, and one with both bounds
	 * extreme; the test updates both after the upgrade.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe priceValidityExtremeBounds() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"price-validity-extreme-bounds",
				"price validities with a fractional closed lower bound and closed extreme bounds",
				session -> {
					definePrices(session);
					setPrice(session, 1, DateTimeRange.between(BASE.plusNanos(500_000_000L), OffsetDateTime.MAX));
					setPrice(session, 2, DateTimeRange.between(OffsetDateTime.MIN, OffsetDateTime.MAX));
				}
			)
			.withProbe("validIn base", validIn(BASE))
			.withProbe("validIn base +1 day", validIn(BASE.plusDays(1)));
	}

	/**
	 * Price validity bounds at half a second, whose release thresholds are whole seconds.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe priceValidityFractionalSecondBounds() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"price-validity-fractional-second-bounds",
				"price validity with fractional-second bounds and whole-second release thresholds",
				session -> {
					definePrices(session);
					setPrice(
						session, 1,
						DateTimeRange.between(BASE.plusNanos(500_000_000L), BASE.plusSeconds(2).plusNanos(500_000_000L))
					);
					setPrice(session, 2, DateTimeRange.between(BASE.plusSeconds(10), BASE.plusSeconds(20)));
				}
			)
			.withProbe("validIn +0.200", validIn(BASE.plusNanos(200_000_000L)))
			.withProbe("validIn +1", validIn(BASE.plusSeconds(1)))
			.withProbe("validIn +2.200", validIn(BASE.plusSeconds(2).plusNanos(200_000_000L)));
	}

	/**
	 * Deleted data the release left as tombstones: product 1's only filterable `DateTimeRange` attribute, its reference
	 * attribute `refValidity`, and its `moment` — one element of the sortable compound `(label, moment)` — are removed,
	 * and product 2's reference loses its group, each in its own transaction after the catalog went live.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe removedValuesTombstones() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"removed-values-tombstones",
				"removed attribute, reference attribute, compound element and reference group, kept as tombstones",
				session -> {
					session.defineEntitySchema(GROUP).updateVia(session);
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
						.withAttribute("label", String.class, whichIs -> whichIs.localized().sortable())
						.withAttribute("moment", OffsetDateTime.class, whichIs -> whichIs.sortable().nullable())
						.withSortableAttributeCompound(
							"labelMoment", attributeElement("label"), attributeElement("moment")
						)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.withGroupTypeRelatedToEntity(GROUP)
								.indexedForFilteringAndPartitioning()
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withAttribute(
									"refValidity", DateTimeRange.class, thatIs -> thatIs.filterable().nullable()
								)
						)
						.updateVia(session);
					session.createNewEntity(GROUP, 10).upsertVia(session);
					session.createNewEntity(BRAND, 1).upsertVia(session);
					session.createNewEntity(PRODUCT, 1)
						.setAttribute("validity", A)
						.setAttribute("label", Locale.ENGLISH, "x")
						.setAttribute("moment", BASE)
						.setReference(BRAND, 1, ref -> ref.setGroup(10).setAttribute("refValidity", A))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setAttribute("validity", C)
						.setAttribute("label", Locale.ENGLISH, "y")
						.setAttribute("moment", BASE.plusDays(1))
						.setReference(BRAND, 1, ref -> ref.setGroup(10).setAttribute("refValidity", C))
						.upsertVia(session);
				}
			)
			.thenAlive(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite().removeAttribute("validity").upsertVia(session),
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.updateReference(BRAND, 1, ref -> ref.removeAttribute("refValidity"))
					.upsertVia(session),
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite().removeAttribute("moment").upsertVia(session),
				session -> session.getEntity(PRODUCT, 2, entityFetchAllContent()).orElseThrow()
					.openForWrite().updateReference(BRAND, 1, ref -> ref.removeGroup()).upsertVia(session)
			)
			.withProbe("validity inRange +1", inRange("validity", BASE.plusSeconds(1)))
			.withProbe(
				"refValidity inRange +1",
				query(
					collection(PRODUCT),
					filterBy(referenceHaving(BRAND, attributeInRange("refValidity", BASE.plusSeconds(1))))
				)
			)
			.withProbe("order by labelMoment", orderedInEnglish("labelMoment"))
			.withProbe(
				"group 10",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, groupHaving(entityPrimaryKeyInSet(10)))))
			);
	}

	/**
	 * Builds a recipe of products 1..n holding the given values of a sort-only `DateTimeRange` `sortValidity`.
	 *
	 * @param name   the fixture name
	 * @param shape  the shape description
	 * @param values the value of product `i + 1` at index `i`
	 * @return the recipe
	 */
	@Nonnull
	private static ReleaseFixtureRecipe sortOnlyDateTimeRanges(
		@Nonnull String name,
		@Nonnull String shape,
		@Nonnull DateTimeRange... values
	) {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				name, shape,
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("sortValidity", DateTimeRange.class, whichIs -> whichIs.sortable())
						.updateVia(session);
					for (int i = 0; i < values.length; i++) {
						session.createNewEntity(PRODUCT, i + 1)
							.setAttribute("sortValidity", values[i])
							.upsertVia(session);
					}
				}
			)
			.withProbe(
				"order by sortValidity",
				query(collection(PRODUCT), orderBy(attributeNatural("sortValidity")), require(page(1, 400)))
			);
	}

	/**
	 * Defines `product` with a filterable `DateTimeRange` attribute `validity`.
	 *
	 * @param session the session
	 */
	private static void defineFilterableValidity(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(PRODUCT)
			.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
			.updateVia(session);
	}

	/**
	 * Creates a product holding the given `validity`.
	 *
	 * @param session  the session
	 * @param pk       the product primary key
	 * @param validity the value
	 */
	private static void setValidity(@Nonnull EvitaSessionContract session, int pk, @Nonnull DateTimeRange validity) {
		session.createNewEntity(PRODUCT, pk).setAttribute("validity", validity).upsertVia(session);
	}

	/**
	 * Defines `product` with a localized sortable `label`, a sortable `code` and the compound `labelCode` of both.
	 *
	 * @param session the session
	 */
	private static void defineLabelCodeCompound(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(PRODUCT)
			.withAttribute("label", String.class, whichIs -> whichIs.localized().sortable())
			.withAttribute("code", Integer.class, whichIs -> whichIs.sortable())
			.withSortableAttributeCompound("labelCode", attributeElement("label"), attributeElement("code"))
			.updateVia(session);
	}

	/**
	 * Defines `product` with indexed prices.
	 *
	 * @param session the session
	 */
	private static void definePrices(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
	}

	/**
	 * Creates a product with one indexed price in {@link #PRICE_LIST} and {@link #EUR} valid in `validity`.
	 *
	 * @param session  the session
	 * @param pk       the product primary key, used as the price id too
	 * @param validity the price validity
	 */
	private static void setPrice(@Nonnull EvitaSessionContract session, int pk, @Nonnull DateTimeRange validity) {
		session.createNewEntity(PRODUCT, pk)
			.setPrice(pk, PRICE_LIST, EUR, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, validity, true)
			.upsertVia(session);
	}

	/**
	 * Returns a query for products with a price in {@link #PRICE_LIST} and {@link #EUR} valid at `moment`.
	 *
	 * @param moment the moment
	 * @return the query
	 */
	@Nonnull
	public static Query validIn(@Nonnull OffsetDateTime moment) {
		return query(
			collection(PRODUCT),
			filterBy(priceInCurrency(EUR), priceInPriceLists(PRICE_LIST), priceValidIn(moment))
		);
	}

	/**
	 * Returns a query for products whose range attribute contains the value.
	 *
	 * @param attributeName the attribute
	 * @param value         the value
	 * @return the query
	 */
	@Nonnull
	public static Query inRange(@Nonnull String attributeName, @Nonnull OffsetDateTime value) {
		return query(collection(PRODUCT), filterBy(attributeInRange(attributeName, value)));
	}

	/**
	 * Returns a query for products whose numeric range attribute contains the value.
	 *
	 * @param attributeName the attribute
	 * @param value         the value
	 * @return the query
	 */
	@Nonnull
	public static Query inRange(@Nonnull String attributeName, @Nonnull BigDecimal value) {
		return query(collection(PRODUCT), filterBy(attributeInRange(attributeName, value)));
	}

	/**
	 * Returns a query for English products ordered by the attribute or compound.
	 *
	 * @param name the attribute or compound
	 * @return the query
	 */
	@Nonnull
	public static Query orderedInEnglish(@Nonnull String name) {
		return query(
			collection(PRODUCT),
			filterBy(entityLocaleEquals(Locale.ENGLISH)),
			orderBy(attributeNatural(name)),
			require(page(1, 400))
		);
	}

	/**
	 * Returns a query for English products whose attribute equals the value.
	 *
	 * @param attributeName the attribute
	 * @param value         the value
	 * @return the query
	 */
	@Nonnull
	public static Query equalsInEnglish(@Nonnull String attributeName, @Nonnull Serializable value) {
		return query(
			collection(PRODUCT),
			filterBy(entityLocaleEquals(Locale.ENGLISH), attributeEquals(attributeName, value))
		);
	}

}
