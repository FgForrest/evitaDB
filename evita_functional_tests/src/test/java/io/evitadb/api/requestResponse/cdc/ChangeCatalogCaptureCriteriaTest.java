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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This class contains tests for the {@link ChangeCatalogCaptureCriteria} class, focusing on the
 * {@link ChangeCatalogCaptureCriteria#compareTo(ChangeCatalogCaptureCriteria)} method implementation
 * with various inputs including NULL values and different types of sites.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class ChangeCatalogCaptureCriteriaTest {

    @Test
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
    void shouldCompareCriteriaWithDifferentAreas() {
        final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
                .area(CaptureArea.SCHEMA)
                .site(SchemaSite.builder().entityType("product").build())
                .build();

        final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
                .area(CaptureArea.DATA)
                .site(DataSite.builder().entityType("product").build())
                .build();

        // SCHEMA comes before DATA in enum definition order
        assertTrue(criteria1.compareTo(criteria2) < 0);
        assertTrue(criteria2.compareTo(criteria1) > 0);
    }

    @Test
    void shouldCompareCriteriaWithNullArea() {
        final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
                .area(null)
                .site(SchemaSite.builder().entityType("product").build())
                .build();

        final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
                .area(CaptureArea.SCHEMA)
                .site(SchemaSite.builder().entityType("product").build())
                .build();

        // Null area is less than non-null area
        assertTrue(criteria1.compareTo(criteria2) < 0);
        assertTrue(criteria2.compareTo(criteria1) > 0);
    }

    @Test
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

        // "product" comes after "category" alphabetically
        assertTrue(criteria1.compareTo(criteria2) > 0);
        assertTrue(criteria2.compareTo(criteria1) < 0);
    }

    @Test
    void shouldCompareCriteriaWithSameAreaButDifferentTypesOfSites() {
        final ChangeCatalogCaptureCriteria criteria1 = ChangeCatalogCaptureCriteria.builder()
                .area(CaptureArea.SCHEMA)
                .site(SchemaSite.builder().entityType("product").build())
                .build();

        final ChangeCatalogCaptureCriteria criteria2 = ChangeCatalogCaptureCriteria.builder()
                .area(CaptureArea.DATA)
                .site(DataSite.builder().entityType("product").build())
                .build();

        // Comparison is based on class name, SCHEMA comes before DATA in enum definition order
        assertTrue(criteria1.compareTo(criteria2) < 0);
        assertTrue(criteria2.compareTo(criteria1) > 0);
    }

    @Test
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

        // Primary key 1 comes before 2
        assertTrue(criteria1.compareTo(criteria2) < 0);
        assertTrue(criteria2.compareTo(criteria1) > 0);
    }

    @Test
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

        // UPSERT comes before REMOVE in enum definition order
        assertTrue(criteria1.compareTo(criteria2) < 0);
        assertTrue(criteria2.compareTo(criteria1) > 0);
    }

    @Test
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
