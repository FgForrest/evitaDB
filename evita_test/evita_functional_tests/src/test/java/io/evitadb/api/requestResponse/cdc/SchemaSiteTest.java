/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.dataType.ContainerType;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SchemaSite} covering compareTo, builder, and equality semantics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("SchemaSite")
class SchemaSiteTest implements EvitaTestSupport {

	@Nested
	@DisplayName("compareTo")
	class CompareTo {

		@Test
		@DisplayName("should compare equal SchemaSites as zero")
		void shouldCompareEqualSchemaSites() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT, Operation.REMOVE)
				.containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
				.containerName("name", "code")
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT, Operation.REMOVE)
				.containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
				.containerName("name", "code")
				.build();

			assertEquals(0, site1.compareTo(site2));
			assertEquals(0, site2.compareTo(site1));
		}

		@Test
		@DisplayName("should return zero when comparing self")
		void shouldReturnZeroWhenComparingSelf() {
			final SchemaSite site = SchemaSite.builder()
				.entityType("product")
				.build();

			assertEquals(0, site.compareTo(site));
		}

		@Test
		@DisplayName("should order by entity type")
		void shouldCompareSchemaSitesWithDifferentEntityType() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("category")
				.build();

			assertTrue(site1.compareTo(site2) > 0);
			assertTrue(site2.compareTo(site1) < 0);
		}

		@Test
		@DisplayName("should order null entity type before non-null")
		void shouldCompareSchemaSitesWithNullEntityType() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType(null)
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should order by operations")
		void shouldCompareSchemaSitesWithDifferentOperations() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT)
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.REMOVE)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should order null operations before non-null")
		void shouldCompareSchemaSitesWithNullOperations() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.operation((Operation[]) null)
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should treat differently ordered operations as equal")
		void shouldCompareSchemaSitesWithDifferentlyOrderedOperations() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT, Operation.REMOVE)
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.REMOVE, Operation.UPSERT)
				.build();

			assertEquals(0, site1.compareTo(site2));
			assertEquals(0, site2.compareTo(site1));
		}

		@Test
		@DisplayName("should order by container types")
		void shouldCompareSchemaSitesWithDifferentContainerTypes() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.containerType(ContainerType.ENTITY)
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.containerType(ContainerType.ATTRIBUTE)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should order null container types before non-null")
		void shouldCompareSchemaSitesWithNullContainerTypes() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.containerType((ContainerType[]) null)
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.containerType(ContainerType.ENTITY)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should treat differently ordered container types as equal")
		void shouldCompareSchemaSitesWithDifferentlyOrderedContainerTypes() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.containerType(ContainerType.ATTRIBUTE, ContainerType.ENTITY)
				.build();

			assertEquals(0, site1.compareTo(site2));
			assertEquals(0, site2.compareTo(site1));
		}

		@Test
		@DisplayName("should order by container names")
		void shouldCompareSchemaSitesWithDifferentContainerNames() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.containerName("name")
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.containerName("code")
				.build();

			assertTrue(site1.compareTo(site2) > 0);
			assertTrue(site2.compareTo(site1) < 0);
		}

		@Test
		@DisplayName("should order null container names before non-null")
		void shouldCompareSchemaSitesWithNullContainerNames() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.containerName((String[]) null)
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.containerName("name")
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should treat differently ordered container names as equal")
		void shouldCompareSchemaSitesWithDifferentlyOrderedContainerNames() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.containerName("name", "code")
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.containerName("code", "name")
				.build();

			assertEquals(0, site1.compareTo(site2));
			assertEquals(0, site2.compareTo(site1));
		}

		@Test
		@DisplayName("should compare with ALL SchemaSite")
		void shouldCompareWithAllSchemaSite() {
			final SchemaSite site = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT)
				.containerType(ContainerType.ENTITY)
				.build();

			assertTrue(SchemaSite.ALL.compareTo(site) < 0);
			assertTrue(site.compareTo(SchemaSite.ALL) > 0);
		}
	}

	@Nested
	@DisplayName("Builder")
	class Builder {

		@Test
		@DisplayName("should create SchemaSite with all fields via builder")
		void shouldCreateSchemaSiteUsingBuilder() {
			final SchemaSite site = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.REMOVE, Operation.UPSERT)
				.containerType(ContainerType.ATTRIBUTE, ContainerType.ENTITY)
				.containerName("name", "code")
				.build();

			assertEquals("product", site.entityType());
			assertArrayEquals(new Operation[]{Operation.UPSERT, Operation.REMOVE}, site.operation());
			assertArrayEquals(
				new ContainerType[]{ContainerType.ENTITY, ContainerType.ATTRIBUTE},
				site.containerType()
			);
			assertArrayEquals(new String[]{"code", "name"}, site.containerName());
		}

		@Test
		@DisplayName("should create ALL constant with all fields null")
		void shouldVerifyAllConstantHasAllFieldsNull() {
			assertNull(SchemaSite.ALL.entityType());
			assertNull(SchemaSite.ALL.operation());
			assertNull(SchemaSite.ALL.containerType());
			assertNull(SchemaSite.ALL.containerName());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityAndHashCode {

		@Test
		@DisplayName("should be equal for structurally identical sites")
		void shouldBeEqualForStructurallyIdenticalSites() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT, Operation.REMOVE)
				.containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
				.containerName("name", "code")
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT, Operation.REMOVE)
				.containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
				.containerName("name", "code")
				.build();

			assertEquals(site1, site2);
			assertEquals(site1.hashCode(), site2.hashCode());
		}

		@Test
		@DisplayName("should be not equal when entity type differs")
		void shouldNotBeEqualWhenEntityTypeDiffers() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("category")
				.build();

			assertNotEquals(site1, site2);
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final SchemaSite site = SchemaSite.builder()
				.entityType("product")
				.build();

			assertEquals(site, site);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final SchemaSite site = SchemaSite.builder()
				.entityType("product")
				.build();

			assertNotEquals(null, site);
		}

		@Test
		@DisplayName("should not be equal to different type")
		void shouldNotBeEqualToDifferentType() {
			final SchemaSite site = SchemaSite.builder()
				.entityType("product")
				.build();

			assertNotEquals("not a site", site);
		}

		@Test
		@DisplayName("should treat differently ordered arrays as equal")
		void shouldTreatDifferentlyOrderedArraysAsEqual() {
			final SchemaSite site1 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT, Operation.REMOVE)
				.containerName("z", "a")
				.build();

			final SchemaSite site2 = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.REMOVE, Operation.UPSERT)
				.containerName("a", "z")
				.build();

			assertEquals(site1, site2);
			assertEquals(site1.hashCode(), site2.hashCode());
		}
	}
}
