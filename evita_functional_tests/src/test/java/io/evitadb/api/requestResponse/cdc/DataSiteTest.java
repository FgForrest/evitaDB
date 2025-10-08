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
 * This class contains tests for the {@link DataSite} class, focusing on the {@link DataSite#compareTo(DataSite)} method
 * implementation with various inputs including NULL values and differently ordered array inputs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class DataSiteTest {

    @Test
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
    void shouldCompareDataSitesWithDifferentOperations() {
        final DataSite site1 = DataSite.builder()
                .entityType("product")
                .operation(Operation.UPSERT)
                .build();

        final DataSite site2 = DataSite.builder()
                .entityType("product")
                .operation(Operation.REMOVE)
                .build();

        // Operations are sorted in the constructor, so we need to check the actual values
        // UPSERT comes before REMOVE in enum definition order
        assertTrue(site1.compareTo(site2) < 0);
        assertTrue(site2.compareTo(site1) > 0);
    }

    @Test
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
    void shouldCompareDataSitesWithDifferentlyOrderedOperations() {
        final DataSite site1 = DataSite.builder()
                .entityType("product")
                .operation(Operation.UPSERT, Operation.REMOVE)
                .build();

        final DataSite site2 = DataSite.builder()
                .entityType("product")
                .operation(Operation.REMOVE, Operation.UPSERT)
                .build();

        // Operations are sorted in the constructor, so these should be equal
        assertEquals(0, site1.compareTo(site2));
        assertEquals(0, site2.compareTo(site1));
    }

    @Test
    void shouldCompareDataSitesWithDifferentContainerTypes() {
        final DataSite site1 = DataSite.builder()
                .entityType("product")
                .containerType(ContainerType.ENTITY)
                .build();

        final DataSite site2 = DataSite.builder()
                .entityType("product")
                .containerType(ContainerType.ATTRIBUTE)
                .build();

        // ContainerTypes are sorted in the constructor, so we need to check the actual values
        // ENTITY comes before ATTRIBUTE in enum definition order
        assertTrue(site1.compareTo(site2) < 0);
        assertTrue(site2.compareTo(site1) > 0);
    }

    @Test
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
    void shouldCompareDataSitesWithDifferentlyOrderedContainerTypes() {
        final DataSite site1 = DataSite.builder()
                .entityType("product")
                .containerType(ContainerType.ENTITY, ContainerType.ATTRIBUTE)
                .build();

        final DataSite site2 = DataSite.builder()
                .entityType("product")
                .containerType(ContainerType.ATTRIBUTE, ContainerType.ENTITY)
                .build();

        // ContainerTypes are sorted in the constructor, so these should be equal
        assertEquals(0, site1.compareTo(site2));
        assertEquals(0, site2.compareTo(site1));
    }

    @Test
    void shouldCompareDataSitesWithDifferentContainerNames() {
        final DataSite site1 = DataSite.builder()
                .entityType("product")
                .containerName("name")
                .build();

        final DataSite site2 = DataSite.builder()
                .entityType("product")
                .containerName("code")
                .build();

        // ContainerNames are sorted in the constructor, so we need to check the actual values
        // "code" comes before "name" in alphabetical order
        assertTrue(site1.compareTo(site2) > 0);
        assertTrue(site2.compareTo(site1) < 0);
    }

    @Test
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
    void shouldCompareDataSitesWithDifferentlyOrderedContainerNames() {
        final DataSite site1 = DataSite.builder()
                .entityType("product")
                .containerName("name", "code")
                .build();

        final DataSite site2 = DataSite.builder()
                .entityType("product")
                .containerName("code", "name")
                .build();

        // ContainerNames are sorted in the constructor, so these should be equal
        assertEquals(0, site1.compareTo(site2));
        assertEquals(0, site2.compareTo(site1));
    }

    @Test
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
        assertArrayEquals(new ContainerType[]{ContainerType.ENTITY, ContainerType.ATTRIBUTE}, site.containerType());
        assertArrayEquals(new String[]{"code", "name"}, site.containerName());
    }

    @Test
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
