/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.engine.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.engine.EngineKryoConfigurer;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies the correctness of the {@link EngineStateSerializer} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("EngineStateSerializer should serialize and deserialize")
class EngineStateSerializerTest {

    /**
     * Create a Kryo instance with all required configurers
     */
    protected final Kryo kryo = KryoFactory.createKryo(
        EngineKryoConfigurer.INSTANCE
    );

    /**
     * Serializes and deserializes the given object and verifies that the deserialized object equals the original.
     *
     * @param object the object to test
     */
    protected void assertSerializationRound(@Nonnull Object object) {
        final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
        try (final Output output = new Output(os, 4_096)) {
            this.kryo.writeObject(output, object);
        }
        try (final Input input = new Input(os.toByteArray())) {
            final Object deserialized = this.kryo.readObject(input, object.getClass());
            assertEquals(object, deserialized);
        }
    }

    @Test
    @DisplayName("EngineState with all fields")
    void shouldSerializeAndDeserializeEngineStateWithAllFields() {
        // Create an EngineState with all fields populated
        final EngineState engineState = EngineState.builder()
            .storageProtocolVersion(1)
            .version(2L)
            .walFileReference(new LogFileRecordReference(
                EnginePersistenceService::getWalFileName,
                3,
                new FileLocation(100L, 200)
            ))
            .activeCatalogs(new String[]{"catalog1", "catalog2"})
            .inactiveCatalogs(new String[]{"catalog3"})
            .readOnlyCatalogs(new String[]{"catalog4", "catalog5"})
            .build();

        // Test serialization and deserialization
        assertSerializationRound(engineState);
    }

    @Test
    @DisplayName("EngineState with null LogFileRecordReference")
    void shouldSerializeAndDeserializeEngineStateWithNullWalFileReference() {
        // Create an EngineState with null LogFileRecordReference
        final EngineState engineState = EngineState.builder()
            .storageProtocolVersion(1)
            .version(2L)
            .walFileReference(null)
            .activeCatalogs(new String[]{"catalog1", "catalog2"})
            .inactiveCatalogs(new String[]{"catalog3"})
            .readOnlyCatalogs(new String[]{"catalog6"})
            .build();

        // Test serialization and deserialization
        assertSerializationRound(engineState);
    }

    @Test
    @DisplayName("EngineState with empty catalog arrays")
    void shouldSerializeAndDeserializeEngineStateWithEmptyCatalogArrays() {
        // Create an EngineState with empty catalog arrays
        final EngineState engineState = EngineState.builder()
            .storageProtocolVersion(1)
            .version(2L)
            .walFileReference(new LogFileRecordReference(
                EnginePersistenceService::getWalFileName,
                3,
                new FileLocation(100L, 200)
            ))
            .activeCatalogs(new String[0])
            .inactiveCatalogs(new String[0])
            .readOnlyCatalogs(new String[0])
            .build();

        // Test serialization and deserialization
        assertSerializationRound(engineState);
    }
}
