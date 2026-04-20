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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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

	@Nested
	@DisplayName("Guard against misuse")
	class GuardAgainstMisuse {

		@Test
		@DisplayName("should throw internal error when enclosing reference scope is missing")
		void shouldThrowWhenReferenceScopeMissing() {
			final ReferenceHistogramStatistics constraint =
				new ReferenceHistogramStatistics(10, HIST_NAME);
			final ExtraResultPlanningVisitor planner = mock(ExtraResultPlanningVisitor.class);
			when(planner.getProcessingScope())
				.thenReturn(processingScopeReturning(null));

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> translator.createProducer(constraint, planner)
			);
			assertNotNull(error.getMessage());
			assertTrue(
				error.getMessage().contains("reference-summary"),
				"Error must surface the missing-reference-scope contract, was: " + error.getMessage()
			);
		}

		@Test
		@DisplayName("should throw internal error when enclosing reference-summary producer is missing")
		@SuppressWarnings({"rawtypes", "unchecked"})
		void shouldThrowWhenProducerMissing() {
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
				() -> translator.createProducer(constraint, planner)
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
		@DisplayName("should throw when descriptors diverge on any field")
		void shouldThrowOnDescriptorDivergence(
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
}
