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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Random;
import java.util.TreeSet;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EntityIndexKey} verifying constructor validation, equality/hashCode contract,
 * compareTo ordering, toString formatting, and randomized consistency.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EntityIndexKey functionality")
class EntityIndexKeyTest implements TimeBoundedTestSupport {

	private static final String CATEGORY = "CATEGORY";
	private static final String BRAND = "BRAND";

	/**
	 * Creates a {@link RepresentativeReferenceKey} with specified reference name and primary key.
	 */
	@Nonnull
	private static RepresentativeReferenceKey rrk(@Nonnull String refName, int pk) {
		return new RepresentativeReferenceKey(new ReferenceKey(refName, pk));
	}

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorValidationTest {

		@Test
		@DisplayName("should accept GLOBAL type with null discriminator")
		void shouldAcceptGlobalWithNullDiscriminator() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);

			assertEquals(EntityIndexType.GLOBAL, key.type());
			assertEquals(Scope.LIVE, key.scope());
			assertNull(key.discriminator());
		}

		@Test
		@DisplayName("should accept GLOBAL type via single-arg constructor")
		void shouldAcceptGlobalViaSingleArgConstructor() {
			final EntityIndexKey key = new EntityIndexKey(EntityIndexType.GLOBAL);

			assertEquals(EntityIndexType.GLOBAL, key.type());
			assertEquals(Scope.DEFAULT_SCOPE, key.scope());
			assertNull(key.discriminator());
		}

		@Test
		@DisplayName("should accept GLOBAL type via two-arg constructor")
		void shouldAcceptGlobalViaTwoArgConstructor() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.ARCHIVED
			);

			assertEquals(EntityIndexType.GLOBAL, key.type());
			assertEquals(Scope.ARCHIVED, key.scope());
			assertNull(key.discriminator());
		}

		@Test
		@DisplayName("should accept REFERENCED_ENTITY_TYPE with String discriminator")
		void shouldAcceptReferencedEntityTypeWithString() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			assertEquals(EntityIndexType.REFERENCED_ENTITY_TYPE, key.type());
			assertEquals(CATEGORY, key.discriminator());
		}

		@Test
		@DisplayName("should accept REFERENCED_GROUP_ENTITY_TYPE with String discriminator")
		void shouldAcceptReferencedGroupEntityTypeWithString() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, BRAND
			);

			assertEquals(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, key.type());
			assertEquals(BRAND, key.discriminator());
		}

		@Test
		@DisplayName("should accept REFERENCED_ENTITY with RepresentativeReferenceKey")
		void shouldAcceptReferencedEntityWithRrk() {
			final RepresentativeReferenceKey refKey = rrk(CATEGORY, 1);
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, refKey
			);

			assertEquals(EntityIndexType.REFERENCED_ENTITY, key.type());
			assertEquals(refKey, key.discriminator());
		}

		@Test
		@DisplayName("should accept REFERENCED_GROUP_ENTITY with RepresentativeReferenceKey")
		void shouldAcceptReferencedGroupEntityWithRrk() {
			final RepresentativeReferenceKey refKey = rrk(BRAND, 5);
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, refKey
			);

			assertEquals(EntityIndexType.REFERENCED_GROUP_ENTITY, key.type());
			assertEquals(refKey, key.discriminator());
		}

		@Test
		@DisplayName("should reject REFERENCED_ENTITY_TYPE with null discriminator")
		void shouldRejectReferencedEntityTypeWithNull() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, null
				)
			);
		}

		@Test
		@DisplayName("should reject GLOBAL with String discriminator")
		void shouldRejectGlobalWithString() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new EntityIndexKey(
					EntityIndexType.GLOBAL, Scope.LIVE, CATEGORY
				)
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_ENTITY with String discriminator")
		void shouldRejectReferencedEntityWithString() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, CATEGORY
				)
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_ENTITY_TYPE with RepresentativeReferenceKey")
		void shouldRejectReferencedEntityTypeWithRrk() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, rrk(CATEGORY, 1)
				)
			);
		}

		@Test
		@DisplayName("should reject any type with unsupported discriminator type like Integer")
		void shouldRejectUnsupportedDiscriminatorType() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, 42
				)
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_GROUP_ENTITY_TYPE with null discriminator")
		void shouldRejectReferencedGroupEntityTypeWithNull() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, null
				)
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_GROUP_ENTITY with String discriminator")
		void shouldRejectReferencedGroupEntityWithString() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, BRAND
				)
			);
		}

		@Test
		@DisplayName("should reject GLOBAL with RepresentativeReferenceKey")
		void shouldRejectGlobalWithRrk() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new EntityIndexKey(
					EntityIndexType.GLOBAL, Scope.LIVE, rrk(CATEGORY, 1)
				)
			);
		}
	}

	@Nested
	@DisplayName("toString formatting")
	class ToStringFormattingTest {

		@Test
		@DisplayName("should format GLOBAL key without discriminator suffix")
		void shouldFormatGlobalKey() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);

			assertEquals("Live index: GLOBAL", key.toString());
		}

		@Test
		@DisplayName("should format GLOBAL key with ARCHIVED scope")
		void shouldFormatGlobalArchivedKey() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.ARCHIVED, null
			);

			assertEquals("Archived index: GLOBAL", key.toString());
		}

		@Test
		@DisplayName("should format REFERENCED_ENTITY_TYPE key with discriminator")
		void shouldFormatReferencedEntityTypeKey() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			assertEquals(
				"Live index: REFERENCED_ENTITY_TYPE - CATEGORY",
				key.toString()
			);
		}

		@Test
		@DisplayName("should format REFERENCED_ENTITY key with RepresentativeReferenceKey")
		void shouldFormatReferencedEntityKey() {
			final RepresentativeReferenceKey refKey = rrk(CATEGORY, 7);
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, refKey
			);

			final String result = key.toString();
			assertTrue(
				result.startsWith("Live index: REFERENCED_ENTITY - "),
				"toString should start with scope, type, and separator"
			);
			assertTrue(
				result.contains(CATEGORY),
				"toString should contain the reference name"
			);
		}

		@Test
		@DisplayName("should return itself from entityIndexKey() accessor")
		void shouldReturnItselfFromAccessor() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);

			assertEquals(key, key.entityIndexKey());
		}
	}

	@Nested
	@DisplayName("equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			assertEquals(key, key);
		}

		@Test
		@DisplayName("should be symmetric for equal keys")
		void shouldBeSymmetric() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			assertEquals(key1, key2);
			assertEquals(key2, key1);
		}

		@Test
		@DisplayName("should produce consistent hashCode for equal keys")
		void shouldProduceConsistentHashCode() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not equal null")
		void shouldNotEqualNull() {
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);

			assertNotEquals(null, key);
		}

		@Test
		@DisplayName("should distinguish different types")
		void shouldDistinguishDifferentTypes() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should distinguish different scopes")
		void shouldDistinguishDifferentScopes() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.ARCHIVED, CATEGORY
			);

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should distinguish different discriminators")
		void shouldDistinguishDifferentDiscriminators() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, BRAND
			);

			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should be equal for GLOBAL keys with same scope")
		void shouldBeEqualForGlobalKeysWithSameScope() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should be equal for REFERENCED_ENTITY keys with equal RRK")
		void shouldBeEqualForReferencedEntityWithEqualRrk() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk(CATEGORY, 1)
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk(CATEGORY, 1)
			);

			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should distinguish REFERENCED_ENTITY keys with different primary keys")
		void shouldDistinguishReferencedEntityWithDifferentPk() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk(CATEGORY, 1)
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk(CATEGORY, 2)
			);

			assertNotEquals(key1, key2);
		}
	}

	@Nested
	@DisplayName("compareTo contract")
	class CompareToContractTest {

		@Test
		@DisplayName("should be anti-symmetric")
		void shouldBeAntiSymmetric() {
			final EntityIndexKey a = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "A_REF"
			);
			final EntityIndexKey b = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "B_REF"
			);

			assertTrue(a.compareTo(b) < 0);
			assertTrue(b.compareTo(a) > 0);
		}

		@Test
		@DisplayName("should return zero for equal keys")
		void shouldReturnZeroForEqualKeys() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			assertEquals(0, key1.compareTo(key2));
		}

		@Test
		@DisplayName("should be transitive")
		void shouldBeTransitive() {
			final EntityIndexKey a = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "A_REF"
			);
			final EntityIndexKey b = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "B_REF"
			);
			final EntityIndexKey c = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "C_REF"
			);

			// a < b and b < c implies a < c
			assertTrue(a.compareTo(b) < 0);
			assertTrue(b.compareTo(c) < 0);
			assertTrue(a.compareTo(c) < 0);
		}

		@Test
		@DisplayName("should order by type first")
		void shouldOrderByTypeFirst() {
			final EntityIndexKey global = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);
			final EntityIndexKey refType = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			// GLOBAL has lower ordinal than REFERENCED_ENTITY_TYPE
			assertTrue(global.compareTo(refType) < 0);
			assertTrue(refType.compareTo(global) > 0);
		}

		@Test
		@DisplayName("should order by scope within same type")
		void shouldOrderByScopeWithinSameType() {
			final EntityIndexKey live = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);
			final EntityIndexKey archived = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.ARCHIVED, null
			);

			// LIVE ordinal=0 < ARCHIVED ordinal=1
			assertTrue(live.compareTo(archived) < 0);
			assertTrue(archived.compareTo(live) > 0);
		}

		@Test
		@DisplayName("should order by String discriminator within same type and scope")
		void shouldOrderByStringDiscriminator() {
			final EntityIndexKey alpha = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "ALPHA"
			);
			final EntityIndexKey beta = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "BETA"
			);

			assertTrue(alpha.compareTo(beta) < 0);
			assertTrue(beta.compareTo(alpha) > 0);
		}

		@Test
		@DisplayName("should order by RRK discriminator within same type and scope")
		void shouldOrderByRrkDiscriminator() {
			final EntityIndexKey catKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk("ALPHA", 1)
			);
			final EntityIndexKey brandKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk("BETA", 1)
			);

			assertTrue(catKey.compareTo(brandKey) < 0);
			assertTrue(brandKey.compareTo(catKey) > 0);
		}

		@Test
		@DisplayName("should order RRK by primary key when reference names match")
		void shouldOrderRrkByPrimaryKey() {
			final EntityIndexKey pk1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk(CATEGORY, 1)
			);
			final EntityIndexKey pk2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk(CATEGORY, 2)
			);

			assertTrue(pk1.compareTo(pk2) < 0);
			assertTrue(pk2.compareTo(pk1) > 0);
		}

		@Test
		@DisplayName("should return zero for GLOBAL keys with same scope")
		void shouldReturnZeroForGlobalWithSameScope() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.GLOBAL, Scope.LIVE, null
			);

			assertEquals(0, key1.compareTo(key2));
		}

		@Test
		@DisplayName("should be consistent with equals")
		void shouldBeConsistentWithEquals() {
			final EntityIndexKey key1 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey key2 = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);

			// compareTo == 0 should imply equals == true
			assertEquals(0, key1.compareTo(key2));
			assertEquals(key1, key2);
		}

		@Test
		@DisplayName("REFERENCED_ENTITY_TYPE keys with different scopes should not compare as equal")
		void shouldDistinguishReferencedEntityTypeKeysByScope() {
			final EntityIndexKey liveKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey archivedKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.ARCHIVED, CATEGORY
			);

			assertNotEquals(liveKey, archivedKey);
			assertNotEquals(0, liveKey.compareTo(archivedKey));
		}

		@Test
		@DisplayName("REFERENCED_GROUP_ENTITY keys with different scopes should not compare as equal")
		void shouldDistinguishReferencedGroupEntityKeysByScope() {
			final RepresentativeReferenceKey refKey = rrk(BRAND, 5);
			final EntityIndexKey liveKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, refKey
			);
			final EntityIndexKey archivedKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.ARCHIVED, refKey
			);

			assertNotEquals(liveKey, archivedKey);
			assertNotEquals(0, liveKey.compareTo(archivedKey));
		}

		@Test
		@DisplayName("TreeSet should keep both keys when scopes differ")
		void shouldKeepBothKeysInTreeSet() {
			final EntityIndexKey liveKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, CATEGORY
			);
			final EntityIndexKey archivedKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.ARCHIVED, CATEGORY
			);

			final TreeSet<EntityIndexKey> set = new TreeSet<>();
			set.add(liveKey);
			set.add(archivedKey);

			assertEquals(2, set.size());
		}

		@Test
		@DisplayName("should provide consistent total ordering for type + scope + discriminator")
		void shouldProvideTotalOrdering() {
			final EntityIndexKey a = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "A_REF"
			);
			final EntityIndexKey b = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "B_REF"
			);
			final EntityIndexKey aArchived = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.ARCHIVED, "A_REF"
			);

			assertTrue(a.compareTo(b) < 0);
			assertNotEquals(0, a.compareTo(aArchived));
		}
	}

	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

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
		@Tag(LONG_RUNNING_TEST)
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
}
