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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Random;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link EntityIndexKey} verifying TreeSet/HashSet consistency
 * across many random key insertions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EntityIndexKey generational proof")
@Tag(INDEXING)
@Tag(MANAGEMENT)
class LongRunningEntityIndexKeyTest implements TimeBoundedTestSupport {

	/** All entity index types that have valid constructor configurations. */
	private static final EntityIndexType[] VALID_TYPES = {
		EntityIndexType.GLOBAL,
		EntityIndexType.REFERENCED_ENTITY_TYPE,
		EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE,
		EntityIndexType.REFERENCED_ENTITY,
		EntityIndexType.REFERENCED_GROUP_ENTITY
	};

	/** Reference names used for random key generation. */
	private static final String[] REF_NAMES = {
		"CATEGORY", "BRAND", "TAG", "STORE", "PARAMETER"
	};

	/**
	 * Creates a {@link RepresentativeReferenceKey} with specified reference name and primary key.
	 */
	@Nonnull
	private static RepresentativeReferenceKey rrk(@Nonnull String refName, int pk) {
		return new RepresentativeReferenceKey(new ReferenceKey(refName, pk));
	}

	/**
	 * Generates a random valid {@link EntityIndexKey} using the given random source.
	 */
	@Nonnull
	private static EntityIndexKey randomKey(@Nonnull Random random) {
		final EntityIndexType type = VALID_TYPES[random.nextInt(VALID_TYPES.length)];
		final Scope scope = Scope.values()[random.nextInt(Scope.values().length)];
		final String refName = REF_NAMES[random.nextInt(REF_NAMES.length)];

		return switch (type) {
			case GLOBAL -> new EntityIndexKey(type, scope, null);
			case REFERENCED_ENTITY_TYPE, REFERENCED_GROUP_ENTITY_TYPE ->
				new EntityIndexKey(type, scope, refName);
			case REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY ->
				new EntityIndexKey(
					type, scope,
					rrk(refName, random.nextInt(100) + 1)
				);
			// deprecated type not used in random generation
			default -> new EntityIndexKey(EntityIndexType.GLOBAL, scope, null);
		};
	}

	@DisplayName("survives generational randomized test verifying TreeSet/HashSet consistency")
	@Tag(SLOW)
	@ParameterizedTest(
		name = "EntityIndexKey should survive generational randomized test " +
			"verifying TreeSet/HashSet consistency"
	)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new TreeSet<>(), new HashSet<>()),
			(random, state) -> {
				final EntityIndexKey key = randomKey(random);

				state.treeSet().add(key);
				state.hashSet().add(key);

				// periodically verify consistency
				assertEquals(
					state.treeSet().size(),
					state.hashSet().size(),
					"TreeSet and HashSet must agree on size"
				);
				assertTrue(
					state.treeSet().containsAll(state.hashSet()),
					"TreeSet must contain all HashSet elements"
				);
				assertTrue(
					state.hashSet().containsAll(state.treeSet()),
					"HashSet must contain all TreeSet elements"
				);

				return state;
			}
		);
	}

	/**
	 * State carried across iterations of the generational test, holding both
	 * a {@link TreeSet} (compareTo-based) and a {@link HashSet} (equals/hashCode-based).
	 */
	private record TestState(
		@Nonnull TreeSet<EntityIndexKey> treeSet,
		@Nonnull HashSet<EntityIndexKey> hashSet
	) {}
}
