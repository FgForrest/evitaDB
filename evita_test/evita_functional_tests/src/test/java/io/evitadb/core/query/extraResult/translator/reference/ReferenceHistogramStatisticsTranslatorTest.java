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

package io.evitadb.core.query.extraResult.translator.reference;

import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.HistogramRequest;
import io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceSummaryProducer.RequestedBucketRange;
import io.evitadb.core.query.filter.translator.histogram.ResolvedHistogramHaving;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferenceHistogramStatisticsTranslator}. The translator is normally invoked
 * inside a reference-summary traversal where the enclosing translator has already prepared a
 * {@link ProcessingScope}. Tests here exercise:
 *
 * - guard errors when the enclosing reference-summary scope / producer is missing;
 * - cross-scope divergence of resolved `HistogramValueDescriptor`s (the `assertConsistent` helper).
 *
 * The full planner context — realistic reference schemas, histogram definitions, multi-scope
 * resolution and inapplicability handling — is covered by the end-to-end functional tests in
 * `ReferenceHistogramFunctionalTest`. This unit suite stays laser-focused on the defensive
 * pre-conditions that must survive regardless of upstream setup so that regressions in guard
 * logic surface without spinning a full Evita instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferenceHistogramStatisticsTranslator")
class ReferenceHistogramStatisticsTranslatorTest {

	private static final String REF_NAME = "categories";
	private static final String HIST_NAME = "priceBucket";
	private static final String ATTR_NAME = "price";

	private final ReferenceHistogramStatisticsTranslator translator = new ReferenceHistogramStatisticsTranslator();

	/**
	 * Builds a real {@link ProcessingScope} record whose `referenceSchemaAccessor` returns the
	 * supplied reference schema (possibly `null`). `ProcessingScope` is a Java `record` — its
	 * accessor methods are final and cannot be stubbed by Mockito; construct the record directly
	 * instead.
	 *
	 * @param referenceSchema optional reference schema to be returned by the accessor; `null` for
	 *                        the "no reference scope" case
	 * @return a fully-functional {@link ProcessingScope}
	 */
	@Nonnull
	private static ProcessingScope processingScopeReturning(
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		final Deque<Set<Scope>> scopes = new ArrayDeque<>();
		scopes.push(EnumSet.of(Scope.LIVE));
		final Supplier<ReferenceSchemaContract> refAccessor = () -> referenceSchema;
		final Supplier<EntitySchemaContract> entityAccessor = () -> null;
		return new ProcessingScope(null, scopes, refAccessor, entityAccessor);
	}

	/**
	 * Builds a real {@link HistogramValueDescriptor} record with sensible defaults so tests can
	 * override only the fields they care about. Records are plain data — no mocks needed.
	 *
	 * @param source         source kind (reference vs. referenced-entity attribute)
	 * @param attributeName  source attribute name
	 * @param plainType      plain numeric type of the source attribute
	 * @param localized      whether the source attribute is locale-scoped
	 * @return a descriptor for test use
	 */
	@Nonnull
	private static HistogramValueDescriptor descriptor(
		@Nonnull HistogramValueSource source,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> plainType,
		boolean localized
	) {
		return new HistogramValueDescriptor(
			source,
			source == HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE ? "someTargetEntity" : null,
			attributeName,
			plainType,
			false,
			localized,
			null
		);
	}

	/**
	 * Reflectively invokes the private static `assertConsistent` helper. Rethrows the underlying
	 * `RuntimeException` for `assertThrows` compatibility.
	 */
	private static void invokeAssertConsistent(
		@Nonnull HistogramValueDescriptor a,
		@Nonnull HistogramValueDescriptor b,
		@Nonnull String referenceName,
		@Nonnull String histogramName
	) throws Exception {
		final Method method = ReferenceHistogramStatisticsTranslator.class.getDeclaredMethod(
			"assertConsistent",
			HistogramValueDescriptor.class, HistogramValueDescriptor.class,
			String.class, String.class
		);
		method.setAccessible(true);
		try {
			method.invoke(null, a, b, referenceName, histogramName);
		} catch (final InvocationTargetException ite) {
			if (ite.getCause() instanceof RuntimeException re) {
				throw re;
			}
			throw ite;
		}
	}

	/**
	 * Reflectively invokes the private static `extractRequestedBucketRanges` helper. The helper now
	 * takes the planning-context's pre-resolved `histogramHaving` registry directly — no filter tree,
	 * no resolver — so tests construct the registry list in the shape the filter translator would have
	 * produced.
	 *
	 * @param resolvedHistogramHavings the pre-resolved registry entries
	 * @param referenceName            the reference the histogram belongs to
	 * @param histogramName            the histogram slot name
	 * @return the per-group range map (empty when no registered entry targets the slot)
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private static Map<Integer, RequestedBucketRange> invokeExtractRequestedBucketRanges(
		@Nonnull List<ResolvedHistogramHaving> resolvedHistogramHavings,
		@Nonnull String referenceName,
		@Nonnull String histogramName
	) throws Exception {
		final Method method = ReferenceHistogramStatisticsTranslator.class.getDeclaredMethod(
			"extractRequestedBucketRanges",
			List.class, String.class, String.class
		);
		method.setAccessible(true);
		try {
			return (Map<Integer, RequestedBucketRange>) method.invoke(
				null, resolvedHistogramHavings, referenceName, histogramName
			);
		} catch (final InvocationTargetException ite) {
			if (ite.getCause() instanceof RuntimeException re) {
				throw re;
			}
			throw ite;
		}
	}

	@Nested
	@DisplayName("Guard against misuse")
	class GuardAgainstMisuse {

		@Test
		@DisplayName("should throw GenericEvitaInternalError when translator runs outside a reference-summary scope")
		void shouldThrowGenericEvitaInternalErrorWhenEnclosingReferenceScopeMissing() {
			final ReferenceHistogramStatistics constraint =
				new ReferenceHistogramStatistics(10, HIST_NAME);
			final ExtraResultPlanningVisitor planner = mock(ExtraResultPlanningVisitor.class);
			when(planner.getProcessingScope())
				.thenReturn(processingScopeReturning(null));

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceHistogramStatisticsTranslatorTest.this.translator.createProducer(constraint, planner)
			);
			assertNotNull(error.getMessage());
			assertTrue(
				error.getMessage().contains("reference-summary"),
				"Error must surface the missing-reference-scope contract, was: " + error.getMessage()
			);
		}

		@Test
		@DisplayName("should throw GenericEvitaInternalError when enclosing ReferenceSummaryProducer is absent")
		@SuppressWarnings({"rawtypes", "unchecked"})
		void shouldThrowGenericEvitaInternalErrorWhenReferenceSummaryProducerAbsent() {
			final ReferenceHistogramStatistics constraint =
				new ReferenceHistogramStatistics(10, HIST_NAME);
			final ReferenceSchemaContract referenceSchema = mock(ReferenceSchemaContract.class);
			when(referenceSchema.getName()).thenReturn(REF_NAME);

			final ExtraResultPlanningVisitor planner = mock(ExtraResultPlanningVisitor.class);
			when(planner.getProcessingScope())
				.thenReturn(processingScopeReturning(referenceSchema));
			// `findExistingProducer(Class, Predicate)` must return null to simulate missing producer
			when(planner.findExistingProducer(
				ArgumentMatchers.<Class>any(),
				ArgumentMatchers.<Predicate>any()
			)).thenReturn(null);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceHistogramStatisticsTranslatorTest.this.translator.createProducer(constraint, planner)
			);
			assertTrue(
				error.getMessage().contains("ReferenceSummaryProducer"),
				"Error must point at the missing parent producer, was: " + error.getMessage()
			);
		}
	}

	/**
	 * Pins the cross-scope divergence-detection contract enforced by `assertConsistent`. Two
	 * descriptors resolved from different scopes must agree on every field that downstream
	 * boundary-resolution and histogram-computation logic depends on: `source`, `sourceAttributeName`,
	 * `plainType`, `localized`. A single parameterized test exercises each divergence axis using
	 * real `HistogramValueDescriptor` records (cheap, no mocks).
	 *
	 * No functional-suite counterpart exists because the schema builder does not cross-validate
	 * bucketed definitions across scopes at build time — divergent definitions are reachable only
	 * at query time via this runtime check.
	 */
	@Nested
	@DisplayName("assertConsistent cross-scope divergence detection")
	class AssertConsistent {

		/**
		 * Produces `(divergenceAxis, descriptorA, descriptorB)` tuples where `descriptorA` and
		 * `descriptorB` agree on every field except the named axis. Each case pins one clause of
		 * the `||` chain in `assertConsistent`.
		 *
		 * @return a stream of argument tuples covering every divergence axis
		 */
		@Nonnull
		static Stream<Arguments> divergentDescriptorPairs() {
			final HistogramValueDescriptor baseline = descriptor(
				HistogramValueSource.REFERENCE_ATTRIBUTE, ATTR_NAME, Integer.class, false
			);
			return Stream.of(
				Arguments.of(
					"source",
					baseline,
					descriptor(HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, ATTR_NAME, Integer.class, false)
				),
				Arguments.of(
					"attributeName",
					baseline,
					descriptor(HistogramValueSource.REFERENCE_ATTRIBUTE, "other", Integer.class, false)
				),
				Arguments.of(
					"plainType",
					baseline,
					descriptor(HistogramValueSource.REFERENCE_ATTRIBUTE, ATTR_NAME, Long.class, false)
				),
				Arguments.of(
					"localizedFlag",
					baseline,
					descriptor(HistogramValueSource.REFERENCE_ATTRIBUTE, ATTR_NAME, Integer.class, true)
				)
			);
		}

		@ParameterizedTest(name = "divergent {0}")
		@MethodSource("divergentDescriptorPairs")
		@DisplayName("should throw when descriptors diverge on the given field")
		void shouldThrowWhenDescriptorsDivergeOnGivenField(
			@Nonnull String axis,
			@Nonnull HistogramValueDescriptor live,
			@Nonnull HistogramValueDescriptor archived
		) {
			final EvitaInvalidUsageException error = assertThrows(
				EvitaInvalidUsageException.class,
				() -> invokeAssertConsistent(live, archived, REF_NAME, HIST_NAME),
				"Divergence on " + axis + " must be flagged"
			);
			assertTrue(
				error.getMessage().contains("incompatible value expressions"),
				"Error must surface the cross-scope-divergence contract, was: " + error.getMessage()
			);
		}
	}

	/**
	 * Pins the `extractRequestedBucketRanges` helper contract: it consumes the planning-context's
	 * pre-resolved `histogramHaving` registry (populated by `HistogramHavingTranslator` during filter
	 * translation) and returns a per-group map of `[from, to]` ranges keyed by resolved group PK.
	 * The extractor has no filter-tree walker or group-selector resolver of its own — all of that
	 * work has already been paid during filter translation.
	 *
	 * These tests build `ResolvedHistogramHaving` entries directly, mirroring the shape the filter
	 * translator would have produced. The single-histogram-name shorthand (null `histogramName` at
	 * the DSL surface) is resolved upstream in `HistogramHavingTranslator#resolveDescriptor`, so the
	 * extractor only ever sees entries whose `histogramName` is a concrete non-null value — there is
	 * no "match any slot" case here.
	 */
	@Nested
	@DisplayName("extractRequestedBucketRanges — slot filtering and bound handling")
	class ExtractRequestedBucketRange {

		@Nonnull
		private static ResolvedHistogramHaving ungrouped(
			@Nonnull String referenceName,
			@Nonnull String histogramName,
			@Nullable BigDecimal from,
			@Nullable BigDecimal to
		) {
			return new ResolvedHistogramHaving(
				referenceName, histogramName, ResolvedHistogramHaving.NON_GROUPED_SENTINEL, from, to
			);
		}

		@Test
		@DisplayName("should return an empty map when the resolved-histogramHaving registry is empty")
		void shouldReturnEmptyMapWhenRegistryIsEmpty() throws Exception {
			final Map<Integer, RequestedBucketRange> result = invokeExtractRequestedBucketRanges(
				List.of(), REF_NAME, HIST_NAME
			);
			assertNotNull(result);
			assertTrue(result.isEmpty(), "Empty registry yields an empty map");
		}

		@Test
		@DisplayName("should return an empty map when no registry entry matches the (reference, histogram) slot")
		void shouldReturnEmptyMapWhenNoRegistryEntryMatchesSlot() throws Exception {
			final Map<Integer, RequestedBucketRange> result = invokeExtractRequestedBucketRanges(
				List.of(ungrouped("otherRef", "otherHist", new BigDecimal("10"), new BigDecimal("50"))),
				REF_NAME, HIST_NAME
			);
			assertNotNull(result);
			assertTrue(result.isEmpty(), "Non-matching entries yield an empty map");
		}

		@Test
		@DisplayName("should extract the [from, to] range of a matching ungrouped entry under the sentinel key")
		void shouldExtractRangeFromMatchingUngroupedEntryUnderSentinelKey() throws Exception {
			final Map<Integer, RequestedBucketRange> result = invokeExtractRequestedBucketRanges(
				List.of(ungrouped(REF_NAME, HIST_NAME, new BigDecimal("10"), new BigDecimal("50"))),
				REF_NAME, HIST_NAME
			);
			assertEquals(1, result.size());
			final RequestedBucketRange range = result.get(HistogramRequest.NON_GROUPED_SENTINEL);
			assertNotNull(range, "Matching ungrouped entry keys the sentinel slot");
			assertEquals(new BigDecimal("10"), range.from());
			assertEquals(new BigDecimal("50"), range.to());
		}

		@Test
		@DisplayName("should surface a null `to` when the registry entry has an open-ended upper bound")
		void shouldSurfaceNullUpperBoundWhenRegistryEntryIsOpenEnded() throws Exception {
			final Map<Integer, RequestedBucketRange> result = invokeExtractRequestedBucketRanges(
				List.of(ungrouped(REF_NAME, HIST_NAME, new BigDecimal("10"), null)),
				REF_NAME, HIST_NAME
			);
			final RequestedBucketRange range = result.get(HistogramRequest.NON_GROUPED_SENTINEL);
			assertNotNull(range);
			assertEquals(new BigDecimal("10"), range.from());
			assertNull(range.to(), "Open-ended upper bound must surface as null `to`");
		}

		@Test
		@DisplayName("should surface a null `from` when the registry entry has an open-ended lower bound")
		void shouldSurfaceNullLowerBoundWhenRegistryEntryIsOpenEnded() throws Exception {
			final Map<Integer, RequestedBucketRange> result = invokeExtractRequestedBucketRanges(
				List.of(ungrouped(REF_NAME, HIST_NAME, null, new BigDecimal("50"))),
				REF_NAME, HIST_NAME
			);
			final RequestedBucketRange range = result.get(HistogramRequest.NON_GROUPED_SENTINEL);
			assertNotNull(range);
			assertNull(range.from(), "Open-ended lower bound must surface as null `from`");
			assertEquals(new BigDecimal("50"), range.to());
		}

		@Test
		@DisplayName("should skip registry entries whose reference name does not match the queried slot")
		void shouldSkipRegistryEntriesWithMismatchingReferenceName() throws Exception {
			final Map<Integer, RequestedBucketRange> result = invokeExtractRequestedBucketRanges(
				List.of(ungrouped("otherRef", HIST_NAME, new BigDecimal("10"), new BigDecimal("50"))),
				REF_NAME, HIST_NAME
			);
			assertNotNull(result);
			assertTrue(result.isEmpty(), "Different reference name must not match");
		}

		@Test
		@DisplayName("should skip registry entries whose histogram name does not match the queried slot")
		void shouldSkipRegistryEntriesWithMismatchingHistogramName() throws Exception {
			final Map<Integer, RequestedBucketRange> result = invokeExtractRequestedBucketRanges(
				List.of(ungrouped(REF_NAME, "otherHist", new BigDecimal("10"), new BigDecimal("50"))),
				REF_NAME, HIST_NAME
			);
			assertNotNull(result);
			assertTrue(result.isEmpty(), "Different histogram name must not match");
		}

		@Test
		@DisplayName("should throw EvitaInvalidUsageException when two entries target the same (refName, histName, groupPk) slot")
		void shouldThrowEvitaInvalidUsageExceptionWhenTwoEntriesTargetSameSlot() {
			final List<ResolvedHistogramHaving> registry = List.of(
				ungrouped(REF_NAME, HIST_NAME, new BigDecimal("10"), new BigDecimal("50")),
				ungrouped(REF_NAME, HIST_NAME, new BigDecimal("20"), new BigDecimal("60"))
			);
			final EvitaInvalidUsageException error = assertThrows(
				EvitaInvalidUsageException.class,
				() -> invokeExtractRequestedBucketRanges(registry, REF_NAME, HIST_NAME),
				"Two registry entries addressing the same slot must be rejected"
			);
			assertNotNull(error.getMessage());
			assertTrue(
				error.getMessage().contains(HIST_NAME)
					&& error.getMessage().contains(REF_NAME),
				"Error must surface the offending slot, was: " + error.getMessage()
			);
		}
	}

	/**
	 * Pins the per-group bucket-range extraction contract: when several registry entries address
	 * different group slots of the same `(referenceName, histogramName)` pair, the extractor must
	 * return a per-group map keyed by resolved group PK (not a single flattened range). The
	 * consumer (`PendingHistogram.materialize`) then looks up the range matching the actual group
	 * PK of the histogram being materialized — so the "height" group's histogram flags `requested`
	 * only inside its own `[50, 120]` slider range and the "weight" group's histogram flags
	 * `requested` only inside its own `[90, 140]` slider range.
	 *
	 * Regression guard: the earlier implementation returned only the first document-order match's
	 * range and applied it to every group's histogram — a silent cross-group contamination. A test
	 * on the new per-group map protects against a future flattening regression.
	 */
	@Nested
	@DisplayName("extractRequestedBucketRanges — per-group keying (no cross-contamination)")
	class ExtractRequestedBucketRangesPerGroup {

		@Test
		@DisplayName("should key ranges by resolved group PK when sibling entries target different groups")
		void shouldKeyRangesByResolvedGroupPkWhenSiblingEntriesTargetDifferentGroups() throws Exception {
			// two registry entries address different group slots — extractor must surface a per-group
			// map keyed by resolved PK so each group's histogram gets its OWN range applied
			final List<ResolvedHistogramHaving> registry = List.of(
				new ResolvedHistogramHaving(REF_NAME, HIST_NAME, 7, new BigDecimal("50"), new BigDecimal("120")),
				new ResolvedHistogramHaving(REF_NAME, HIST_NAME, 11, new BigDecimal("90"), new BigDecimal("140"))
			);

			final Map<Integer, RequestedBucketRange> rangesByGroupPk =
				invokeExtractRequestedBucketRanges(registry, REF_NAME, HIST_NAME);

			assertNotNull(rangesByGroupPk, "Per-group extraction must yield a map, not null");
			assertEquals(2, rangesByGroupPk.size(),
				"Both group slots must be represented in the per-group map");

			final RequestedBucketRange heightRange = rangesByGroupPk.get(7);
			assertNotNull(heightRange, "Height group (PK=7) must have an entry");
			assertEquals(new BigDecimal("50"), heightRange.from(),
				"Height slider lower bound must be 50 — no cross-contamination from weight");
			assertEquals(new BigDecimal("120"), heightRange.to(),
				"Height slider upper bound must be 120 — no cross-contamination from weight");

			final RequestedBucketRange weightRange = rangesByGroupPk.get(11);
			assertNotNull(weightRange, "Weight group (PK=11) must have an entry");
			assertEquals(new BigDecimal("90"), weightRange.from(),
				"Weight slider lower bound must be 90 — no cross-contamination from height");
			assertEquals(new BigDecimal("140"), weightRange.to(),
				"Weight slider upper bound must be 140 — no cross-contamination from height");
		}
	}
}
