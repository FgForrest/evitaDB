/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.requestResponse;

import io.evitadb.api.requestResponse.EvitaRequest.AttributeRequest;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.chunk.NoTransformer;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for inner record types of {@link EvitaRequest}:
 * {@link RequirementContext}, {@link AttributeRequest},
 * and {@link ReferenceContentKey}.
 *
 * @author evitaDB
 */
@DisplayName("EvitaRequest inner records")
class EvitaRequestInnerRecordsTest {

	@Nested
	@DisplayName("RequirementContext")
	class RequirementContextTest {

		/**
		 * Verifies attributeRequest returns empty when no
		 * attribute content.
		 */
		@Test
		@DisplayName(
			"attributeRequest returns empty for null"
		)
		void shouldReturnEmptyAttributeRequest() {
			final RequirementContext ctx =
				new RequirementContext(
					ManagedReferencesBehaviour.ANY,
					null,
					null, null, null, null,
					NoTransformer.INSTANCE
				);

			final AttributeRequest attrReq =
				ctx.attributeRequest();

			assertNotNull(attrReq);
			assertTrue(attrReq.attributeSet().isEmpty());
			assertFalse(attrReq.isRequiresEntityAttributes());
		}

		/**
		 * Verifies attributeRequest returns populated set.
		 */
		@Test
		@DisplayName("attributeRequest returns attributes")
		void shouldReturnPopulatedAttributeRequest() {
			final RequirementContext ctx =
				new RequirementContext(
					ManagedReferencesBehaviour.ANY,
					attributeContent("name", "code"),
					null, null, null, null,
					NoTransformer.INSTANCE
				);

			final AttributeRequest attrReq =
				ctx.attributeRequest();

			assertTrue(attrReq.isRequiresEntityAttributes());
			assertEquals(
				2, attrReq.attributeSet().size()
			);
			assertTrue(
				attrReq.attributeSet().contains("name")
			);
			assertTrue(
				attrReq.attributeSet().contains("code")
			);
		}

		/**
		 * Verifies requiresInit returns false for minimal
		 * context.
		 */
		@Test
		@DisplayName("requiresInit false for minimal")
		void shouldReturnFalseRequiresInit() {
			final RequirementContext ctx =
				new RequirementContext(
					ManagedReferencesBehaviour.ANY,
					null,
					null, null, null, null,
					NoTransformer.INSTANCE
				);

			assertFalse(ctx.requiresInit());
		}

		/**
		 * Verifies requiresInit returns true when
		 * entityFetch is present.
		 */
		@Test
		@DisplayName("requiresInit true with entityFetch")
		void shouldReturnTrueRequiresInitWithFetch() {
			final RequirementContext ctx =
				new RequirementContext(
					ManagedReferencesBehaviour.ANY,
					null,
					io.evitadb.api.query.QueryConstraints
						.entityFetch(),
					null, null, null,
					NoTransformer.INSTANCE
				);

			assertTrue(ctx.requiresInit());
		}

		/**
		 * Verifies requiresInit returns true when
		 * managed references behaviour is EXISTING.
		 */
		@Test
		@DisplayName(
			"requiresInit true with EXISTING behaviour"
		)
		void shouldReturnTrueRequiresInitExisting() {
			final RequirementContext ctx =
				new RequirementContext(
					ManagedReferencesBehaviour.EXISTING,
					null,
					null, null, null, null,
					NoTransformer.INSTANCE
				);

			assertTrue(ctx.requiresInit());
		}

		/**
		 * Verifies withExtendedAttributeContentRequirement
		 * creates new context with combined attributes.
		 */
		@Test
		@DisplayName("extends attribute content")
		void shouldExtendAttributeContent() {
			final RequirementContext ctx =
				new RequirementContext(
					ManagedReferencesBehaviour.ANY,
					attributeContent("name"),
					null, null, null, null,
					NoTransformer.INSTANCE
				);

			final RequirementContext extended =
				ctx.withExtendedAttributeContentRequirement(
					attributeContent("code")
				);

			assertNotSame(ctx, extended);
			final AttributeRequest attrReq =
				extended.attributeRequest();
			assertTrue(
				attrReq.isRequiresEntityAttributes()
			);
			// combined should have both
			assertTrue(
				attrReq.attributeSet().contains("name")
			);
			assertTrue(
				attrReq.attributeSet().contains("code")
			);
		}

		/**
		 * Verifies withExtendedAttributeContentRequirement
		 * uses provided content when current is null.
		 */
		@Test
		@DisplayName("uses provided when current null")
		void shouldUseProvidedWhenCurrentNull() {
			final RequirementContext ctx =
				new RequirementContext(
					ManagedReferencesBehaviour.ANY,
					null,
					null, null, null, null,
					NoTransformer.INSTANCE
				);

			final RequirementContext extended =
				ctx.withExtendedAttributeContentRequirement(
					attributeContent("code")
				);

			final AttributeRequest attrReq =
				extended.attributeRequest();
			assertTrue(
				attrReq.isRequiresEntityAttributes()
			);
			assertTrue(
				attrReq.attributeSet().contains("code")
			);
		}
	}

	@Nested
	@DisplayName("AttributeRequest constants")
	class AttributeRequestTest {

		/**
		 * Verifies EMPTY constant.
		 */
		@Test
		@DisplayName("EMPTY has no attributes")
		void shouldHaveEmptyConstant() {
			assertFalse(
				AttributeRequest.EMPTY
					.isRequiresEntityAttributes()
			);
			assertTrue(
				AttributeRequest.EMPTY
					.attributeSet()
					.isEmpty()
			);
		}

		/**
		 * Verifies ALL constant.
		 */
		@Test
		@DisplayName("ALL requires attributes")
		void shouldHaveAllConstant() {
			assertTrue(
				AttributeRequest.ALL
					.isRequiresEntityAttributes()
			);
			// empty set means "all attributes"
			assertTrue(
				AttributeRequest.ALL
					.attributeSet()
					.isEmpty()
			);
		}
	}

	@Nested
	@DisplayName("ReferenceContentKey comparison")
	class ReferenceContentKeyTest {

		/**
		 * Verifies compareTo sorts by reference name.
		 */
		@Test
		@DisplayName("sorts by reference name first")
		void shouldSortByReferenceName() {
			final ReferenceContentKey keyA =
				new ReferenceContentKey(null, "alpha");
			final ReferenceContentKey keyB =
				new ReferenceContentKey(null, "beta");

			assertTrue(keyA.compareTo(keyB) < 0);
			assertTrue(keyB.compareTo(keyA) > 0);
		}

		/**
		 * Verifies compareTo sorts by instance name
		 * when reference names are equal.
		 */
		@Test
		@DisplayName(
			"sorts by instance name as tiebreaker"
		)
		void shouldSortByInstanceNameSecond() {
			final ReferenceContentKey keyA =
				new ReferenceContentKey("a", "ref");
			final ReferenceContentKey keyB =
				new ReferenceContentKey("b", "ref");

			assertTrue(keyA.compareTo(keyB) < 0);
		}

		/**
		 * Verifies null instance name sorts first.
		 */
		@Test
		@DisplayName("null instance name sorts first")
		void shouldSortNullInstanceFirst() {
			final ReferenceContentKey nullKey =
				new ReferenceContentKey(null, "ref");
			final ReferenceContentKey nonNullKey =
				new ReferenceContentKey("inst", "ref");

			assertTrue(nullKey.compareTo(nonNullKey) < 0);
		}

		/**
		 * Verifies equal keys compare as zero.
		 */
		@Test
		@DisplayName("equal keys compare as zero")
		void shouldCompareEqualAsZero() {
			final ReferenceContentKey key1 =
				new ReferenceContentKey("inst", "ref");
			final ReferenceContentKey key2 =
				new ReferenceContentKey("inst", "ref");

			assertEquals(0, key1.compareTo(key2));
		}
	}
}
