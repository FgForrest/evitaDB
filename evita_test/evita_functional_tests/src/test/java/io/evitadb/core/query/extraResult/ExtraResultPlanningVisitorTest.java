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

package io.evitadb.core.query.extraResult;

import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the producer-discovery logic of {@link ExtraResultPlanningVisitor}. The class is
 * heavily wired into the query planner (filter/order visitors, target indexes, etc.), so these
 * tests mock the constructor dependencies and exercise only the behaviour of the two
 * `findExistingProducer` overloads.
 *
 * The focus is the interaction between the `lastReturnedProducer` cache and the predicate-aware
 * overload: a match selected by a narrow predicate must NOT poison subsequent class-only lookups,
 * which need to return the first-registered producer of the class (insertion-ordered).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ExtraResultPlanningVisitor — findExistingProducer")
class ExtraResultPlanningVisitorTest {

	/**
	 * Builds a visitor wired against mocked dependencies. `QueryPlanningContext#getScopes` returns a
	 * single live scope so the constructor completes without NPEs.
	 */
	@Nonnull
	private static ExtraResultPlanningVisitor buildVisitor() {
		final QueryPlanningContext queryContext = mock(QueryPlanningContext.class);
		when(queryContext.getScopes()).thenReturn(Set.of(Scope.LIVE));
		final TargetIndexes<?> indexes = mock(TargetIndexes.class);
		final Formula formula = mock(Formula.class);
		final FilterByVisitor filterByVisitor = mock(FilterByVisitor.class);
		return new ExtraResultPlanningVisitor(
			queryContext, indexes, formula, filterByVisitor, Collections.emptyList(), null
		);
	}

	/**
	 * Test double implementing {@link ExtraResultProducer} that also carries an integer tag so the
	 * tests can write narrow predicates selecting a specific instance.
	 */
	private static final class TaggedProducer implements ExtraResultProducer {
		private final int tag;

		TaggedProducer(final int tag) {
			this.tag = tag;
		}

		int tag() {
			return this.tag;
		}

		@Override
		public <T extends Serializable> EvitaResponseExtraResult fabricate(@Nonnull QueryExecutionContext context) {
			throw new UnsupportedOperationException("Not implemented for test double");
		}

		@Nonnull
		@Override
		public String getDescription() {
			return "TaggedProducer(" + this.tag + ")";
		}
	}

	@Nested
	@DisplayName("Predicate overload must not poison the class-only lookup cache")
	class CacheIsolationBetweenOverloads {

		@Test
		@DisplayName("should return the first registered producer on class-only lookup even after a narrow predicate selected the second")
		void shouldReturnFirstRegisteredProducerOnClassOnlyLookupWhenPredicateEarlierSelectedSecond() {
			final ExtraResultPlanningVisitor visitor = buildVisitor();
			final TaggedProducer first = new TaggedProducer(1);
			final TaggedProducer second = new TaggedProducer(2);
			visitor.registerProducer(first);
			visitor.registerProducer(second);

			// narrow predicate hits the second producer
			final Predicate<TaggedProducer> narrow = p -> p.tag() == 2;
			final TaggedProducer narrowed = visitor.findExistingProducer(TaggedProducer.class, narrow);
			assertSame(second, narrowed, "narrow predicate must locate the second producer");

			// class-only lookup must still honour insertion order — if the predicate overload had
			// updated `lastReturnedProducer`, this would erroneously return `second`
			final TaggedProducer classOnly = visitor.findExistingProducer(TaggedProducer.class);
			assertSame(first, classOnly, "class-only lookup must return the first producer");
			assertNotSame(second, classOnly, "class-only lookup must not return the predicate-selected producer");
		}

		@Test
		@DisplayName("should return the cached instance on repeated class-only lookups for a single registered producer")
		void shouldReturnCachedInstanceOnRepeatedClassOnlyLookupsForSingleProducer() {
			final ExtraResultPlanningVisitor visitor = buildVisitor();
			final TaggedProducer only = new TaggedProducer(1);
			visitor.registerProducer(only);

			assertSame(only, visitor.findExistingProducer(TaggedProducer.class));
			// second call hits the cache path
			assertSame(only, visitor.findExistingProducer(TaggedProducer.class));
		}

		@Test
		@DisplayName("should return null and keep the class-only cache intact when predicate matches no producer")
		void shouldReturnNullAndKeepClassOnlyCacheIntactWhenPredicateMatchesNoProducer() {
			final ExtraResultPlanningVisitor visitor = buildVisitor();
			final TaggedProducer first = new TaggedProducer(1);
			final TaggedProducer second = new TaggedProducer(2);
			visitor.registerProducer(first);
			visitor.registerProducer(second);

			final TaggedProducer nothing = visitor.findExistingProducer(TaggedProducer.class, p -> p.tag() == 999);
			assertNull(nothing);

			// cache must still return the first producer for the class-only lookup
			assertSame(first, visitor.findExistingProducer(TaggedProducer.class));
		}
	}
}
