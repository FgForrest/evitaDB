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
import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
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
import java.math.BigDecimal;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.dataType.Scope.LIVE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises references whose target or group type evitaDB does not manage. Reference attribute histograms work for
 * unmanaged targets, while a fetch of an unmanaged type - a histogram boundary entity, or an entity or group body of
 * a summary or facet summary - is refused with {@link EntityNotManagedException} naming that type. A fetch of the
 * managed side of such a reference is accepted.
 */
@DisplayName("Reference attribute histograms and fetches for unmanaged types")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
@ExtendWith(EvitaParameterResolver.class)
class UnmanagedReferenceHistogramBoundaryFunctionalTest implements EvitaTestSupport {
	private static final String UNGROUPED_FIXTURE = "unmanagedReferenceHistogramUngrouped";
	private static final String GROUPED_FIXTURE = "unmanagedReferenceHistogramGrouped";
	private static final String UNMANAGED_GROUP_FIXTURE = "unmanagedReferenceHistogramUnmanagedGroup";
	private static final String MANAGED_TARGET_FIXTURE = "referenceSummaryManagedTargetUnmanagedGroup";
	private static final String NOT_FACETED_FIXTURE = "unmanagedReferenceHistogramNotFaceted";
	private static final String PRODUCT = "Product";
	private static final String TARGET = "ExternalParameter";
	private static final String GROUP = "ParameterGroup";
	private static final String EXTERNAL_GROUP = "ExternalParameterGroup";
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
		setUpFixture(evita, null, true);
	}

	/**
	 * Creates the faceted reference fixture with a managed group and unmanaged targets.
	 *
	 * @param evita engine in which to create the fixture
	 */
	@DataSet(value = GROUPED_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpGroupedFixture(@Nonnull Evita evita) {
		setUpFixture(evita, GROUP, true);
	}

	/**
	 * Creates the faceted reference fixture with an unmanaged group and unmanaged targets.
	 *
	 * @param evita engine in which to create the fixture
	 */
	@DataSet(value = UNMANAGED_GROUP_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpUnmanagedGroupFixture(@Nonnull Evita evita) {
		setUpFixture(evita, EXTERNAL_GROUP, true);
	}

	/**
	 * Creates a faceted reference with two managed targets and an unmanaged group.
	 *
	 * @param evita engine in which to create the fixture
	 */
	@DataSet(value = MANAGED_TARGET_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpManagedTargetFixture(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(TARGET).withoutGeneratedPrimaryKey().updateVia(session);
			session.createNewEntity(TARGET, 101).upsertVia(session);
			session.createNewEntity(TARGET, 102).upsertVia(session);
			session.defineEntitySchema(PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceToEntity(
					REFERENCE_NAME, TARGET, Cardinality.ZERO_OR_MORE,
					reference -> reference.withGroupType(EXTERNAL_GROUP).facetedInScope(LIVE)
				)
				.updateVia(session);
			session.createNewEntity(PRODUCT, 1)
				.setReference(REFERENCE_NAME, 101, reference -> reference.setGroup(EXTERNAL_GROUP, GROUP_PK))
				.setReference(REFERENCE_NAME, 102, reference -> reference.setGroup(EXTERNAL_GROUP, GROUP_PK))
				.upsertVia(session);
		});
	}

	/**
	 * Creates the ungrouped reference fixture whose reference is not faceted.
	 *
	 * @param evita engine in which to create the fixture
	 */
	@DataSet(value = NOT_FACETED_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpNotFacetedFixture(@Nonnull Evita evita) {
		setUpFixture(evita, null, false);
	}

	/**
	 * Persists the schema and inserts two owners of the reference.
	 *
	 * @param evita engine in which to create the fixture
	 * @param groupType type of the reference group, or null for an ungrouped reference
	 * @param faceted whether the reference is faceted
	 */
	private static void setUpFixture(@Nonnull Evita evita, @Nullable String groupType, boolean faceted) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			if (GROUP.equals(groupType)) {
				session.defineEntitySchema(GROUP).withoutGeneratedPrimaryKey().updateVia(session);
				session.createNewEntity(GROUP, GROUP_PK).upsertVia(session);
			}
			session.defineEntitySchema(PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(REFERENCE_NAME, TARGET, Cardinality.ZERO_OR_MORE, reference -> {
					if (GROUP.equals(groupType)) {
						reference.withGroupTypeRelatedToEntity(groupType);
					} else if (groupType != null) {
						reference.withGroupType(groupType);
					}
					reference.indexedForFilteringAndPartitioningInScope(LIVE)
						.indexedWithComponentsInScope(LIVE, ReferenceIndexedComponents.values())
						.withAttribute(SHARE, Integer.class, attribute -> attribute.filterableInScope(LIVE))
						.bucketedInScope(
							LIVE, SHARE_HISTOGRAM, ExpressionFactory.parse("$reference.attributes['share']")
						);
					if (faceted) {
						reference.facetedInScope(LIVE);
					}
				})
				.updateVia(session);

			for (int ownerPk = 1; ownerPk <= 2; ownerPk++) {
				final int value = ownerPk == 1 ? 3 : 7;
				session.createNewEntity(PRODUCT, ownerPk)
					.setReference(REFERENCE_NAME, 100 + ownerPk, reference -> {
						if (groupType != null) {
							reference.setGroup(groupType, GROUP_PK);
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
	 * Expects the action to be refused with exactly {@link EntityNotManagedException} naming the given type.
	 *
	 * @param unmanagedType entity type the refusal must name
	 * @param action action expected to be refused
	 */
	private static void assertRefusedAsNotManaged(@Nonnull String unmanagedType, @Nonnull Executable action) {
		final EntityNotManagedException exception = assertThrowsExactly(EntityNotManagedException.class, action);
		assertEquals(
			"Cannot execute the operation, entity type `" + unmanagedType + "` is not managed by evitaDB!",
			exception.getMessage()
		);
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
		 * Verifies that the histogram boundary fetch reports the unmanaged reference target type.
		 *
		 * @param evita engine containing the ungrouped fixture
		 */
		@Test
		@UseDataSet(UNGROUPED_FIXTURE)
		@DisplayName("Refuses a histogram boundary fetch requirement")
		void shouldThrowExceptionWhenHistogramRequestsEntityFetch(@Nonnull Evita evita) {
			assertRefusedAsNotManaged(
				TARGET,
				() -> assertHistogram(
					evita, false, true, histogramStatistics(10, entityFetch(attributeContentAll()), SHARE_HISTOGRAM)
				)
			);
		}

		/**
		 * Verifies that the reference-specific summary fetch reports the unmanaged reference target type.
		 *
		 * @param evita engine containing the ungrouped fixture
		 */
		@Test
		@UseDataSet(UNGROUPED_FIXTURE)
		@DisplayName("Refuses a summary entity fetch requirement")
		void shouldThrowExceptionWhenSummaryRequestsEntityFetch(@Nonnull Evita evita) {
			assertRefusedAsNotManaged(
				TARGET,
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
		}

		/**
		 * Verifies that the deprecated reference-specific facet summary reports the unmanaged reference target type.
		 *
		 * @param evita engine containing the ungrouped fixture
		 */
		@Test
		@UseDataSet(UNGROUPED_FIXTURE)
		@DisplayName("Refuses a facet summary entity fetch requirement")
		void shouldThrowExceptionWhenFacetSummaryRequestsEntityFetch(@Nonnull Evita evita) {
			assertRefusedAsNotManaged(
				TARGET,
				() -> evita.queryCatalog(TEST_CATALOG, session -> {
					session.query(
						query(
							collection(PRODUCT),
							require(facetSummaryOfReference(REFERENCE_NAME, entityFetch(attributeContentAll())))
						),
						EntityReferenceContract.class
					);
				})
			);
		}

		/**
		 * Verifies that an all-references histogram boundary fetch reports the unmanaged reference target type.
		 *
		 * @param evita engine containing the ungrouped fixture
		 */
		@Test
		@UseDataSet(UNGROUPED_FIXTURE)
		@DisplayName("Refuses a histogram boundary fetch in an all-references summary")
		void shouldThrowExceptionWhenGenericSummaryHistogramRequestsEntityFetch(@Nonnull Evita evita) {
			assertRefusedAsNotManaged(
				TARGET,
				() -> evita.queryCatalog(TEST_CATALOG, session -> {
					session.query(
						query(
							collection(PRODUCT),
							require(referenceSummaryWithHistograms(
								null, null, null,
								histogramStatistics(10, entityFetch(attributeContentAll()), SHARE_HISTOGRAM)
							))
						),
						EntityReferenceContract.class
					);
				})
			);
		}
	}

	/**
	 * Covers summary fetches of a reference whose group type evitaDB does not manage.
	 */
	@Nested
	@DisplayName("Unmanaged group")
	class UnmanagedGroup {
		/**
		 * Verifies that a reference-specific summary group fetch reports the unmanaged group type.
		 *
		 * @param evita engine containing the fixture with an unmanaged group
		 */
		@Test
		@UseDataSet(UNMANAGED_GROUP_FIXTURE)
		@DisplayName("Refuses a summary group fetch")
		void shouldThrowExceptionWhenSummaryRequestsUnmanagedGroupFetch(@Nonnull Evita evita) {
			assertRefusedAsNotManaged(
				EXTERNAL_GROUP,
				() -> evita.queryCatalog(TEST_CATALOG, session -> {
					final ReferenceSchemaContract reference = session.getEntitySchema(PRODUCT).orElseThrow()
						.getReference(REFERENCE_NAME).orElseThrow();
					assertEquals(EXTERNAL_GROUP, reference.getReferencedGroupType());
					assertFalse(reference.isReferencedGroupTypeManaged());
					assertFalse(session.getAllEntityTypes().contains(EXTERNAL_GROUP));
					return session.query(
						query(
							collection(PRODUCT),
							require(
								referenceSummaryOfReference(REFERENCE_NAME, entityGroupFetch(attributeContentAll()))
							)
						),
						EntityReferenceContract.class
					);
				})
			);
		}

		/**
		 * Verifies that the deprecated reference-specific facet summary group fetch reports the unmanaged group type.
		 *
		 * @param evita engine containing the fixture with an unmanaged group
		 */
		@Test
		@UseDataSet(UNMANAGED_GROUP_FIXTURE)
		@DisplayName("Refuses a facet summary group fetch")
		void shouldThrowExceptionWhenFacetSummaryRequestsUnmanagedGroupFetch(@Nonnull Evita evita) {
			assertRefusedAsNotManaged(
				EXTERNAL_GROUP,
				() -> evita.queryCatalog(TEST_CATALOG, session -> {
					session.query(
						query(
							collection(PRODUCT),
							require(facetSummaryOfReference(REFERENCE_NAME, entityGroupFetch(attributeContentAll())))
						),
						EntityReferenceContract.class
					);
				})
			);
		}

		/**
		 * Verifies that summary group fetch validation uses the group's management flag.
		 *
		 * @param evita engine containing the fixture with managed targets and an unmanaged group
		 */
		@Test
		@UseDataSet(MANAGED_TARGET_FIXTURE)
		@DisplayName("Refuses a summary group fetch even when the target is managed")
		void shouldThrowExceptionWhenSummaryRequestsUnmanagedGroupFetchForManagedTarget(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final ReferenceSchemaContract reference = session.getEntitySchema(PRODUCT).orElseThrow()
					.getReference(REFERENCE_NAME).orElseThrow();
				assertEquals(TARGET, reference.getReferencedEntityType());
				assertTrue(reference.isReferencedEntityTypeManaged());
				assertTrue(session.getAllEntityTypes().contains(TARGET));
				assertEquals(EXTERNAL_GROUP, reference.getReferencedGroupType());
				assertFalse(reference.isReferencedGroupTypeManaged());
				assertFalse(session.getAllEntityTypes().contains(EXTERNAL_GROUP));

				assertRefusedAsNotManaged(
					EXTERNAL_GROUP,
					() -> session.query(
						query(
							collection(PRODUCT),
							require(
								referenceSummaryOfReference(REFERENCE_NAME, entityGroupFetch(attributeContentAll()))
							)
						),
						EntityReferenceContract.class
					)
				);
			});
		}

		/**
		 * Expects the summary to fetch the managed targets: the unmanaged group does not refuse a fetch of the targets.
		 *
		 * @param evita engine containing the fixture with managed targets and an unmanaged group
		 */
		@Test
		@UseDataSet(MANAGED_TARGET_FIXTURE)
		@DisplayName("Fetches the managed targets")
		void shouldReturnSummaryWithTargetEntitiesWhenTargetIsManaged(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final ReferenceSchemaContract reference = session.getEntitySchema(PRODUCT).orElseThrow()
					.getReference(REFERENCE_NAME).orElseThrow();
				assertTrue(reference.isReferencedEntityTypeManaged());
				assertFalse(reference.isReferencedGroupTypeManaged());

				final EvitaResponse<EntityReferenceContract> response = session.query(
					query(
						collection(PRODUCT),
						require(referenceSummaryOfReference(REFERENCE_NAME, entityFetch(attributeContentAll())))
					),
					EntityReferenceContract.class
				);

				final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(REFERENCE_NAME, GROUP_PK);
				assertNotNull(group);
				assertEquals(2, group.getFacetStatistics().size());
				for (final FacetStatistics facet : group.getFacetStatistics()) {
					assertEquals(TARGET, assertInstanceOf(SealedEntity.class, facet.getFacetEntity()).getType());
				}
			});
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

	/**
	 * Expects the summary to fetch the managed group of a reference whose targets are unmanaged: the unmanaged targets
	 * do not refuse a fetch of the group.
	 *
	 * @param evita engine containing the grouped fixture
	 */
	@Test
	@UseDataSet(GROUPED_FIXTURE)
	@DisplayName("Fetches the managed group of a reference to unmanaged targets")
	void shouldReturnSummaryWithGroupEntityWhenGroupIsManaged(@Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			final ReferenceSchemaContract reference = session.getEntitySchema(PRODUCT).orElseThrow()
				.getReference(REFERENCE_NAME).orElseThrow();
			assertFalse(reference.isReferencedEntityTypeManaged());
			assertTrue(reference.isReferencedGroupTypeManaged());

			final EvitaResponse<EntityReferenceContract> response = session.query(
				query(
					collection(PRODUCT),
					require(referenceSummaryOfReference(REFERENCE_NAME, entityGroupFetch(attributeContentAll())))
				),
				EntityReferenceContract.class
			);

			final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
			assertNotNull(summary);
			final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(REFERENCE_NAME, GROUP_PK);
			assertNotNull(group);
			final SealedEntity groupEntity = assertInstanceOf(SealedEntity.class, group.getGroupEntity());
			assertEquals(GROUP, groupEntity.getType());
			assertEquals(GROUP_PK, groupEntity.getPrimaryKey());
		});
	}
}
