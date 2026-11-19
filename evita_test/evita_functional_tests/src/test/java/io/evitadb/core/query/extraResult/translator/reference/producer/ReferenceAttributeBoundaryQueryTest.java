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

package io.evitadb.core.query.extraResult.translator.reference.producer;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public-query checks of the preselected alternative, loadability and fetch tuples of sibling reference histograms. */
@DisplayName("Reference attribute boundary entities through public queries")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
class ReferenceAttributeBoundaryQueryTest implements EvitaTestSupport {
	/**
	 * Entity states that distinguish selection from materialization and forbid searching for a third candidate. Each
	 * constant names the state of the brands, not the expected result.
	 *
	 * Owner 100 is the only owner the histogram counts, and it references brands 9, 10 and 12. At the minimum bound
	 * the pick is therefore brand 9, the lowest brand it references there, and the alternative is brand 1, the lowest
	 * candidate of all, referenced only by the uncounted owner 200. Brand 10 is an owned, loadable candidate that must
	 * never stand in for either of them.
	 */
	private enum LoadCase {
		/** Brand 1 does not exist; the pick, brand 9, loads. */
		ALTERNATIVE_MISSING,
		/** Brand 9 does not exist; the alternative, brand 1, loads. */
		PRIMARY_MISSING,
		/** Brand 9 carries only a German name, so it lacks the query's English locale; brand 1 loads. */
		PRIMARY_WITHOUT_LOCALE,
		/** Brand 9 is archived, outside the live scope the query asks for; brand 1 loads. */
		PRIMARY_ARCHIVED,
		/** Neither brand 1 nor brand 9 exists. */
		BOTH_MISSING
	}

	/** Runs each loadability case with identical and different boundary-content requests on the same PKs. */
	@ParameterizedTest
	@EnumSource(LoadCase.class)
	@DisplayName("should resolve only the preselected pick or its alternative, with equal or different fetches")
	void shouldResolveOnlyTheChosenPairThroughPublicQueries(@Nonnull LoadCase loadCase) {
		final TestPaths paths = createTestPaths("ReferenceAttributeBoundary_" + loadCase);
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUp(session, loadCase);
			});
			// one catalog per case serves both fetch variants, so each assertion names the variant it checks
			for (final boolean sameFetch : new boolean[]{true, false}) {
				final String variant = loadCase + (sameFetch ? " with equal fetches" : " with different fetches");
				evita.queryCatalog(TEST_CATALOG, session -> {
					final EntityFetch firstFetch = entityFetch(attributeContent("code"));
					final EntityFetch secondFetch = entityFetch(attributeContent(sameFetch ? "code" : "weight"));
					final EvitaResponse<EntityReference> response = session.query(
						query(
							collection("owner"),
							filterBy(entityPrimaryKeyInSet(100), scope(Scope.LIVE), entityLocaleEquals(Locale.ENGLISH)),
							require(referenceSummaryOfReferenceWithHistograms(
								"brand", FacetStatisticsDepth.COUNTS, (EntityFetch) null, null,
								histogramStatistics(10, firstFetch, "shares"),
								histogramStatistics(10, secondFetch, "sameShares")
							))
						), EntityReference.class
					);
					final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary, variant);
					final ReferenceSummary.ReferenceGroupStatistics group =
						summary.getReferenceGroupStatistics("brand", 1);
					assertNotNull(group, variant);
					final HistogramContract first = group.getHistogramStatistics("shares");
					final HistogramContract second = group.getHistogramStatistics("sameShares");
					assertNotNull(first, variant);
					assertNotNull(second, variant);
					assertEquals(0, new BigDecimal("10").compareTo(first.getMin()), variant);
					assertEquals(0, new BigDecimal("20").compareTo(first.getMax()), variant);
					assertEquals(2, first.getOverallCount(), variant);
					assertArrayEquals(first.getBuckets(), second.getBuckets(), variant);
					assertEquals(first.getOverallCount(), second.getOverallCount(), variant);
					if (loadCase == LoadCase.BOTH_MISSING) {
						// PK 10 is an owned, loadable candidate. It must not replace either preselected missing pick.
						assertTrue(first.getMinReferencedEntity().isEmpty(), variant);
						assertTrue(first.getMaxReferencedEntity().isEmpty(), variant);
						assertTrue(second.getMinReferencedEntity().isEmpty(), variant);
						assertTrue(second.getMaxReferencedEntity().isEmpty(), variant);
					} else {
						final int expected = loadCase == LoadCase.ALTERNATIVE_MISSING ? 9 : 1;
						final SealedEntity firstMin = first.getMinReferencedEntity().orElseThrow();
						final SealedEntity secondMin = second.getMinReferencedEntity().orElseThrow();
						assertEquals(expected, firstMin.getPrimaryKey(), variant);
						assertEquals(expected, secondMin.getPrimaryKey(), variant);
						assertEquals(12, first.getMaxReferencedEntity().orElseThrow().getPrimaryKey(), variant);
						assertEquals(12, second.getMaxReferencedEntity().orElseThrow().getPrimaryKey(), variant);
						assertEquals("brand-" + expected, firstMin.getAttribute("code"), variant);
						if (sameFetch) {
							assertSame(firstMin, secondMin, variant);
							assertSame(first.getMaxReferencedEntity().orElseThrow(),
								second.getMaxReferencedEntity().orElseThrow(), variant);
						} else {
							assertNotSame(firstMin, secondMin, variant);
							assertEquals(Integer.valueOf(expected), secondMin.getAttribute("weight"), variant);
						}
					}
					return null;
				});
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** Defines only validator-accepted histogram expressions and creates both reduced indexes before adding values. */
	private static void setUp(@Nonnull EvitaSessionContract session, @Nonnull LoadCase loadCase) {
		session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute("name", String.class, attribute -> attribute.localized().nullable())
			.withAttribute("code", String.class, attribute -> attribute.nullable())
			.withAttribute("weight", Integer.class, attribute -> attribute.nullable()).updateVia(session);
		session.defineEntitySchema("group").withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema("owner").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
			.withAttribute("name", String.class, attribute -> attribute.localized().nullable())
			.withAttribute("status", String.class, attribute -> attribute.filterable())
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, reference -> reference
				.withGroupTypeRelatedToEntity("group").indexedForFiltering().faceted()
				.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY,
					ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
				.withAttribute("rank", Integer.class, attribute -> attribute.filterable().representative())
				.withAttribute("share", Integer.class, attribute -> attribute.filterable().nullable())
				.bucketedPartially(ExpressionFactory.parse("$entity.attributes['status'] == 'A'"))
				.bucketed("shares", ExpressionFactory.parse("$reference.attributes['share']"))
				.bucketed("sameShares", ExpressionFactory.parse("$reference.attributes['share']")))
			.updateVia(session);
		session.upsertEntity(session.createNewEntity("group", 1));
		for (final int pk : new int[]{1, 9, 10, 12}) {
			if ((pk == 1 && (loadCase == LoadCase.ALTERNATIVE_MISSING || loadCase == LoadCase.BOTH_MISSING)) ||
				(pk == 9 && (loadCase == LoadCase.PRIMARY_MISSING || loadCase == LoadCase.BOTH_MISSING))) {
				continue;
			}
			final EntityBuilder brand = session.createNewEntity("brand", pk)
				.setAttribute("code", "brand-" + pk).setAttribute("weight", pk)
				.setAttribute("name", pk == 9 && loadCase == LoadCase.PRIMARY_WITHOUT_LOCALE ?
					Locale.GERMAN : Locale.ENGLISH, "brand-" + pk);
			if (pk == 9 && loadCase == LoadCase.PRIMARY_ARCHIVED) {
				brand.setScope(Scope.ARCHIVED);
			}
			session.upsertEntity(brand);
		}
		for (final int owner : new int[]{200, 100}) {
			final EntityBuilder entity = session.createNewEntity("owner", owner)
				.setAttribute("name", Locale.ENGLISH, "owner-" + owner)
				.setAttribute("status", owner == 100 ? "A" : "B");
			if (owner == 200) {
				reference(entity, 1, 1, null);
			} else {
				reference(entity, 9, 2, null);
				reference(entity, 10, 2, null);
				reference(entity, 12, 2, null);
			}
			session.upsertEntity(entity);
		}
		for (final int owner : new int[]{200, 100}) {
			final EntityBuilder entity = session.getEntity("owner", owner, entityFetchAllContent())
				.orElseThrow().openForWrite();
			if (owner == 200) {
				reference(entity, 1, 1, 10);
			} else {
				reference(entity, 9, 2, 10);
				reference(entity, 10, 2, 10);
				reference(entity, 12, 2, 20);
			}
			session.upsertEntity(entity);
		}
	}

	/** Creates or updates the only reference to this brand, preserving its representative rank. */
	private static void reference(@Nonnull EntityBuilder owner, int pk, int rank, @Nullable Integer share) {
		owner.setOrUpdateReference("brand", pk, reference -> true, reference -> {
			reference.setGroup("group", 1).setAttribute("rank", rank);
			if (share != null) {
				reference.setAttribute("share", share);
			}
		});
	}
}
