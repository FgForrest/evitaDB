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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This class contains tests for the {@link SchemaSite} class, focusing on the {@link SchemaSite#compareTo(SchemaSite)} method
 * implementation with various inputs including NULL values and differently ordered array inputs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class SchemaSiteTest {

    @Test
    void shouldCompareEqualSchemaSites() {
        final SchemaSite site1 = SchemaSite.builder()
                .entityType("product")
                .operation(Operation.UPSERT, Operation.REMOVE)
                .containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
                .build();

        final SchemaSite site2 = SchemaSite.builder()
                .entityType("product")
                .operation(Operation.UPSERT, Operation.REMOVE)
                .containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
                .build();

        assertEquals(0, site1.compareTo(site2));
        assertEquals(0, site2.compareTo(site1));
    }

    @Test
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
    void shouldCompareSchemaSitesWithDifferentOperations() {
        final SchemaSite site1 = SchemaSite.builder()
                .entityType("product")
                .operation(Operation.UPSERT)
                .build();

        final SchemaSite site2 = SchemaSite.builder()
                .entityType("product")
                .operation(Operation.REMOVE)
                .build();

        // Operations are sorted in the constructor, so we need to check the actual values
        // UPSERT comes before REMOVE in enum definition order
        assertTrue(site1.compareTo(site2) < 0);
        assertTrue(site2.compareTo(site1) > 0);
    }

    @Test
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
    void shouldCompareSchemaSitesWithDifferentlyOrderedOperations() {
        final SchemaSite site1 = SchemaSite.builder()
                .entityType("product")
                .operation(Operation.UPSERT, Operation.REMOVE)
                .build();

        final SchemaSite site2 = SchemaSite.builder()
                .entityType("product")
                .operation(Operation.REMOVE, Operation.UPSERT)
                .build();

        // Operations are sorted in the constructor, so these should be equal
        assertEquals(0, site1.compareTo(site2));
        assertEquals(0, site2.compareTo(site1));
    }

    @Test
    void shouldCompareSchemaSitesWithDifferentContainerTypes() {
        final SchemaSite site1 = SchemaSite.builder()
                .entityType("product")
                .containerType(ContainerType.ENTITY)
                .build();

        final SchemaSite site2 = SchemaSite.builder()
                .entityType("product")
                .containerType(ContainerType.ATTRIBUTE)
                .build();

        // ContainerTypes are sorted in the constructor, so we need to check the actual values
        // ENTITY comes before ATTRIBUTE in enum definition order
        assertTrue(site1.compareTo(site2) < 0);
        assertTrue(site2.compareTo(site1) > 0);
    }

    @Test
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
    void shouldCompareSchemaSitesWithDifferentlyOrderedContainerTypes() {
        final SchemaSite site1 = SchemaSite.builder()
                .entityType("product")
                .containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
                .build();

        final SchemaSite site2 = SchemaSite.builder()
                .entityType("product")
                .containerType(ContainerType.ATTRIBUTE, ContainerType.ENTITY)
                .build();

        // ContainerTypes are sorted in the constructor, so these should be equal
        assertEquals(0, site1.compareTo(site2));
        assertEquals(0, site2.compareTo(site1));
    }

    @Test
    void shouldCreateSchemaSiteUsingBuilder() {
        final SchemaSite site = SchemaSite.builder()
                .entityType("product")
                .operation(Operation.REMOVE, Operation.UPSERT)
                .containerType(ContainerType.ATTRIBUTE, ContainerType.ENTITY)
                .build();

        assertEquals("product", site.entityType());
        assertArrayEquals(new Operation[]{Operation.UPSERT, Operation.REMOVE}, site.operation());
        assertArrayEquals(new ContainerType[]{ContainerType.ENTITY, ContainerType.ATTRIBUTE}, site.containerType());
    }

    @Test
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
