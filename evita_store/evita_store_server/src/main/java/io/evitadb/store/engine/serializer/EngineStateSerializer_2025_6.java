/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.shared.model.FileLocation;

import java.time.OffsetDateTime;

/**
 * Backward-compatible {@link Serializer} implementation that reads {@link EngineState} from the version 4 binary
 * format (dev branch, before cumulative CRC32C checksums were added to WAL file references).
 *
 * This serializer handles the old format where {@link LogFileRecordReference} did not include a cumulative checksum
 * field. It creates references with `cumulativeChecksum = 0L`.
 *
 * @deprecated introduced with #1062 and could be removed later when no version prior to 2026.1 is used
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Deprecated(since = "2026.1", forRemoval = true)
public class EngineStateSerializer_2025_6 extends Serializer<EngineState> {

	@Override
	public void write(Kryo kryo, Output output, EngineState object) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
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
			final int fileIndex = input.readVarInt(true);
			final long startingPosition = input.readVarLong(true);
			final int recordLength = input.readVarInt(true);
			// Old format does not have cumulative checksum - use 0L
			walFileReference = new LogFileRecordReference(
				EnginePersistenceService::getWalFileName,
				fileIndex,
				new FileLocation(startingPosition, recordLength),
				0L
			);
		} else {
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

		return new EngineState<>(
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
