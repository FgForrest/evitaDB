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

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.ConstraintPropertyType;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.Map.Entry;
import java.util.Optional;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for [ConstraintProcessingUtils] verifying the four mapping tables that wire together
 * `ConstraintPropertyType` ↔ key prefix ↔ `ConstraintDomain`. Pins the `GROUP` ↔ `"group"` ↔
 * `GROUP_ENTITY` triple and the structural invariant that every `ConstraintPropertyType` has a
 * default domain — a missing entry in `PROPERTY_TYPE_TO_DOMAIN` for a newly added property type
 * would silently break key resolution and is caught here as a failing parametric test.
 *
 * @author JNO, FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("ConstraintProcessingUtils mappings")
class ConstraintProcessingUtilsTest {

	@Nested
	@DisplayName("Group property-type mappings")
	class GroupMappingsTest {

		@Test
		@DisplayName("should map a `group`-prefixed key (e.g. `groupHaving`) back to GROUP property type")
		void shouldReturnGroupPropertyTypeForGroupPrefixedKey() {
			final Entry<String, ConstraintPropertyType> entry = ConstraintProcessingUtils.getPropertyTypeFromPrefix("groupHaving");
			assertEquals("group", entry.getKey());
			assertEquals(ConstraintPropertyType.GROUP, entry.getValue());
		}

		@Test
		@DisplayName("should treat GROUP_ENTITY domain's fallback property type as GROUP")
		void shouldReturnGroupPropertyTypeForGroupEntityDomain() {
			final ConstraintPropertyType propertyType =
				ConstraintProcessingUtils.getFallbackPropertyTypeForDomain(ConstraintDomain.GROUP_ENTITY);
			assertEquals(ConstraintPropertyType.GROUP, propertyType);
		}

		@Test
		@DisplayName("should round-trip GROUP property type through prefix and domain consistently")
		void shouldRoundTripGroupPropertyType() {
			// prefix → propertyType → domain → propertyType — every hop must preserve identity for GROUP
			final String prefix = ConstraintProcessingUtils.getPrefixForPropertyType(ConstraintPropertyType.GROUP).orElseThrow();
			final ConstraintPropertyType viaPrefix = ConstraintProcessingUtils.getPropertyTypeFromPrefix(prefix).getValue();
			final ConstraintDomain domain = ConstraintProcessingUtils.getDomainForPropertyType(viaPrefix);
			final ConstraintPropertyType viaDomain = ConstraintProcessingUtils.getFallbackPropertyTypeForDomain(domain);

			assertSame(ConstraintPropertyType.GROUP, viaPrefix);
			assertSame(ConstraintDomain.GROUP_ENTITY, domain);
			assertSame(ConstraintPropertyType.GROUP, viaDomain);
		}
	}

	@Nested
	@DisplayName("Bijection invariants for all property types")
	class BijectionTest {

		@ParameterizedTest
		@EnumSource(ConstraintPropertyType.class)
		@DisplayName("should have a registered prefix and round-trip to itself for every ConstraintPropertyType")
		void shouldHaveBijectionForEveryPropertyType(@Nonnull final ConstraintPropertyType propertyType) {
			// Every property type must have a prefix mapping. GENERIC has the empty prefix, which is
			// explicitly used as the fallback by `getPropertyTypeFromPrefix` — so we guard the
			// round-trip step against the empty-prefix case (any string would match).
			final Optional<String> prefix = ConstraintProcessingUtils.getPrefixForPropertyType(propertyType);
			assertTrue(prefix.isPresent(), "Missing prefix mapping for ConstraintPropertyType=" + propertyType);

			final String prefixValue = prefix.get();
			if (!prefixValue.isEmpty()) {
				// non-empty prefixes must round-trip exactly to the same property type
				final Entry<String, ConstraintPropertyType> entry =
					ConstraintProcessingUtils.getPropertyTypeFromPrefix(prefixValue);
				assertEquals(prefixValue, entry.getKey());
				assertEquals(propertyType, entry.getValue());
			} else {
				// GENERIC case: empty prefix is the fallback when nothing else matches
				assertSame(ConstraintPropertyType.GENERIC, propertyType);
			}
		}

		@ParameterizedTest
		@EnumSource(ConstraintPropertyType.class)
		@DisplayName("should have a registered default domain for every ConstraintPropertyType")
		void shouldHaveEveryEnumValueMappedToDomain(@Nonnull final ConstraintPropertyType propertyType) {
			// A property type without a default-domain entry would silently break key resolution.
			// The check throws if the mapping is missing, and the assertion below pins it as a
			// regression guard.
			final ConstraintDomain domain = ConstraintProcessingUtils.getDomainForPropertyType(propertyType);
			assertNotNull(domain, "Missing default-domain mapping for ConstraintPropertyType=" + propertyType);
		}
	}

	@Nested
	@DisplayName("Defensive errors")
	class DefensiveErrorsTest {

		@Test
		@DisplayName("should throw ExternalApiInternalError on getFallbackPropertyTypeForDomain with a dynamic domain")
		void shouldThrowOnDynamicDomain() {
			// DEFAULT and HIERARCHY_TARGET are flagged isDynamic=true and have no static mapping
			// — they must not silently return null, the call must fail with a clear message
			final ExternalApiInternalError ex = assertThrows(
				ExternalApiInternalError.class,
				() -> ConstraintProcessingUtils.getFallbackPropertyTypeForDomain(ConstraintDomain.DEFAULT)
			);
			assertTrue(
				ex.getMessage().contains("Dynamic domain"),
				"Unexpected message: " + ex.getMessage()
			);
		}
	}
}
