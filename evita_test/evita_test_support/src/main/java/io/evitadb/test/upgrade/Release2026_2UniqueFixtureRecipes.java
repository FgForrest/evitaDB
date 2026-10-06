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
import io.evitadb.dataType.DateTimeRange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.PRODUCT;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.*;

/**
 * Recipes of the release fixtures with unique shapes that the v2026.2.18 engine writes: globally unique trees, the
 * standalone unique tree of a localized attribute unique across locales, and unique values folded into the FILTER
 * tree. This class compiles against the 2026.2 API and the current one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class Release2026_2UniqueFixtureRecipes {

	/**
	 * How many entities the paged unique fixtures hold besides the two inverted values.
	 */
	private static final int PAGED_FILLER = 298;

	private Release2026_2UniqueFixtureRecipes() {
		// recipes only
	}

	/**
	 * Returns the recipes of every unique fixture the v2026.2.18 engine writes.
	 *
	 * @return the recipes
	 */
	@Nonnull
	public static List<ReleaseFixtureRecipe> all() {
		return Arrays.asList(
			uniqueGlobalRangePaged(),
			uniqueLocalizedRangePaged(),
			uniqueGlobalOpenBoundTwins(),
			uniqueGlobalArrayOpenBoundTwins(),
			uniqueFoldedOpenBoundTwins(),
			uniqueFoldedSubMillisecondMoments(),
			healthyUniqueGlobalRange()
		);
	}

	/**
	 * A globally unique `DateTimeRange` `period` on 300 entities, so the catalog-level unique tree is paged; the
	 * last two hold A and then B — adjacent in one leaf, ordered A before B by the release and B before A today.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe uniqueGlobalRangePaged() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"unique-global-range-paged",
				"paged globally unique DateTimeRange tree whose release order inverts at millisecond precision",
				session -> {
					defineGlobalPeriod(session, false);
					for (int pk = 1; pk <= PAGED_FILLER; pk++) {
						session.createNewEntity(PRODUCT, pk).setAttribute("period", filler(pk)).upsertVia(session);
					}
					session.createNewEntity(PRODUCT, PAGED_FILLER + 1).setAttribute("period", A).upsertVia(session);
					session.createNewEntity(PRODUCT, PAGED_FILLER + 2).setAttribute("period", B).upsertVia(session);
				}
			)
			.withProbe("equals A", periodEquals(A, null))
			.withProbe("equals B", periodEquals(B, null));
	}

	/**
	 * A localized `DateTimeRange` `period` unique across locales — stored in a standalone owner
	 * unique tree — on 300 entities, the last two holding A and then B.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe uniqueLocalizedRangePaged() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"unique-localized-range-paged",
				"paged standalone owner unique DateTimeRange tree whose release order inverts at millisecond precision",
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("period", DateTimeRange.class, whichIs -> whichIs.localized().unique())
						.updateVia(session);
					for (int pk = 1; pk <= PAGED_FILLER; pk++) {
						session.createNewEntity(PRODUCT, pk)
							.setAttribute("period", Locale.ENGLISH, filler(pk))
							.upsertVia(session);
					}
					session.createNewEntity(PRODUCT, PAGED_FILLER + 1)
						.setAttribute("period", Locale.ENGLISH, A)
						.upsertVia(session);
					session.createNewEntity(PRODUCT, PAGED_FILLER + 2)
						.setAttribute("period", Locale.ENGLISH, B)
						.upsertVia(session);
				}
			)
			.withProbe("equals A", periodEquals(A, Locale.ENGLISH))
			.withProbe("equals B", periodEquals(B, Locale.ENGLISH));
	}

	/**
	 * A globally unique `DateTimeRange` `period`; entity 1 holds {@link ReleaseFixtureValues#U1}, entity 2
	 * {@link ReleaseFixtureValues#U2} — two owners the release told apart and that are equal today.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe uniqueGlobalOpenBoundTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"unique-global-open-bound-twins",
				"globally unique DateTimeRange tree holding open-bound twins of two owners",
				session -> {
					defineGlobalPeriod(session, false);
					session.createNewEntity(PRODUCT, 1).setAttribute("period", U1).upsertVia(session);
					session.createNewEntity(PRODUCT, 2).setAttribute("period", U2).upsertVia(session);
				}
			)
			.withProbe("equals U1", periodEquals(U1, null))
			.withProbe("equals U2", periodEquals(U2, null));
	}

	/**
	 * A globally unique `DateTimeRange[]` `periods`; entity 1 holds both open-bound twins.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe uniqueGlobalArrayOpenBoundTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"unique-global-array-open-bound-twins",
				"globally unique DateTimeRange array tree holding open-bound twins of one owner",
				session -> {
					defineGlobalPeriod(session, true);
					session.createNewEntity(PRODUCT, 1)
						.setAttribute("periods", new DateTimeRange[]{U1, U2})
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setAttribute("periods", new DateTimeRange[]{FAR})
						.upsertVia(session);
				}
			)
			.withProbe("equals U1", query(collection(PRODUCT), filterBy(attributeEquals("periods", U1))))
			.withProbe("equals FAR", query(collection(PRODUCT), filterBy(attributeEquals("periods", FAR))));
	}

	/**
	 * A non-localized unique `DateTimeRange` `period` — enforced in the FILTER tree it is folded into; entity 1
	 * holds {@link ReleaseFixtureValues#U1}, entity 2 {@link ReleaseFixtureValues#U2}.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe uniqueFoldedOpenBoundTwins() {
		return foldedUnique(
			"unique-folded-open-bound-twins", "folded unique DateTimeRange holding open-bound twins of two owners",
			DateTimeRange.class, U1, U2
		);
	}

	/**
	 * A unique `OffsetDateTime` `period`; entities 1 and 2 hold moments 400 µs apart within one millisecond.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe uniqueFoldedSubMillisecondMoments() {
		return foldedUnique(
			"unique-folded-sub-millisecond-moments",
			"folded unique OffsetDateTime holding two owners' moments within one millisecond",
			OffsetDateTime.class, BASE.plusNanos(100_000L), BASE.plusNanos(500_000L)
		);
	}

	/**
	 * Healthy: a globally unique `DateTimeRange` `period` with whole-second closed values. Nothing in it needs a
	 * repair.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe healthyUniqueGlobalRange() {
		final DateTimeRange first = DateTimeRange.between(BASE, BASE.plusDays(1));
		final DateTimeRange second = DateTimeRange.between(BASE.plusDays(2), BASE.plusDays(3));
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"healthy-unique-global-range",
				"healthy globally unique DateTimeRange tree with whole-second closed values",
				session -> {
					defineGlobalPeriod(session, false);
					session.createNewEntity(PRODUCT, 1).setAttribute("period", first).upsertVia(session);
					session.createNewEntity(PRODUCT, 2).setAttribute("period", second).upsertVia(session);
				}
			)
			.withProbe("equals first", periodEquals(first, null))
			.withProbe("equals second", periodEquals(second, null))
			.withProbe(
				"inRange +1 hour",
				query(collection(PRODUCT), filterBy(attributeInRange("period", BASE.plusHours(1))))
			);
	}

	/**
	 * Builds a folded-unique recipe: `product.period` of the given type is unique (and not localized), entity 1 holds
	 * `first` and entity 2 `second`.
	 *
	 * @param name   the fixture name
	 * @param shape  the shape description
	 * @param type   the attribute type
	 * @param first  the value of entity 1
	 * @param second the value of entity 2
	 * @return the recipe
	 */
	@Nonnull
	private static ReleaseFixtureRecipe foldedUnique(
		@Nonnull String name,
		@Nonnull String shape,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull Serializable first,
		@Nonnull Serializable second
	) {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				name, shape,
				session -> {
					session.defineEntitySchema(PRODUCT)
						.withAttribute("period", type, whichIs -> whichIs.unique())
						.updateVia(session);
					session.createNewEntity(PRODUCT, 1).setAttribute("period", first).upsertVia(session);
					session.createNewEntity(PRODUCT, 2).setAttribute("period", second).upsertVia(session);
				}
			)
			.withProbe("equals first", query(collection(PRODUCT), filterBy(attributeEquals("period", first))))
			.withProbe("equals second", query(collection(PRODUCT), filterBy(attributeEquals("period", second))));
	}

	/**
	 * Defines the catalog-level globally unique `DateTimeRange` attribute and `product` using it.
	 *
	 * @param session the session
	 * @param array   whether the attribute is an array `periods` rather than a scalar `period`
	 */
	private static void defineGlobalPeriod(@Nonnull EvitaSessionContract session, boolean array) {
		final String name = array ? "periods" : "period";
		session.getCatalogSchema().openForWrite()
			.withAttribute(
				name, array ? DateTimeRange[].class : DateTimeRange.class, whichIs -> whichIs.uniqueGlobally()
			)
			.updateVia(session);
		session.defineEntitySchema(PRODUCT).withGlobalAttribute(name).updateVia(session);
	}

	/**
	 * Returns a whole-second range, ten seconds wide, distinct for every primary key and later than every other value
	 * of the paged unique fixtures.
	 *
	 * @param pk the primary key
	 * @return the range
	 */
	@Nonnull
	private static DateTimeRange filler(int pk) {
		final OffsetDateTime from = BASE.plusSeconds(100L + 20L * pk);
		return DateTimeRange.between(from, from.plusSeconds(10));
	}

	/**
	 * Returns a query for products whose `period` equals the value, in the locale when given.
	 *
	 * @param value  the value
	 * @param locale the locale of a localized attribute, or `null`
	 * @return the query
	 */
	@Nonnull
	private static Query periodEquals(@Nonnull DateTimeRange value, @Nullable Locale locale) {
		return locale == null
			? query(collection(PRODUCT), filterBy(attributeEquals("period", value)))
			: query(collection(PRODUCT), filterBy(entityLocaleEquals(locale), attributeEquals("period", value)));
	}

}
