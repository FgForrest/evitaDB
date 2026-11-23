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

package io.evitadb.api.functional.histogram;

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.dataType.Scope.LIVE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises histograms of reference attributes when the target type has no collection in the catalog.
 * Two products contribute distinct values and reference distinct external primary keys.
 */
@DisplayName("Reference attribute histograms for unmanaged targets")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
@ExtendWith(EvitaParameterResolver.class)
class UnmanagedReferenceHistogramBoundaryFunctionalTest implements EvitaTestSupport {
	private static final String UNGROUPED_FIXTURE = "unmanagedReferenceHistogramUngrouped";
	private static final String GROUPED_FIXTURE = "unmanagedReferenceHistogramGrouped";
	private static final String NOT_FACETED_FIXTURE = "unmanagedReferenceHistogramNotFaceted";
	private static final String PRODUCT = "Product";
	private static final String TARGET = "ExternalParameter";
	private static final String GROUP = "ParameterGroup";
	private static final String REFERENCE_NAME = "externalParameter";
	private static final String SHARE = "share";
	private static final String SHARE_HISTOGRAM = "shareHistogram";
	private static final int GROUP_PK = 20;

	/**
	 * Creates the ungrouped faceted reference fixture.
	 *
	 * @param evita engine in which to create the fixture
	 */
	@DataSet(value = UNGROUPED_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpUngroupedFixture(@Nonnull Evita evita) {
		setUpFixture(evita, false, true);
	}

	/**
	 * Creates the faceted reference fixture with a managed group and unmanaged targets.
	 *
	 * @param evita engine in which to create the fixture
	 */
	@DataSet(value = GROUPED_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpGroupedFixture(@Nonnull Evita evita) {
		setUpFixture(evita, true, true);
	}

	/**
	 * Creates the ungrouped reference fixture whose reference is not faceted.
	 *
	 * @param evita engine in which to create the fixture
	 */
	@DataSet(value = NOT_FACETED_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpNotFacetedFixture(@Nonnull Evita evita) {
		setUpFixture(evita, false, false);
	}

	/**
	 * Persists the schema and inserts two owners of the reference.
	 *
	 * @param evita engine in which to create the fixture
	 * @param grouped whether to create a managed reference group
	 * @param faceted whether the reference is faceted
	 */
	private static void setUpFixture(@Nonnull Evita evita, boolean grouped, boolean faceted) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			if (grouped) {
				session.defineEntitySchema(GROUP).withoutGeneratedPrimaryKey().updateVia(session);
				session.createNewEntity(GROUP, GROUP_PK).upsertVia(session);
			}
			session.defineEntitySchema(PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(REFERENCE_NAME, TARGET, Cardinality.ZERO_OR_MORE, reference -> {
					final ReferenceSchemaBuilder builder = grouped
						? reference.withGroupTypeRelatedToEntity(GROUP)
						: reference;
					builder.indexedForFilteringAndPartitioningInScope(LIVE)
						.indexedWithComponentsInScope(LIVE, ReferenceIndexedComponents.values())
						.withAttribute(SHARE, Integer.class, attribute -> attribute.filterableInScope(LIVE))
						.bucketedInScope(
							LIVE, SHARE_HISTOGRAM, ExpressionFactory.parse("$reference.attributes['share']")
						);
					if (faceted) {
						builder.facetedInScope(LIVE);
					}
				})
				.updateVia(session);

			for (int ownerPk = 1; ownerPk <= 2; ownerPk++) {
				final int value = ownerPk == 1 ? 3 : 7;
				session.createNewEntity(PRODUCT, ownerPk)
					.setReference(REFERENCE_NAME, 100 + ownerPk, reference -> {
						if (grouped) {
							reference.setGroup(GROUP, GROUP_PK);
						}
						reference.setAttribute(SHARE, value);
					})
					.upsertVia(session);
			}
		});
	}

	/**
	 * Verifies the persisted schema and expects both observations to be returned without boundary entities.
	 * A faceted reference is summarized on its own. A summary of a single reference refuses a reference that is
	 * not faceted, so such a reference is summarized by the summary of all references, which computes a requested
	 * histogram on every reference declaring it.
	 *
	 * @param evita engine containing the fixture
	 * @param grouped whether to request the histogram for the managed group
	 * @param faceted whether the fixture's reference is faceted
	 * @param statistics histogram requirement for the reference summary
	 * @return the statistics of the group carrying the histogram
	 */
	@Nonnull
	private static ReferenceGroupStatistics assertHistogram(
		@Nonnull Evita evita,
		boolean grouped,
		boolean faceted,
		@Nonnull ReferenceHistogramStatistics statistics
	) {
		return evita.queryCatalog(TEST_CATALOG, session -> {
			final ReferenceSchemaContract reference = session.getEntitySchema(PRODUCT).orElseThrow()
				.getReference(REFERENCE_NAME).orElseThrow();
			assertFalse(reference.isReferencedEntityTypeManaged());
			assertFalse(session.getAllEntityTypes().contains(TARGET));
			assertEquals(faceted, reference.isFacetedInScope(LIVE));
			assertNotNull(reference.getHistogramIndexDefinition(LIVE, SHARE_HISTOGRAM));
			if (grouped) {
				assertTrue(reference.isReferencedGroupTypeManaged());
				assertTrue(session.getAllEntityTypes().contains(GROUP));
			}

			final RequireConstraint summaryRequirement = faceted
				? referenceSummaryOfReferenceWithHistograms(
					REFERENCE_NAME, null, (EntityFetch) null, (EntityGroupFetch) null, statistics
				)
				: referenceSummaryWithHistograms(null, null, null, statistics);
			final EvitaResponse<EntityReferenceContract> response = session.query(
				query(
					collection(PRODUCT),
					require(summaryRequirement)
				),
				EntityReferenceContract.class
			);
			final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
			assertNotNull(summary);
			final ReferenceGroupStatistics group = grouped
				? summary.getReferenceGroupStatistics(REFERENCE_NAME, GROUP_PK)
				: summary.getReferenceGroupStatistics(REFERENCE_NAME);
			assertNotNull(group);
			final HistogramContract histogram = group.getHistogramStatistics(SHARE_HISTOGRAM);
			assertNotNull(histogram);
			assertEquals(2, histogram.getOverallCount());
			assertEquals(0, BigDecimal.valueOf(3).compareTo(histogram.getMin()));
			assertEquals(0, BigDecimal.valueOf(7).compareTo(histogram.getMax()));
			assertTrue(histogram.getBuckets().length > 0);
			assertTrue(histogram.getMinReferencedEntity().isEmpty());
			assertTrue(histogram.getMaxReferencedEntity().isEmpty());
			return group;
		});
	}

	/**
	 * Covers histogram and summary fetch requirements for an ungrouped reference.
	 */
	@Nested
	@DisplayName("Ungrouped reference")
	class Ungrouped {
		/**
		 * Expects the histogram when no boundary content is requested.
		 *
		 * @param evita engine containing the ungrouped fixture
		 */
		@Test
		@UseDataSet(UNGROUPED_FIXTURE)
		@DisplayName("Returns the histogram without a boundary fetch requirement")
		void shouldReturnHistogramWithoutEntityFetch(@Nonnull Evita evita) {
			assertHistogram(evita, false, true, histogramStatistics(10, SHARE_HISTOGRAM));
		}

		/**
		 * Expects the histogram of a reference that is not faceted. No facet statistics hold such a reference, so
		 * its histogram comes in a group synthesized for the histogram alone, which counts no entity and holds no
		 * facet.
		 *
		 * @param evita engine containing the fixture whose reference is not faceted
		 */
		@Test
		@UseDataSet(NOT_FACETED_FIXTURE)
		@DisplayName("Returns the histogram of a reference that is not faceted")
		void shouldReturnHistogramWhenReferenceIsNotFaceted(@Nonnull Evita evita) {
			final ReferenceGroupStatistics group = assertHistogram(
				evita, false, false, histogramStatistics(10, SHARE_HISTOGRAM)
			);

			assertEquals(0, group.getCount());
			assertTrue(group.getFacetStatistics().isEmpty());
		}

		/**
		 * Verifies that planning refuses a histogram boundary entity fetch for an unmanaged target.
		 * Planning also refuses the summary's own entity fetch for that target.
		 *
		 * @param evita engine containing the ungrouped fixture
		 */
		@Test
		@UseDataSet(UNGROUPED_FIXTURE)
		@DisplayName("Refuses a histogram boundary fetch requirement")
		void shouldThrowExceptionWhenHistogramRequestsEntityFetch(@Nonnull Evita evita) {
			final EvitaInvalidUsageException exception = assertThrowsExactly(
				EvitaInvalidUsageException.class,
				() -> assertHistogram(
					evita, false, true, histogramStatistics(10, entityFetch(attributeContentAll()), SHARE_HISTOGRAM)
				)
			);
			// the refusal of the fetch's planning, not a refusal of the histogram or a failed fetch
			assertEquals(
				"EntityFetch constraint is probably incorrectly nested in require part of the query.",
				exception.getMessage()
			);
		}

		/**
		 * Verifies that planning refuses the summary's entity fetch because the target has no collection.
		 *
		 * @param evita engine containing the ungrouped fixture
		 */
		@Test
		@UseDataSet(UNGROUPED_FIXTURE)
		@DisplayName("Refuses a summary entity fetch requirement")
		void shouldThrowExceptionWhenSummaryRequestsEntityFetch(@Nonnull Evita evita) {
			final EntityCollectionRequiredException exception = assertThrowsExactly(
				EntityCollectionRequiredException.class,
				() -> evita.queryCatalog(TEST_CATALOG, session -> {
					session.query(
						query(
							collection(PRODUCT),
							require(referenceSummaryOfReference(REFERENCE_NAME, entityFetch(attributeContentAll())))
						),
						EntityReferenceContract.class
					);
				})
			);
			// the schema lookup of planning, not the entity fetch of execution
			assertEquals(
				"Collection type is required in query in order to compute access entity schema!",
				exception.getMessage()
			);
		}
	}

	/**
	 * Expects the same histogram under the assigned managed group.
	 *
	 * @param evita engine containing the grouped fixture
	 */
	@Test
	@UseDataSet(GROUPED_FIXTURE)
	@DisplayName("Returns the histogram for an assigned managed group")
	void shouldReturnHistogramWhenReferenceHasManagedGroup(@Nonnull Evita evita) {
		assertHistogram(evita, true, true, histogramStatistics(10, SHARE_HISTOGRAM));
	}
}
