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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for {@link ScopedFilterCapabilities}.
 *
 * The record carries an **array** component, which records compare by reference unless the class says otherwise -
 * and every mutation-combination and schema-diffing path in the codebase relies on value equality here. Those are the
 * cases this test exists for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ScopedFilterCapabilities")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class ScopedFilterCapabilitiesTest {

	@Nested
	@DisplayName("Equality and hash code")
	class EqualityAndHashCode {

		@Test
		@DisplayName("Should compare two distinct arrays holding the same capabilities as equal")
		void shouldCompareDistinctArraysWithSameContentsAsEqual() {
			final ScopedFilterCapabilities first = new ScopedFilterCapabilities(
				Scope.LIVE, FilterIndexCapability.SUBSTRING
			);
			final ScopedFilterCapabilities second = new ScopedFilterCapabilities(
				Scope.LIVE, new FilterIndexCapability[]{FilterIndexCapability.SUBSTRING}
			);
			assertEquals(first, second);
			assertEquals(first.hashCode(), second.hashCode());
		}

		@Test
		@DisplayName("Should not treat carriers of different scopes as equal")
		void shouldNotTreatCarriersOfDifferentScopesAsEqual() {
			assertNotEquals(
				new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING),
				new ScopedFilterCapabilities(Scope.ARCHIVED, FilterIndexCapability.SUBSTRING)
			);
		}

		@Test
		@DisplayName("Should not treat an empty carrier as equal to one declaring a capability")
		void shouldNotTreatEmptyCarrierAsEqualToDeclaringOne() {
			assertNotEquals(
				new ScopedFilterCapabilities(Scope.LIVE),
				new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
			);
		}

		@Test
		@DisplayName("Should not be affected by mutation of the array it was built from")
		void shouldNotBeAffectedByMutationOfTheArrayItWasBuiltFrom() {
			// the carrier is a component of `@Immutable` mutations whose equals/hashCode reach into this array, and
			// whose identity drives mutation combination and change detection - a caller that kept the array it
			// passed in would otherwise be able to change a validated carrier's contents, and the hash code of any
			// mutation already filed in a hash-keyed collection along with it
			final FilterIndexCapability[] source = {FilterIndexCapability.SUBSTRING};
			final ScopedFilterCapabilities carrier = new ScopedFilterCapabilities(Scope.LIVE, source);
			final int hashCodeBeforeMutation = carrier.hashCode();

			source[0] = null;

			assertEquals(FilterIndexCapability.SUBSTRING, carrier.capabilities()[0]);
			assertEquals(new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING), carrier);
			assertEquals(hashCodeBeforeMutation, carrier.hashCode());
		}
	}

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("Should accept a carrier declaring no capability at all")
		void shouldAcceptCarrierDeclaringNoCapability() {
			final ScopedFilterCapabilities carrier = new ScopedFilterCapabilities(Scope.LIVE);
			assertEquals(Scope.LIVE, carrier.scope());
			assertEquals(0, carrier.capabilities().length);
		}

		@Test
		@DisplayName("Should refuse a null scope, a null capability array and a null capability inside it")
		void shouldRefuseNulls() {
			// asserted on the concrete refusal type rather than on `Exception`: the compact constructor refuses
			// through `Assert.notNull`, and a bare `Exception` would be satisfied just as well by an accidental
			// NullPointerException from anywhere else in construction
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ScopedFilterCapabilities(null, FilterIndexCapability.SUBSTRING)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ScopedFilterCapabilities(Scope.LIVE, (FilterIndexCapability[]) null)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ScopedFilterCapabilities(Scope.LIVE, new FilterIndexCapability[]{null})
			);
		}
	}

	@Nested
	@DisplayName("Rendering")
	class Rendering {

		@Test
		@DisplayName("Should render both the scope and the capabilities in toString")
		void shouldRenderScopeAndCapabilitiesInToString() {
			final String rendered = new ScopedFilterCapabilities(
				Scope.LIVE, FilterIndexCapability.SUBSTRING
			).toString();
			assertTrue(rendered.contains(Scope.LIVE.name()));
			assertTrue(rendered.contains(FilterIndexCapability.SUBSTRING.name()));
			// the default record rendering of an array component is an unhelpful identity hash - guard against a
			// future edit accidentally reinstating it
			assertFalse(rendered.contains("[Lio.evitadb"));
		}
	}

}
