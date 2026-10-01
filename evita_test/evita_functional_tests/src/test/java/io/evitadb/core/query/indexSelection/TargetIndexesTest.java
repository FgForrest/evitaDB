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


package io.evitadb.core.query.indexSelection;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a {@link TargetIndexes} set is matched to the constraint being translated: by the constraint instance
 * **and** the processing scopes the set was built for, so one instance placed in two `inScope` containers finds the
 * set of its own container (#1686).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TargetIndexes matching a translated constraint")
@Tag(ENGINE)
@Tag(QUERY)
class TargetIndexesTest {
	private static final Set<Scope> LIVE = EnumSet.of(Scope.LIVE);
	private static final Set<Scope> ARCHIVED = EnumSet.of(Scope.ARCHIVED);
	private static final Set<Scope> BOTH = EnumSet.of(Scope.LIVE, Scope.ARCHIVED);

	@Test
	@DisplayName("should represent the constraint instance in the scopes it was built for")
	void shouldRepresentConstraintInstanceInScopesItWasBuiltFor() {
		final FilterConstraint constraint = referenceHaving("brand", entityPrimaryKeyInSet(1));

		assertTrue(candidate(constraint, LIVE).represents(constraint, EnumSet.of(Scope.LIVE)));
		assertTrue(candidate(constraint, BOTH).represents(constraint, EnumSet.of(Scope.LIVE, Scope.ARCHIVED)));
	}

	@Test
	@DisplayName("should not represent the same constraint instance in other scopes")
	void shouldNotRepresentSameConstraintInstanceInOtherScopes() {
		final FilterConstraint constraint = referenceHaving("brand", entityPrimaryKeyInSet(1));
		final TargetIndexes<ReducedEntityIndex> liveCandidate = candidate(constraint, LIVE);

		assertFalse(liveCandidate.represents(constraint, ARCHIVED));
		assertFalse(liveCandidate.represents(constraint, BOTH));
	}

	@Test
	@DisplayName("should not represent an equal but distinct constraint instance")
	void shouldNotRepresentEqualButDistinctConstraintInstance() {
		final FilterConstraint constraint = referenceHaving("brand", entityPrimaryKeyInSet(1));
		final FilterConstraint equalConstraint = referenceHaving("brand", entityPrimaryKeyInSet(1));

		assertFalse(candidate(constraint, LIVE).represents(equalConstraint, LIVE));
	}

	@Test
	@DisplayName("should not represent any constraint when built for none")
	void shouldNotRepresentAnyConstraintWhenBuiltForNone() {
		final FilterConstraint constraint = referenceHaving("brand", entityPrimaryKeyInSet(1));
		final TargetIndexes<GlobalEntityIndex> globalSet = new TargetIndexes<>(
			"GLOBAL", GlobalEntityIndex.class, Collections.emptyList()
		);

		assertFalse(globalSet.represents(constraint, BOTH));
	}

	/**
	 * Creates an empty deferred reduced-index set representing the constraint, built for the passed scopes.
	 *
	 * @param constraint the represented constraint
	 * @param scopes     the scopes the set is built for
	 * @return the set
	 */
	@Nonnull
	private static TargetIndexes<ReducedEntityIndex> candidate(
		@Nonnull FilterConstraint constraint,
		@Nonnull Set<Scope> scopes
	) {
		return new TargetIndexes<>(
			"REFERENCED_ENTITY", constraint, scopes, ReducedEntityIndex.class, 0, List::of
		);
	}

}
