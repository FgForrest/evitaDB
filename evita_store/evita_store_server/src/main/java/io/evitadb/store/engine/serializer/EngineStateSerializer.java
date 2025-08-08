/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.utils.Assert;

import java.time.OffsetDateTime;

/**
 * This {@link Serializer} implementation reads/writes {@link EngineState} from/to binary format.
 *
 * It handles the serialization and deserialization of engine state information including:
 * - Storage protocol version
 * - Engine version
 * - Introduction timestamp
 * - WAL file reference
 * - Active, inactive, and read-only catalogs
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EngineStateSerializer extends Serializer<EngineState> {

	@Override
	public void write(Kryo kryo, Output output, EngineState engineState) {
		// Write basic engine state properties
		output.writeVarInt(engineState.storageProtocolVersion(), true);
		output.writeVarLong(engineState.version(), true);
		kryo.writeObject(output, engineState.introducedAt());

		// Write WAL file reference if it exists
		final LogFileRecordReference walFileReference = engineState.walFileReference();
		if (walFileReference == null) {
			// Indicate that there's no WAL file reference
			output.writeBoolean(false);
		} else {
			// Indicate that there is a WAL file reference and write its details
			output.writeBoolean(true);
			output.writeVarInt(walFileReference.fileIndex(), true);

			// Ensure file location is not null before writing it
			Assert.isPremiseValid(
				walFileReference.fileLocation() != null, "WAL file reference must have non-null file location!");
			final FileLocation fileLocation = walFileReference.fileLocation();
			output.writeVarLong(fileLocation.startingPosition(), true);
			output.writeVarInt(fileLocation.recordLength(), true);
		}

		// Write active catalogs
		output.writeVarInt(engineState.activeCatalogs().length, true);
		for (String catalogName : engineState.activeCatalogs()) {
			output.writeString(catalogName);
		}

		// Write inactive catalogs
		output.writeVarInt(engineState.inactiveCatalogs().length, true);
		for (String catalogName : engineState.inactiveCatalogs()) {
			output.writeString(catalogName);
		}

		// Write read-only catalogs
		output.writeVarInt(engineState.readOnlyCatalogs().length, true);
		for (String catalogName : engineState.readOnlyCatalogs()) {
			output.writeString(catalogName);
		}
	}

	@Override
	public EngineState read(Kryo kryo, Input input, Class<? extends EngineState> aClass) {
		// Read basic engine state properties
		final int storageProtocolVersion = input.readVarInt(true);
		final long version = input.readVarLong(true);
		final OffsetDateTime introducedAt = kryo.readObject(input, OffsetDateTime.class);

		// Read WAL file reference if it exists
		final LogFileRecordReference walFileReference;
		if (input.readBoolean()) {
			// WAL file reference exists, read its details
			final int fileIndex = input.readVarInt(true);
			final long startingPosition = input.readVarLong(true);
			final int recordLength = input.readVarInt(true);
			walFileReference = new LogFileRecordReference(
				EnginePersistenceService::getWalFileName,
				fileIndex,
				new FileLocation(startingPosition, recordLength)
			);
		} else {
			// No WAL file reference
			walFileReference = null;
		}

		// Read active catalogs
		final int activeCatalogsCount = input.readVarInt(true);
		final String[] activeCatalogs = new String[activeCatalogsCount];
		for (int i = 0; i < activeCatalogsCount; i++) {
			activeCatalogs[i] = input.readString();
		}

		// Read inactive catalogs
		final int inactiveCatalogsCount = input.readVarInt(true);
		final String[] inactiveCatalogs = new String[inactiveCatalogsCount];
		for (int i = 0; i < inactiveCatalogsCount; i++) {
			inactiveCatalogs[i] = input.readString();
		}

		// Read read-only catalogs
		final int readOnlyCatalogsCount = input.readVarInt(true);
		final String[] readOnlyCatalogs = new String[readOnlyCatalogsCount];
		for (int i = 0; i < readOnlyCatalogsCount; i++) {
			readOnlyCatalogs[i] = input.readString();
		}

		// Create and return a new EngineState with the read values
		return new EngineState(
			storageProtocolVersion,
			version,
			introducedAt,
			walFileReference,
			activeCatalogs,
			inactiveCatalogs,
			readOnlyCatalogs
		);
	}
}
