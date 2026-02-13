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
 * Tests for {@link DataSite} covering compareTo, builder, and equality semantics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("DataSite")
class DataSiteTest implements EvitaTestSupport {

	@Nested
	@DisplayName("compareTo")
	class CompareTo {

		@Test
		@DisplayName("should compare equal DataSites as zero")
		void shouldCompareEqualDataSites() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
				.operation(Operation.UPSERT, Operation.REMOVE)
				.containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
				.containerName("name", "code")
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
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
			final DataSite site = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
				.build();

			assertEquals(0, site.compareTo(site));
		}

		@Test
		@DisplayName("should order by entity type")
		void shouldCompareDataSitesWithDifferentEntityType() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("category")
				.build();

			assertTrue(site1.compareTo(site2) > 0);
			assertTrue(site2.compareTo(site1) < 0);
		}

		@Test
		@DisplayName("should order null entity type before non-null")
		void shouldCompareDataSitesWithNullEntityType() {
			final DataSite site1 = DataSite.builder()
				.entityType(null)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should order by entity primary key")
		void shouldCompareDataSitesWithDifferentEntityPrimaryKey() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(2)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should order null entity primary key before non-null")
		void shouldCompareDataSitesWithNullEntityPrimaryKey() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(null)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should order by operations")
		void shouldCompareDataSitesWithDifferentOperations() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.operation(Operation.REMOVE)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should order null operations before non-null")
		void shouldCompareDataSitesWithNullOperations() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.operation((Operation[]) null)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should treat differently ordered operations as equal")
		void shouldCompareDataSitesWithDifferentlyOrderedOperations() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT, Operation.REMOVE)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.operation(Operation.REMOVE, Operation.UPSERT)
				.build();

			assertEquals(0, site1.compareTo(site2));
			assertEquals(0, site2.compareTo(site1));
		}

		@Test
		@DisplayName("should order by container types")
		void shouldCompareDataSitesWithDifferentContainerTypes() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.containerType(ContainerType.ENTITY)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.containerType(ContainerType.ATTRIBUTE)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should order null container types before non-null")
		void shouldCompareDataSitesWithNullContainerTypes() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.containerType((ContainerType[]) null)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.containerType(ContainerType.ENTITY)
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should treat differently ordered container types as equal")
		void shouldCompareDataSitesWithDifferentlyOrderedContainerTypes() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.containerType(ContainerType.ATTRIBUTE, ContainerType.ENTITY)
				.build();

			assertEquals(0, site1.compareTo(site2));
			assertEquals(0, site2.compareTo(site1));
		}

		@Test
		@DisplayName("should order by container names")
		void shouldCompareDataSitesWithDifferentContainerNames() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.containerName("name")
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.containerName("code")
				.build();

			assertTrue(site1.compareTo(site2) > 0);
			assertTrue(site2.compareTo(site1) < 0);
		}

		@Test
		@DisplayName("should order null container names before non-null")
		void shouldCompareDataSitesWithNullContainerNames() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.containerName((String[]) null)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.containerName("name")
				.build();

			assertTrue(site1.compareTo(site2) < 0);
			assertTrue(site2.compareTo(site1) > 0);
		}

		@Test
		@DisplayName("should treat differently ordered container names as equal")
		void shouldCompareDataSitesWithDifferentlyOrderedContainerNames() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.containerName("name", "code")
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.containerName("code", "name")
				.build();

			assertEquals(0, site1.compareTo(site2));
			assertEquals(0, site2.compareTo(site1));
		}

		@Test
		@DisplayName("should compare with ALL DataSite")
		void shouldCompareWithAllDataSite() {
			final DataSite site = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
				.operation(Operation.UPSERT)
				.containerType(ContainerType.ENTITY)
				.containerName("name")
				.build();

			assertTrue(DataSite.ALL.compareTo(site) < 0);
			assertTrue(site.compareTo(DataSite.ALL) > 0);
		}
	}

	@Nested
	@DisplayName("Builder")
	class Builder {

		@Test
		@DisplayName("should create DataSite with all fields via builder")
		void shouldCreateDataSiteUsingBuilder() {
			final DataSite site = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
				.operation(Operation.REMOVE, Operation.UPSERT)
				.containerType(ContainerType.ATTRIBUTE, ContainerType.ENTITY)
				.containerName("name", "code")
				.build();

			assertEquals("product", site.entityType());
			assertEquals(Integer.valueOf(1), site.entityPrimaryKey());
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
			assertNull(DataSite.ALL.entityType());
			assertNull(DataSite.ALL.entityPrimaryKey());
			assertNull(DataSite.ALL.operation());
			assertNull(DataSite.ALL.containerType());
			assertNull(DataSite.ALL.containerName());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityAndHashCode {

		@Test
		@DisplayName("should be equal for structurally identical sites")
		void shouldBeEqualForStructurallyIdenticalSites() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
				.operation(Operation.UPSERT, Operation.REMOVE)
				.containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
				.containerName("name", "code")
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.entityPrimaryKey(1)
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
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("category")
				.build();

			assertNotEquals(site1, site2);
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final DataSite site = DataSite.builder()
				.entityType("product")
				.build();

			assertEquals(site, site);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final DataSite site = DataSite.builder()
				.entityType("product")
				.build();

			assertNotEquals(null, site);
		}

		@Test
		@DisplayName("should not be equal to different type")
		void shouldNotBeEqualToDifferentType() {
			final DataSite site = DataSite.builder()
				.entityType("product")
				.build();

			assertNotEquals("not a site", site);
		}

		@Test
		@DisplayName("should treat differently ordered arrays as equal")
		void shouldTreatDifferentlyOrderedArraysAsEqual() {
			final DataSite site1 = DataSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT, Operation.REMOVE)
				.build();

			final DataSite site2 = DataSite.builder()
				.entityType("product")
				.operation(Operation.REMOVE, Operation.UPSERT)
				.build();

			// arrays are sorted in constructor, so these should be equal
			assertEquals(site1, site2);
			assertEquals(site1.hashCode(), site2.hashCode());
		}
	}
}
