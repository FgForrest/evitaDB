/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;

/**
 * Backward-compatible {@link Serializer} implementation that reads {@link EngineState} persisted before catalogs
 * were decoupled from their storage folders — the shape shipped by every release preceding the one that
 * introduced catalog-to-folder bindings. That format has the four catalog-name arrays but none of the folder
 * sections: no name-to-folder bindings, no retired-folder tombstones and no per-catalog generation peaks.
 *
 * The substitution for the absent bindings is **not** an empty map. Under that format a catalog's folder simply
 * *was* its name, so the faithful translation is one identity binding per catalog — which is what the
 * eight-argument {@link EngineState} constructor this reader delegates to produces. Substituting nothing would
 * leave every catalog unbound and force the engine to guess later what the old format had already decided.
 * Tombstones and peaks do substitute empty: no folder had been allocated or retired under the old layout.
 *
 * This serializer only reads — writes always go through the current {@link EngineStateSerializer}.
 *
 * @deprecated kept for backward compatibility; can be removed once no engine state written before
 *             catalog-to-folder bindings were introduced is still in use.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
public class EngineStateSerializer_2026_2 extends Serializer<EngineState> {

	@Override
	public void write(Kryo kryo, Output output, EngineState object) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@Nonnull
	@Override
	public EngineState read(Kryo kryo, Input input, Class<? extends EngineState> aClass) {
		// Read basic engine state properties
		final int storageProtocolVersion = input.readVarInt(true);
		final long version = input.readVarLong(true);
		final OffsetDateTime introducedAt = kryo.readObject(input, OffsetDateTime.class);

		// Read WAL file reference (current format — with cumulative checksum)
		final LogFileRecordReference walFileReference;
		if (input.readBoolean()) {
			final int fileIndex = input.readVarInt(true);
			final long startingPosition = input.readVarLong(true);
			final int recordLength = input.readVarInt(true);
			final long cumulativeChecksum = input.readLong();
			walFileReference = new LogFileRecordReference(
				EnginePersistenceService::getWalFileName,
				fileIndex,
				new FileLocation(startingPosition, recordLength),
				cumulativeChecksum
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

		// Read missing catalogs — the last section this format carries
		final int missingCatalogsCount = input.readVarInt(true);
		final String[] missingCatalogs = new String[missingCatalogsCount];
		for (int i = 0; i < missingCatalogsCount; i++) {
			missingCatalogs[i] = input.readString();
		}

		// The eight-argument constructor is the single place where "no folder map" becomes "folder == name".
		return new EngineState<>(
			storageProtocolVersion,
			version,
			introducedAt,
			walFileReference,
			activeCatalogs,
			inactiveCatalogs,
			readOnlyCatalogs,
			missingCatalogs
		);
	}
}
