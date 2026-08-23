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

package io.evitadb.index.usage;

import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.Capability;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the one thing a map key has to get right: **two schema elements that are not the same element never collapse
 * into one entry**. The key is a flat (kind, container, name) triple rather than a nested structure, which buys
 * cheapness at the cost of making the collisions non-obvious - an entity attribute and a reference attribute of the
 * same name differ only by a nullable field, and an attribute and a sortable compound of the same name differ only by
 * an enum. Both pairs are asserted below, because conflating either one would report a well-used capability's traffic
 * against an unused one and get the unused flag dropped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityKey
 */
@DisplayName("Schema capability key")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class SchemaCapabilityKeyTest {

	private static final String ATTRIBUTE_NAME = "code";
	private static final String REFERENCE_NAME = "categories";
	private static final String COMPOUND_NAME = "codeAndName";

	@Nested
	@DisplayName("Factories")
	class FactoriesTest {

		@Test
		@DisplayName("An entity attribute sits in no container")
		void shouldDescribeAnEntityAttribute() {
			final SchemaCapabilityKey key = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_NAME, Capability.FILTERABLE, Scope.LIVE
			);

			assertEquals(ElementKind.ATTRIBUTE, key.elementKind());
			assertNull(key.containerName(), "An entity attribute is owned by the entity itself, not by a reference");
			assertEquals(ATTRIBUTE_NAME, key.elementName());
			assertEquals(Capability.FILTERABLE, key.capability());
			assertEquals(Scope.LIVE, key.scope());
		}

		@Test
		@DisplayName("A reference attribute names its reference as its container")
		void shouldDescribeAReferenceAttribute() {
			final SchemaCapabilityKey key = SchemaCapabilityKey.referenceAttribute(
				REFERENCE_NAME, ATTRIBUTE_NAME, Capability.SORTABLE, Scope.ARCHIVED
			);

			assertEquals(ElementKind.ATTRIBUTE, key.elementKind());
			assertEquals(REFERENCE_NAME, key.containerName());
			assertEquals(ATTRIBUTE_NAME, key.elementName());
			assertEquals(Capability.SORTABLE, key.capability());
			assertEquals(Scope.ARCHIVED, key.scope());
		}

		@Test
		@DisplayName("A sortable compound can only ever carry the sort capability")
		void shouldDescribeASortableCompound() {
			final SchemaCapabilityKey entityLevel = SchemaCapabilityKey.sortableCompound(
				null, COMPOUND_NAME, Scope.LIVE
			);
			final SchemaCapabilityKey referenceLevel = SchemaCapabilityKey.sortableCompound(
				REFERENCE_NAME, COMPOUND_NAME, Scope.LIVE
			);

			assertEquals(ElementKind.SORTABLE_COMPOUND, entityLevel.elementKind());
			assertNull(entityLevel.containerName());
			assertEquals(
				Capability.SORTABLE, entityLevel.capability(), "A compound exists to be ordered by, nothing else"
			);

			assertEquals(REFERENCE_NAME, referenceLevel.containerName());
			assertNotEquals(
				entityLevel, referenceLevel,
				"A compound declared on a reference is a different element from one of the same name on the entity"
			);
		}

		@Test
		@DisplayName("A key is rejected rather than built around a missing name")
		void shouldRejectMissingParts() {
			assertThrows(
				NullPointerException.class,
				() -> SchemaCapabilityKey.entityAttribute(null, Capability.FILTERABLE, Scope.LIVE)
			);
			assertThrows(
				NullPointerException.class,
				() -> SchemaCapabilityKey.referenceAttribute(null, ATTRIBUTE_NAME, Capability.FILTERABLE, Scope.LIVE)
			);
			assertThrows(
				NullPointerException.class,
				() -> SchemaCapabilityKey.entityAttribute(ATTRIBUTE_NAME, null, Scope.LIVE)
			);
			assertThrows(
				NullPointerException.class,
				() -> SchemaCapabilityKey.entityAttribute(ATTRIBUTE_NAME, Capability.FILTERABLE, null)
			);
		}

	}

	@Nested
	@DisplayName("Identity")
	class IdentityTest {

		@Test
		@DisplayName("Two keys describing the same element are the same map key")
		void shouldTreatEqualDescriptionsAsOneKey() {
			final SchemaCapabilityKey first = SchemaCapabilityKey.referenceAttribute(
				REFERENCE_NAME, ATTRIBUTE_NAME, Capability.FILTERABLE, Scope.LIVE
			);
			final SchemaCapabilityKey second = SchemaCapabilityKey.referenceAttribute(
				REFERENCE_NAME, ATTRIBUTE_NAME, Capability.FILTERABLE, Scope.LIVE
			);

			assertEquals(first, second);
			assertEquals(
				first.hashCode(), second.hashCode(),
				"Equal keys hashing apart would split one element into two registry entries"
			);
		}

		@Test
		@DisplayName("The container tells an entity attribute apart from a reference attribute of the same name")
		void shouldNotConflateAnEntityAttributeWithAReferenceAttribute() {
			assertNotEquals(
				SchemaCapabilityKey.entityAttribute(ATTRIBUTE_NAME, Capability.FILTERABLE, Scope.LIVE),
				SchemaCapabilityKey.referenceAttribute(
					REFERENCE_NAME, ATTRIBUTE_NAME, Capability.FILTERABLE, Scope.LIVE
				)
			);
		}

		@Test
		@DisplayName("The kind tells an attribute apart from a sortable compound of the same name")
		void shouldNotConflateAnAttributeWithASortableCompound() {
			// the two share a name space in the key and nothing but `elementKind` separates them
			assertNotEquals(
				SchemaCapabilityKey.entityAttribute(COMPOUND_NAME, Capability.SORTABLE, Scope.LIVE),
				SchemaCapabilityKey.sortableCompound(null, COMPOUND_NAME, Scope.LIVE)
			);
		}

		@Test
		@DisplayName("Each capability and each scope of one element counts separately")
		void shouldSeparateCapabilitiesAndScopes() {
			final SchemaCapabilityKey filterInLive = SchemaCapabilityKey.entityAttribute(
				ATTRIBUTE_NAME, Capability.FILTERABLE, Scope.LIVE
			);

			// dropping `filterable()` and dropping `unique()` are different schema mutations, and the archive is
			// maintained by a different set of indexes - neither pair may share a counter
			assertNotEquals(
				filterInLive, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_NAME, Capability.UNIQUE, Scope.LIVE)
			);
			assertNotEquals(
				filterInLive, SchemaCapabilityKey.entityAttribute(ATTRIBUTE_NAME, Capability.FILTERABLE, Scope.ARCHIVED)
			);
		}

	}

}
