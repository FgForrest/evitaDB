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

package io.evitadb.core.query;

import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.MatchNodeIdHierarchyFilteringPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithin;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRoot;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Pins how {@link QueryPlanningContext} hands the hierarchy roots and the node visibility the filtering phase resolved
 * over to the hierarchy statistics of one scope.
 *
 * Each occurrence of a hierarchy filter constraint is recorded together with the processing scopes it was translated
 * in. The statistics of a scope read the occurrence translated in exactly that scope first, then the first occurrence
 * whose scopes contain it, and nothing at all from an occurrence that does not cover the scope. Constraints are
 * matched by equality, and the first resolution recorded for a constraint and a scope set wins.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Hierarchy resolutions recorded per occurrence and scope")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(HIERARCHY)
class QueryPlanningContextHierarchyResolutionTest {
	private static final Set<Scope> LIVE = EnumSet.of(Scope.LIVE);
	private static final Set<Scope> ARCHIVED = EnumSet.of(Scope.ARCHIVED);
	private static final Set<Scope> BOTH = EnumSet.of(Scope.LIVE, Scope.ARCHIVED);
	private static final int LIVE_ROOT = 1;
	private static final int OTHER_LIVE_ROOT = 2;
	private static final int ARCHIVED_ROOT = 11;
	private static final int[] NO_ROOTS = new int[0];
	private static final String REFERENCE_NAME = "categories";

	/**
	 * The hierarchy filter constraint all resolutions are recorded for.
	 */
	private final HierarchyFilterConstraint constraint = hierarchyWithin(
		REFERENCE_NAME, entityPrimaryKeyInSet(LIVE_ROOT)
	);
	/**
	 * A fresh context per test - every test records resolutions into it.
	 */
	private QueryPlanningContext context;

	/**
	 * Creates the empty planning context the test records its resolutions into.
	 */
	@BeforeEach
	void setUp() {
		this.context = createContext();
	}

	@Test
	@DisplayName("should prefer the occurrence translated in exactly the scope")
	void shouldPreferOccurrenceTranslatedInExactlyTheScope() {
		// the occurrence covering both scopes is recorded first, so a containment-only scan would answer with it
		this.context.setRootHierarchyNodesFormula(this.constraint, BOTH, roots(LIVE_ROOT));
		this.context.setRootHierarchyNodesFormula(this.constraint, ARCHIVED, roots(ARCHIVED_ROOT));

		assertArrayEquals(
			new int[]{ARCHIVED_ROOT},
			rootsFor(this.constraint, Scope.ARCHIVED),
			"the occurrence translated in ARCHIVED only must answer for ARCHIVED"
		);
		assertArrayEquals(
			new int[]{LIVE_ROOT},
			rootsFor(this.constraint, Scope.LIVE),
			"the occurrence covering both scopes must answer for LIVE"
		);
	}

	@Test
	@DisplayName("should fall back to an occurrence covering several scopes")
	void shouldFallBackToOccurrenceCoveringSeveralScopes() {
		this.context.setRootHierarchyNodesFormula(this.constraint, BOTH, roots(LIVE_ROOT));

		assertArrayEquals(new int[]{LIVE_ROOT}, rootsFor(this.constraint, Scope.LIVE));
		assertArrayEquals(new int[]{LIVE_ROOT}, rootsFor(this.constraint, Scope.ARCHIVED));
	}

	@Test
	@DisplayName("should return no roots for a scope no occurrence covers")
	void shouldReturnEmptyRootsWhenNoOccurrenceCoversTheScope() {
		this.context.setRootHierarchyNodesFormula(this.constraint, ARCHIVED, roots(ARCHIVED_ROOT));

		assertArrayEquals(
			NO_ROOTS,
			rootsFor(this.constraint, Scope.LIVE),
			"an occurrence translated in ARCHIVED only must not answer for LIVE"
		);
		assertArrayEquals(new int[]{ARCHIVED_ROOT}, rootsFor(this.constraint, Scope.ARCHIVED));
	}

	@Test
	@DisplayName("should match an equal but distinct constraint instance")
	void shouldMatchEqualButDistinctConstraintInstance() {
		final HierarchyFilterConstraint equalConstraint = hierarchyWithin(
			REFERENCE_NAME, entityPrimaryKeyInSet(LIVE_ROOT)
		);
		this.context.setRootHierarchyNodesFormula(this.constraint, LIVE, roots(LIVE_ROOT));
		this.context.setRootHierarchyNodesFormula(this.constraint, BOTH, roots(OTHER_LIVE_ROOT));

		assertArrayEquals(
			new int[]{LIVE_ROOT},
			rootsFor(equalConstraint, Scope.LIVE),
			"the exact-scope lookup must match an equal instance"
		);
		assertArrayEquals(
			new int[]{OTHER_LIVE_ROOT},
			rootsFor(equalConstraint, Scope.ARCHIVED),
			"the covering-occurrence lookup must match an equal instance"
		);
	}

	@Test
	@DisplayName("should not answer for a different constraint or for none")
	void shouldNotAnswerForDifferentConstraint() {
		final HierarchyFilterConstraint otherConstraint = hierarchyWithin(
			REFERENCE_NAME, entityPrimaryKeyInSet(OTHER_LIVE_ROOT)
		);
		this.context.setRootHierarchyNodesFormula(this.constraint, BOTH, roots(LIVE_ROOT));

		for (final Scope scope : Scope.values()) {
			assertArrayEquals(
				NO_ROOTS,
				rootsFor(otherConstraint, scope),
				() -> "a different constraint must find no roots in " + scope
			);
			assertArrayEquals(
				NO_ROOTS,
				rootsFor(null, scope),
				() -> "no constraint must find no roots in " + scope
			);
		}
	}

	@Test
	@DisplayName("should keep the first resolution recorded for a constraint and a scope set")
	void shouldKeepFirstResolutionRecordedForOccurrence() {
		this.context.setRootHierarchyNodesFormula(this.constraint, LIVE, roots(LIVE_ROOT));
		this.context.setRootHierarchyNodesFormula(this.constraint, LIVE, roots(OTHER_LIVE_ROOT));

		assertArrayEquals(new int[]{LIVE_ROOT}, rootsFor(this.constraint, Scope.LIVE));
	}

	@Test
	@DisplayName("should resolve the node visibility of an occurrence covering the scope")
	void shouldResolveNodeVisibilityOfCoveringOccurrence() {
		final HierarchyFilteringPredicate bothPredicate = new MatchNodeIdHierarchyFilteringPredicate(LIVE_ROOT);
		final HierarchyFilteringPredicate archivedPredicate = new MatchNodeIdHierarchyFilteringPredicate(ARCHIVED_ROOT);
		this.context.setHierarchyHavingPredicate(this.constraint, BOTH, bothPredicate);
		this.context.setHierarchyHavingPredicate(this.constraint, ARCHIVED, archivedPredicate);

		assertSame(
			archivedPredicate,
			this.context.getHierarchyHavingPredicate(this.constraint, Scope.ARCHIVED),
			"the occurrence translated in ARCHIVED only must answer for ARCHIVED"
		);
		assertSame(
			bothPredicate,
			this.context.getHierarchyHavingPredicate(this.constraint, Scope.LIVE),
			"the occurrence covering both scopes must answer for LIVE"
		);

		final QueryPlanningContext archivedOnlyContext = createContext();
		archivedOnlyContext.setHierarchyHavingPredicate(this.constraint, ARCHIVED, archivedPredicate);
		assertNull(
			archivedOnlyContext.getHierarchyHavingPredicate(this.constraint, Scope.LIVE),
			"an occurrence translated in ARCHIVED only must not answer for LIVE"
		);
	}

	@Test
	@DisplayName("should hand hierarchyWithin to the statistics of a scope only an occurrence covers")
	void shouldNarrowHierarchyWithinToScopesItsOccurrencesCover() {
		// an occurrence that resolved no root still covers its scope - the statistics there must be empty, not
		// computed as if the query had no hierarchy filter
		this.context.setRootHierarchyNodesFormula(this.constraint, ARCHIVED, EmptyFormula.INSTANCE);

		assertSame(this.constraint, this.context.getHierarchyFilterForScope(this.constraint, Scope.ARCHIVED));
		assertNull(
			this.context.getHierarchyFilterForScope(this.constraint, Scope.LIVE),
			"an occurrence translated in ARCHIVED only must not restrict LIVE"
		);
		assertNull(
			createContext().getHierarchyFilterForScope(this.constraint, Scope.LIVE),
			"a constraint with no recorded occurrence restricts no scope"
		);
		assertNull(this.context.getHierarchyFilterForScope(null, Scope.LIVE));
	}

	@Test
	@DisplayName("should hand hierarchyWithinRoot to the statistics of every scope")
	void shouldKeepHierarchyWithinRootForEveryScope() {
		final HierarchyFilterConstraint withinRoot = hierarchyWithinRoot(REFERENCE_NAME);
		for (final Scope scope : Scope.values()) {
			assertSame(
				withinRoot,
				this.context.getHierarchyFilterForScope(withinRoot, scope),
				() -> "hierarchyWithinRoot records no roots, so it must not be dropped in " + scope
			);
		}
	}

	/**
	 * Creates an empty planning context of a query over the `product` collection. The catalog is the only mock,
	 * because it cannot be built outside a running engine and the hierarchy resolutions never consult it.
	 *
	 * @return the context
	 */
	@Nonnull
	private static QueryPlanningContext createContext() {
		return new QueryPlanningContext(
			mock(Catalog.class),
			null,
			null,
			new EvitaRequest(query(collection("product")), OffsetDateTime.now(), EntityReference.class, null),
			Map.of(),
			Map.of(),
			NoCacheSupervisor.INSTANCE
		);
	}

	/**
	 * Returns the roots the context hands to the statistics of the scope for the constraint.
	 *
	 * @param hierarchyFilter the constraint whose roots are asked for, may be NULL
	 * @param scope           the scope the statistics are computed for
	 * @return the root primary keys, ascending
	 */
	@Nonnull
	private int[] rootsFor(@Nullable HierarchyFilterConstraint hierarchyFilter, @Nonnull Scope scope) {
		return this.context.getRootHierarchyNodes(hierarchyFilter, scope).getArray();
	}

	/**
	 * Creates the formula of the resolved hierarchy roots.
	 *
	 * @param primaryKeys the root primary keys, ascending
	 * @return the formula
	 */
	@Nonnull
	private static ConstantFormula roots(@Nonnull int... primaryKeys) {
		return new ConstantFormula(new BaseBitmap(primaryKeys));
	}

}
