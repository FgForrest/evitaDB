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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ChangeCatalogCaptureCriteria} covering compareTo, constructor validation,
 * builder convenience methods, and builder basics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ChangeCatalogCaptureCriteria")
class ChangeCatalogCaptureCriteriaTest implements EvitaTestSupport {

	@Nested
	@DisplayName("compareTo")
	class CompareTo {

		@Test
		@DisplayName("should compare equal criteria as zero")
		void shouldCompareEqualCriteria() {
			final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder()
					.entityType("product")
					.operation(Operation.UPSERT)
					.build())
				.build();

			final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder()
					.entityType("product")
					.operation(Operation.UPSERT)
					.build())
				.build();

			assertEquals(0, criteria1.compareTo(criteria2));
			assertEquals(0, criteria2.compareTo(criteria1));
		}

		@Test
		@DisplayName("should order by area")
		void shouldCompareCriteriaWithDifferentAreas() {
			final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder().entityType("product").build())
				.build();

			final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.DATA)
				.site(DataSite.builder().entityType("product").build())
				.build();

			assertTrue(criteria1.compareTo(criteria2) < 0);
			assertTrue(criteria2.compareTo(criteria1) > 0);
		}

		@Test
		@DisplayName("should order null area before non-null")
		void shouldCompareCriteriaWithNullArea() {
			final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
				.area(null)
				.site(SchemaSite.builder().entityType("product").build())
				.build();

			final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder().entityType("product").build())
				.build();

			assertTrue(criteria1.compareTo(criteria2) < 0);
			assertTrue(criteria2.compareTo(criteria1) > 0);
		}

		@Test
		@DisplayName("should order by site when area is the same")
		void shouldCompareCriteriaWithSameAreaButDifferentSites() {
			final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder()
					.entityType("product")
					.operation(Operation.UPSERT)
					.build())
				.build();

			final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder()
					.entityType("category")
					.operation(Operation.UPSERT)
					.build())
				.build();

			assertTrue(criteria1.compareTo(criteria2) > 0);
			assertTrue(criteria2.compareTo(criteria1) < 0);
		}

		@Test
		@DisplayName("should compare different site types by class name")
		void shouldCompareCriteriaWithSameAreaButDifferentTypesOfSites() {
			final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder().entityType("product").build())
				.build();

			final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.DATA)
				.site(DataSite.builder().entityType("product").build())
				.build();

			assertTrue(criteria1.compareTo(criteria2) < 0);
			assertTrue(criteria2.compareTo(criteria1) > 0);
		}

		@Test
		@DisplayName("should compare criteria with DataSites")
		void shouldCompareCriteriaWithDataSites() {
			final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.DATA)
				.site(DataSite.builder()
					.entityType("product")
					.entityPrimaryKey(1)
					.operation(Operation.UPSERT)
					.containerType(ContainerType.ENTITY)
					.build())
				.build();

			final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.DATA)
				.site(DataSite.builder()
					.entityType("product")
					.entityPrimaryKey(2)
					.operation(Operation.UPSERT)
					.containerType(ContainerType.ENTITY)
					.build())
				.build();

			assertTrue(criteria1.compareTo(criteria2) < 0);
			assertTrue(criteria2.compareTo(criteria1) > 0);
		}

		@Test
		@DisplayName("should compare criteria with SchemaSites")
		void shouldCompareCriteriaWithSchemaSites() {
			final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder()
					.entityType("product")
					.operation(Operation.UPSERT)
					.containerType(ContainerType.ENTITY)
					.build())
				.build();

			final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(SchemaSite.builder()
					.entityType("product")
					.operation(Operation.REMOVE)
					.containerType(ContainerType.ENTITY)
					.build())
				.build();

			assertTrue(criteria1.compareTo(criteria2) < 0);
			assertTrue(criteria2.compareTo(criteria1) > 0);
		}
	}

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorValidation {

		@Test
		@DisplayName("should throw when SCHEMA area gets DataSite")
		void shouldThrowWhenSchemaAreaGetsDataSite() {
			assertThrows(Exception.class, () ->
				new ChangeCatalogCaptureCriteria(
					CaptureArea.SCHEMA,
					DataSite.builder().entityType("product").build()
				)
			);
		}

		@Test
		@DisplayName("should throw when DATA area gets SchemaSite")
		void shouldThrowWhenDataAreaGetsSchemaSite() {
			assertThrows(Exception.class, () ->
				new ChangeCatalogCaptureCriteria(
					CaptureArea.DATA,
					SchemaSite.builder().entityType("product").build()
				)
			);
		}

		@Test
		@DisplayName("should throw when INFRASTRUCTURE area gets non-null site")
		void shouldThrowWhenInfrastructureAreaGetsNonNullSite() {
			assertThrows(Exception.class, () ->
				new ChangeCatalogCaptureCriteria(
					CaptureArea.INFRASTRUCTURE,
					DataSite.builder().entityType("product").build()
				)
			);
		}

		@Test
		@DisplayName("should accept SCHEMA area with SchemaSite")
		void shouldAcceptSchemaAreaWithSchemaSite() {
			final ChangeCatalogCaptureCriteria criteria = new ChangeCatalogCaptureCriteria(
				CaptureArea.SCHEMA,
				SchemaSite.builder().entityType("product").build()
			);
			assertNotNull(criteria);
			assertEquals(CaptureArea.SCHEMA, criteria.area());
		}

		@Test
		@DisplayName("should accept DATA area with DataSite")
		void shouldAcceptDataAreaWithDataSite() {
			final ChangeCatalogCaptureCriteria criteria = new ChangeCatalogCaptureCriteria(
				CaptureArea.DATA,
				DataSite.builder().entityType("product").build()
			);
			assertNotNull(criteria);
			assertEquals(CaptureArea.DATA, criteria.area());
		}

		@Test
		@DisplayName("should accept INFRASTRUCTURE area with null site")
		void shouldAcceptInfrastructureAreaWithNullSite() {
			final ChangeCatalogCaptureCriteria criteria = new ChangeCatalogCaptureCriteria(
				CaptureArea.INFRASTRUCTURE,
				null
			);
			assertNotNull(criteria);
			assertEquals(CaptureArea.INFRASTRUCTURE, criteria.area());
			assertNull(criteria.site());
		}
	}

	@Nested
	@DisplayName("Builder convenience methods")
	class BuilderConvenienceMethods {

		@Test
		@DisplayName("should configure infrastructure area via builder")
		void shouldConfigureInfrastructureArea() {
			final ChangeCatalogCaptureCriteria criteria = ChangeCatalogCaptureCriteria.builder()
				.infrastructureArea()
				.build();

			assertEquals(CaptureArea.INFRASTRUCTURE, criteria.area());
			assertNull(criteria.site());
		}

		@Test
		@DisplayName("should configure data area with default site via builder")
		void shouldConfigureDataAreaWithDefaultSite() {
			final ChangeCatalogCaptureCriteria criteria = ChangeCatalogCaptureCriteria.builder()
				.dataArea()
				.build();

			assertEquals(CaptureArea.DATA, criteria.area());
			assertNotNull(criteria.site());
			assertTrue(criteria.site() instanceof DataSite);
		}

		@Test
		@DisplayName("should configure data area with customized site via builder")
		void shouldConfigureDataAreaWithCustomSite() {
			final ChangeCatalogCaptureCriteria criteria = ChangeCatalogCaptureCriteria.builder()
				.dataArea(builder -> builder.entityType("product"))
				.build();

			assertEquals(CaptureArea.DATA, criteria.area());
			assertNotNull(criteria.site());
			assertEquals("product", ((DataSite) criteria.site()).entityType());
		}

		@Test
		@DisplayName("should configure schema area with default site via builder")
		void shouldConfigureSchemaAreaWithDefaultSite() {
			final ChangeCatalogCaptureCriteria criteria = ChangeCatalogCaptureCriteria.builder()
				.schemaArea()
				.build();

			assertEquals(CaptureArea.SCHEMA, criteria.area());
			assertNotNull(criteria.site());
			assertTrue(criteria.site() instanceof SchemaSite);
		}

		@Test
		@DisplayName("should configure schema area with customized site via builder")
		void shouldConfigureSchemaAreaWithCustomSite() {
			final ChangeCatalogCaptureCriteria criteria = ChangeCatalogCaptureCriteria.builder()
				.schemaArea(builder -> builder.entityType("category"))
				.build();

			assertEquals(CaptureArea.SCHEMA, criteria.area());
			assertNotNull(criteria.site());
			assertEquals("category", ((SchemaSite) criteria.site()).entityType());
		}
	}

	@Nested
	@DisplayName("Builder basics")
	class BuilderBasics {

		@Test
		@DisplayName("should create criteria using builder with area and site")
		void shouldCreateCriteriaUsingBuilder() {
			final SchemaSite schemaSite = SchemaSite.builder()
				.entityType("product")
				.operation(Operation.UPSERT)
				.containerType(ContainerType.ENTITY)
				.build();

			final ChangeCatalogCaptureCriteria criteria = ChangeCatalogCaptureCriteria.builder()
				.area(CaptureArea.SCHEMA)
				.site(schemaSite)
				.build();

			assertEquals(CaptureArea.SCHEMA, criteria.area());
			assertEquals(schemaSite, criteria.site());
		}
	}
}
