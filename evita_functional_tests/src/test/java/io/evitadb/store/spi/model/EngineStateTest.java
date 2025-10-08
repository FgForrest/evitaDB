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

package io.evitadb.store.spi.model;

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * This test verifies the functionality of the {@link EngineState} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EngineState functionality tests")
class EngineStateTest {

    @Test
    @DisplayName("Should create EngineState with default values using builder")
    void shouldCreateEngineStateWithDefaultValues() {
        // when
        final EngineState engineState = EngineState.builder().build();

        // then
        assertEquals(0, engineState.storageProtocolVersion());
        assertEquals(0, engineState.version());
        assertNull(engineState.walFileReference());
        assertNotNull(engineState.activeCatalogs());
        assertEquals(0, engineState.activeCatalogs().length);
        assertNotNull(engineState.inactiveCatalogs());
        assertEquals(0, engineState.inactiveCatalogs().length);
    }

    @Test
    @DisplayName("Should create EngineState with custom values using builder")
    void shouldCreateEngineStateWithCustomValues() {
        // given
        final int storageProtocolVersion = 1;
        final long version = 2L;
        final LogFileRecordReference walFileReference = new LogFileRecordReference(
            index -> CatalogPersistenceService.getWalFileName("test", index),
            3, new FileLocation(100L, 200)
        );
        final String[] activeCatalogs = new String[]{"catalog1", "catalog2"};
        final String[] inactiveCatalogs = new String[]{"catalog3"};

        // when
        final EngineState engineState = EngineState.builder()
                .storageProtocolVersion(storageProtocolVersion)
                .version(version)
                .walFileReference(walFileReference)
                .activeCatalogs(activeCatalogs)
                .inactiveCatalogs(inactiveCatalogs)
                .build();

        // then
        assertEquals(storageProtocolVersion, engineState.storageProtocolVersion());
        assertEquals(version, engineState.version());
        assertEquals(walFileReference, engineState.walFileReference());
        assertArrayEquals(activeCatalogs, engineState.activeCatalogs());
        assertArrayEquals(inactiveCatalogs, engineState.inactiveCatalogs());
    }

    @Test
    @DisplayName("Should create modified EngineState using builder copy")
    void shouldCreateModifiedEngineStateUsingWithMethods() {
        // given
        final EngineState originalState = EngineState.builder()
                .storageProtocolVersion(1)
                .version(2L)
                .walFileReference(new LogFileRecordReference(
                    index -> CatalogPersistenceService.getWalFileName("test", index),
                    3, new FileLocation(100L, 200))
                )
                .activeCatalogs(new String[]{"catalog1", "catalog2"})
                .inactiveCatalogs(new String[]{"catalog3"})
                .build();

        // when
        final EngineState modifiedState1 = EngineState.builder(originalState)
                .storageProtocolVersion(10)
                .build();
        final EngineState modifiedState2 = EngineState.builder(originalState)
                .version(20L)
                .build();
        final EngineState modifiedState3 = EngineState.builder(originalState)
                .walFileReference(new LogFileRecordReference(
                        index -> CatalogPersistenceService.getWalFileName("modified", index),
                        30, new FileLocation(300L, 400)))
                .build();
        final EngineState modifiedState4 = EngineState.builder(originalState)
                .activeCatalogs(new String[]{"modified1"})
                .build();
        final EngineState modifiedState5 = EngineState.builder(originalState)
                .inactiveCatalogs(new String[]{"modified2", "modified3"})
                .build();

        // then
        assertEquals(10, modifiedState1.storageProtocolVersion());
        assertEquals(originalState.version(), modifiedState1.version());
        assertEquals(originalState.walFileReference(), modifiedState1.walFileReference());
        assertArrayEquals(originalState.activeCatalogs(), modifiedState1.activeCatalogs());
        assertArrayEquals(originalState.inactiveCatalogs(), modifiedState1.inactiveCatalogs());

        assertEquals(originalState.storageProtocolVersion(), modifiedState2.storageProtocolVersion());
        assertEquals(20L, modifiedState2.version());
        assertEquals(originalState.walFileReference(), modifiedState2.walFileReference());
        assertArrayEquals(originalState.activeCatalogs(), modifiedState2.activeCatalogs());
        assertArrayEquals(originalState.inactiveCatalogs(), modifiedState2.inactiveCatalogs());

        assertEquals(originalState.storageProtocolVersion(), modifiedState3.storageProtocolVersion());
        assertEquals(originalState.version(), modifiedState3.version());
        assertEquals("modified_0.wal", modifiedState3.walFileReference().walFileNameProvider().apply(0));
        assertEquals(30, modifiedState3.walFileReference().fileIndex());
        assertArrayEquals(originalState.activeCatalogs(), modifiedState3.activeCatalogs());
        assertArrayEquals(originalState.inactiveCatalogs(), modifiedState3.inactiveCatalogs());

        assertEquals(originalState.storageProtocolVersion(), modifiedState4.storageProtocolVersion());
        assertEquals(originalState.version(), modifiedState4.version());
        assertEquals(originalState.walFileReference(), modifiedState4.walFileReference());
        assertArrayEquals(new String[]{"modified1"}, modifiedState4.activeCatalogs());
        assertArrayEquals(originalState.inactiveCatalogs(), modifiedState4.inactiveCatalogs());

        assertEquals(originalState.storageProtocolVersion(), modifiedState5.storageProtocolVersion());
        assertEquals(originalState.version(), modifiedState5.version());
        assertEquals(originalState.walFileReference(), modifiedState5.walFileReference());
        assertArrayEquals(originalState.activeCatalogs(), modifiedState5.activeCatalogs());
        assertArrayEquals(new String[]{"modified2", "modified3"}, modifiedState5.inactiveCatalogs());
    }

    @Test
    @DisplayName("Should create EngineState from existing instance using builder")
    void shouldCreateEngineStateFromExistingInstanceUsingBuilder() {
        // given
        final EngineState originalState = EngineState.builder()
                .storageProtocolVersion(1)
                .version(2L)
                .walFileReference(new LogFileRecordReference(
                    index -> CatalogPersistenceService.getWalFileName("test", index),
                    3, new FileLocation(100L, 200))
                )
                .activeCatalogs(new String[]{"catalog1", "catalog2"})
                .inactiveCatalogs(new String[]{"catalog3"})
                .build();

        // when
        final EngineState modifiedState = EngineState.builder(originalState)
                .storageProtocolVersion(10)
                .activeCatalogs(new String[]{"modified1"})
                .build();

        // then
        assertEquals(10, modifiedState.storageProtocolVersion());
        assertEquals(originalState.version(), modifiedState.version());
        assertEquals(originalState.walFileReference(), modifiedState.walFileReference());
        assertArrayEquals(new String[]{"modified1"}, modifiedState.activeCatalogs());
        assertArrayEquals(originalState.inactiveCatalogs(), modifiedState.inactiveCatalogs());
    }

    @Test
    @DisplayName("Should handle null values correctly")
    void shouldHandleNullValuesCorrectly() {
        // when
        final EngineState engineState = EngineState.builder()
                .walFileReference(null)
                .activeCatalogs(null)
                .inactiveCatalogs(null)
                .build();

        // then
        assertNull(engineState.walFileReference());
        assertNotNull(engineState.activeCatalogs());
        assertEquals(0, engineState.activeCatalogs().length);
        assertNotNull(engineState.inactiveCatalogs());
        assertEquals(0, engineState.inactiveCatalogs().length);
    }
}
