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
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.store.engine.EngineKryoConfigurer;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.model.FileLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SERIALIZATION;

/**
 * This test verifies the correctness of the {@link EngineStateSerializer} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("EngineStateSerializer should serialize and deserialize")
@Tag(STORAGE)
@Tag(MANAGEMENT)
@Tag(SERIALIZATION)
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
		final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
			.storageProtocolVersion(1)
			.version(2L)
			.walFileReference(
				new LogFileRecordReference(
					EnginePersistenceService::getWalFileName,
					3,
					new FileLocation(100L, 200),
					123L
				)
			)
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
		final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
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
		final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
			.storageProtocolVersion(1)
			.version(2L)
			.walFileReference(
				new LogFileRecordReference(
					EnginePersistenceService::getWalFileName,
					3,
					new FileLocation(100L, 200),
					123L
				)
			)
			.activeCatalogs(new String[0])
			.inactiveCatalogs(new String[0])
			.readOnlyCatalogs(new String[0])
			.build();

		// Test serialization and deserialization
		assertSerializationRound(engineState);
	}

	@Test
	@DisplayName("EngineState with missing catalogs")
	void shouldSerializeAndDeserializeEngineStateWithMissingCatalogs() {
		// The `missingCatalogs` array must round-trip through Kryo so the engine can persist the divergence between
		// registered catalogs and folders on disk.
		final EngineState<LogFileRecordReference> engineState = EngineState.<LogFileRecordReference>builder()
			.storageProtocolVersion(1)
			.version(2L)
			.walFileReference(
				new LogFileRecordReference(
					EnginePersistenceService::getWalFileName,
					3,
					new FileLocation(100L, 200),
					123L
				)
			)
			.activeCatalogs(new String[]{"catalogA"})
			.inactiveCatalogs(new String[]{"catalogB"})
			.readOnlyCatalogs(new String[0])
			.missingCatalogs(new String[]{"missingA", "missingB"})
			.build();

		assertSerializationRound(engineState);
	}

	@Test
	@DisplayName("Legacy payload deserializes with empty missingCatalogs substituted")
	void shouldDeserializeLegacyEngineStateAndSubstituteEmptyMissingCatalogs() {
		// Verifies the backward-compat path of `EngineStateSerializer_2026_1`: a bootstrap file written before the
		// `missingCatalogs` field was introduced has no trailing `missingCatalogs` section. When such a payload is
		// read back today, the legacy serializer must reconstruct all original fields and substitute an empty
		// (non-null) missing-catalogs array so callers see the new record shape transparently.
		final OffsetDateTime introducedAt = OffsetDateTime.parse("2025-06-15T10:30:00+02:00");
		final LogFileRecordReference walReference = new LogFileRecordReference(
			EnginePersistenceService::getWalFileName,
			3,
			new FileLocation(100L, 200),
			123L
		);
		final String[] activeCatalogs = {"catalogA", "catalogB"};
		final String[] inactiveCatalogs = {"catalogC"};
		final String[] readOnlyCatalogs = {"catalogD", "catalogE"};

		// Hand-craft a byte stream that matches the pre-Part-C on-disk layout — identical to the
		// current layout except that the trailing `missingCatalogs` section is absent. The legacy
		// serializer's `write` method intentionally throws, so we cannot simply round-trip an
		// object through it; producing the bytes by hand is the only faithful reproduction of a
		// real pre-Part-C bootstrap file.
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			output.writeVarInt(1, true);
			output.writeVarLong(2L, true);
			this.kryo.writeObject(output, introducedAt);

			// WAL reference present (boolean marker + fileIndex + startingPosition + recordLength + checksum).
			output.writeBoolean(true);
			output.writeVarInt(walReference.fileIndex(), true);
			output.writeVarLong(walReference.fileLocation().startingPosition(), true);
			output.writeVarInt(walReference.fileLocation().recordLength(), true);
			output.writeLong(walReference.cumulativeChecksum());

			// Active, inactive and read-only catalog arrays — no trailing missing-catalogs section.
			output.writeVarInt(activeCatalogs.length, true);
			for (final String catalogName : activeCatalogs) {
				output.writeString(catalogName);
			}
			output.writeVarInt(inactiveCatalogs.length, true);
			for (final String catalogName : inactiveCatalogs) {
				output.writeString(catalogName);
			}
			output.writeVarInt(readOnlyCatalogs.length, true);
			for (final String catalogName : readOnlyCatalogs) {
				output.writeString(catalogName);
			}
		}

		final EngineStateSerializer_2026_1 legacySerializer = new EngineStateSerializer_2026_1();
		final EngineState<?> deserialized;
		try (final Input input = new Input(os.toByteArray())) {
			deserialized = legacySerializer.read(this.kryo, input, EngineState.class);
		}

		// All original fields must survive the legacy deserialization unchanged …
		assertEquals(1, deserialized.storageProtocolVersion());
		assertEquals(2L, deserialized.version());
		assertEquals(introducedAt, deserialized.introducedAt());
		assertEquals(walReference, deserialized.walReference());
		assertArrayEquals(activeCatalogs, deserialized.activeCatalogs());
		assertArrayEquals(inactiveCatalogs, deserialized.inactiveCatalogs());
		assertArrayEquals(readOnlyCatalogs, deserialized.readOnlyCatalogs());

		// … and the missing-catalogs bucket must be present but empty, so downstream code can
		// treat pre-Part-C and post-Part-C payloads uniformly.
		assertNotNull(deserialized.missingCatalogs());
		assertEquals(0, deserialized.missingCatalogs().length);
	}
}
