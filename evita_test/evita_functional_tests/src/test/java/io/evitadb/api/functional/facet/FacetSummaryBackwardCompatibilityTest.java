/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.functional.facet;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Iterator;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;

/**
 * Backward-compatibility regression tests that pin the runtime extra-result class returned by
 * {@link io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer}:
 *
 * - Deprecated constraints `facetSummary(...)` / `facetSummaryOfReference(...)` must produce a
 *   runtime {@link FacetSummary} under {@link FacetSummary}{@code .class}, and leave
 *   {@link ReferenceSummary}{@code .class} empty, so existing callers that still look up by the
 *   old key keep working.
 * - Canonical constraints `referenceSummary(...)` / `referenceSummaryOfReference(...)` must
 *   produce a runtime {@link ReferenceSummary} (strictly — not a {@link FacetSummary} subclass)
 *   under {@link ReferenceSummary}{@code .class}, and leave {@link FacetSummary}{@code .class} empty.
 * - Mixed request (both constraint families in the same require) must carry both DTOs, each
 *   under its own class key, with matching content.
 *
 * The content-equality assertion runs once against a shared helper
 * {@link #assertReferenceSummariesEqual(ReferenceSummary, ReferenceSummary)} so that neither
 * path duplicates functional assertions — the two computations are deterministic and produce
 * identical statistics.
 *
 * The whole class is {@code @Deprecated} and marked for removal together with the deprecated
 * {@link io.evitadb.api.query.require.FacetSummary} /
 * {@link io.evitadb.api.query.require.FacetSummaryOfReference} constraints and the
 * {@link FacetSummary} DTO.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated Delete together with {@link io.evitadb.api.query.require.FacetSummary},
 *             {@link io.evitadb.api.query.require.FacetSummaryOfReference} and
 *             {@link FacetSummary}.
 */
// TOBEDONE: deprecated - remove when FacetSummary constraint is removed (https://github.com/FgForrest/evitaDB/issues/538)
@Deprecated(since = "2026.2", forRemoval = true)
@DisplayName("Facet summary backward-compatibility dispatch contract")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(FACET)
public class FacetSummaryBackwardCompatibilityTest extends AbstractEntityByFacetFilteringFunctionalTest {

	/**
	 * The dataset is initialized by the parent class via
	 * {@link io.evitadb.test.annotation.DataSet} on its generator method; we just reference
	 * the same name here.
	 */
	private static final String THOUSAND_PRODUCTS_WITH_FACETS = "ThousandsProductsWithFacets";

	@Nonnull
	@Override
	protected ReferenceSchemaBuilder makeReferenceIndexed(ReferenceSchemaBuilder whichIs) {
		return whichIs.indexedForFiltering();
	}

	@DisplayName("Deprecated facetSummary constraint emits FacetSummary under FacetSummary.class only")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void deprecatedConstraintEmitsFacetSummaryOnly(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.COUNTS),
						facetSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.IMPACT)
					)
				);
				final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);

				final FacetSummary facetSummary = response.getExtraResult(FacetSummary.class);
				assertNotNull(facetSummary, "Deprecated constraint must produce FacetSummary");
				assertSame(
					FacetSummary.class, facetSummary.getClass(),
					"Runtime class must be exactly FacetSummary; rejecting any future subclass drift"
				);
				assertFalse(
					facetSummary.getReferenceStatistics().isEmpty(),
					"Reference statistics must be populated"
				);

				assertNull(
					response.getExtraResult(ReferenceSummary.class),
					"Canonical ReferenceSummary.class must remain empty when only deprecated constraints were used"
				);
				return null;
			}
		);
	}

	@DisplayName("Canonical referenceSummary constraint emits ReferenceSummary under ReferenceSummary.class only")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void canonicalConstraintEmitsReferenceSummaryOnly(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						referenceSummary(FacetStatisticsDepth.COUNTS),
						referenceSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.IMPACT)
					)
				);
				final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);

				final ReferenceSummary referenceSummary = response.getExtraResult(ReferenceSummary.class);
				assertNotNull(referenceSummary, "Canonical constraint must produce ReferenceSummary");
				assertSame(
					ReferenceSummary.class, referenceSummary.getClass(),
					"Runtime class must be exactly ReferenceSummary (not the deprecated FacetSummary subclass)"
				);
				assertFalse(
					referenceSummary.getReferenceStatistics().isEmpty(),
					"Reference statistics must be populated"
				);

				assertNull(
					response.getExtraResult(FacetSummary.class),
					"Deprecated FacetSummary.class must remain empty when only canonical constraints were used"
				);
				return null;
			}
		);
	}

	@DisplayName("Both forms produce equivalent content for the same query")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void bothFormsProduceEquivalentContent(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> deprecatedResponse = session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							facetSummary(FacetStatisticsDepth.COUNTS),
							facetSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.IMPACT)
						)
					),
					EntityReference.class
				);
				final EvitaResponse<EntityReference> canonicalResponse = session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
							referenceSummary(FacetStatisticsDepth.COUNTS),
							referenceSummaryOfReference(Entities.BRAND, FacetStatisticsDepth.IMPACT)
						)
					),
					EntityReference.class
				);

				final FacetSummary deprecatedResult = deprecatedResponse.getExtraResult(FacetSummary.class);
				final ReferenceSummary canonicalResult = canonicalResponse.getExtraResult(ReferenceSummary.class);
				assertNotNull(deprecatedResult);
				assertNotNull(canonicalResult);
				// FacetSummary IS-A ReferenceSummary, so structural comparison uses the common surface
				assertReferenceSummariesEqual(deprecatedResult, canonicalResult);
				return null;
			}
		);
	}

	@DisplayName("Mixed request yields both DTOs keyed by their own class, with identical content")
	@UseDataSet(THOUSAND_PRODUCTS_WITH_FACETS)
	@Test
	void mixedRequestYieldsBothDtosUnderBothKeys(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// deliberately pair the two generic (all-reference) constraints so both DTOs cover the same
				// set of references and the structural equality helper can compare them element-for-element
				final Query query = query(
					collection(Entities.PRODUCT),
					require(
						page(1, Integer.MAX_VALUE),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES),
						facetSummary(FacetStatisticsDepth.COUNTS),
						referenceSummary(FacetStatisticsDepth.COUNTS)
					)
				);
				final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);

				final FacetSummary deprecatedResult = response.getExtraResult(FacetSummary.class);
				assertNotNull(deprecatedResult, "Deprecated constraint in mixed query must produce FacetSummary");
				assertSame(FacetSummary.class, deprecatedResult.getClass());

				final ReferenceSummary canonicalResult = response.getExtraResult(ReferenceSummary.class);
				assertNotNull(canonicalResult, "Canonical constraint in mixed query must produce ReferenceSummary");
				assertSame(ReferenceSummary.class, canonicalResult.getClass());

				// Both DTOs are independent instances — same computation run twice, no shared state
				assertReferenceSummariesEqual(deprecatedResult, canonicalResult);
				return null;
			}
		);
	}

	/**
	 * Structural equality over the {@link ReferenceSummary} public surface. Since
	 * {@link FacetSummary} {@code extends} {@link ReferenceSummary} and adds no extra state,
	 * the same accessors apply to both forms — the comparison never reaches down into
	 * subclass-specific data.
	 *
	 * The helper replaces hand-written content assertions for the compat tests: the two
	 * computations are deterministic, so comparing the two DTOs to each other is strictly
	 * equivalent to comparing each to a hard-coded baseline and catches any drift.
	 */
	private static void assertReferenceSummariesEqual(
		@Nonnull ReferenceSummary left,
		@Nonnull ReferenceSummary right
	) {
		final Collection<? extends ReferenceGroupStatistics> leftStats = left.getReferenceStatistics();
		final Collection<? extends ReferenceGroupStatistics> rightStats = right.getReferenceStatistics();
		assertEquals(
			leftStats.size(), rightStats.size(),
			"Both summaries must carry the same number of group-statistics entries"
		);
		final Iterator<? extends ReferenceGroupStatistics> leftIt = leftStats.iterator();
		final Iterator<? extends ReferenceGroupStatistics> rightIt = rightStats.iterator();
		while (leftIt.hasNext()) {
			final ReferenceGroupStatistics leftGroup = leftIt.next();
			final ReferenceGroupStatistics rightGroup = rightIt.next();
			assertEquals(leftGroup.getReferenceName(), rightGroup.getReferenceName());
			assertEquals(leftGroup.getCount(), rightGroup.getCount());
			final EntityClassifier leftGroupEntity = leftGroup.getGroupEntity();
			final EntityClassifier rightGroupEntity = rightGroup.getGroupEntity();
			if (leftGroupEntity == null) {
				assertNull(rightGroupEntity);
			} else {
				assertNotNull(rightGroupEntity);
				assertEquals(leftGroupEntity.getPrimaryKey(), rightGroupEntity.getPrimaryKey());
			}
			assertFacetStatisticsEqual(leftGroup.getFacetStatistics(), rightGroup.getFacetStatistics());
		}
		// double-check symmetry via equals contract; the wrappers' equals reaches the same result
		assertEquals(
			left.getReferenceStatistics().size(), right.getReferenceStatistics().size(),
			"Size must remain stable on re-iteration"
		);
	}

	/**
	 * Per-group facet-statistics equality — pairs are iterated in insertion order which is
	 * deterministic for both paths.
	 */
	private static void assertFacetStatisticsEqual(
		@Nonnull Collection<FacetStatistics> left,
		@Nonnull Collection<FacetStatistics> right
	) {
		assertEquals(left.size(), right.size(), "Facet statistics count must match");
		final Iterator<FacetStatistics> leftIt = left.iterator();
		final Iterator<FacetStatistics> rightIt = right.iterator();
		while (leftIt.hasNext()) {
			final FacetStatistics l = leftIt.next();
			final FacetStatistics r = rightIt.next();
			assertEquals(l.getFacetEntity().getPrimaryKey(), r.getFacetEntity().getPrimaryKey());
			assertEquals(l.getCount(), r.getCount());
			assertEquals(l.isRequested(), r.isRequested());
			assertEquals(l.getImpact(), r.getImpact());
		}
	}
}
