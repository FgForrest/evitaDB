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
import io.evitadb.spi.store.engine.model.CatalogFolderBinding;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.CatalogGenerationPeak;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.RetiredFolder;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;

/**
 * This {@link Serializer} implementation reads/writes {@link EngineState} from/to binary format.
 *
 * It handles the serialization and deserialization of engine state information including:
 * - Storage protocol version
 * - Engine version
 * - Introduction timestamp
 * - WAL file reference
 * - Active, inactive, read-only, and missing catalogs
 * - Catalog-to-folder bindings, retired folders awaiting deletion and per-catalog generation peaks
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
		final LogFileRecordReference walFileReference = (LogFileRecordReference) engineState.walReference();
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
			// Write cumulative checksum for WAL integrity verification
			output.writeLong(walFileReference.cumulativeChecksum());
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

		// Write missing catalogs — catalogs whose on-disk folder is no longer present
		output.writeVarInt(engineState.missingCatalogs().length, true);
		for (String catalogName : engineState.missingCatalogs()) {
			output.writeString(catalogName);
		}

		// Write the name-to-folder bindings — the engine state's sole authority for where a catalog's data lives
		output.writeVarInt(engineState.catalogFolders().length, true);
		for (final CatalogFolderBinding binding : engineState.catalogFolders()) {
			output.writeString(binding.catalogName());
			output.writeString(binding.folderId().id());
		}

		// Write the tombstones — folders no catalog points at any more, awaiting deletion
		output.writeVarInt(engineState.retiredFolders().length, true);
		for (final RetiredFolder retiredFolder : engineState.retiredFolders()) {
			output.writeString(retiredFolder.catalogName());
			output.writeString(retiredFolder.folderId().id());
		}

		// Write the generation peaks — highest folder generation ever handed out per catalog name
		output.writeVarInt(engineState.generationPeaks().length, true);
		for (final CatalogGenerationPeak peak : engineState.generationPeaks()) {
			output.writeString(peak.catalogName());
			output.writeVarInt(peak.peak(), true);
		}
	}

	@Nonnull
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
			// Read cumulative checksum for WAL integrity verification
			final long cumulativeChecksum = input.readLong();
			walFileReference = new LogFileRecordReference(
				EnginePersistenceService::getWalFileName,
				fileIndex,
				new FileLocation(startingPosition, recordLength),
				cumulativeChecksum
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

		// Read missing catalogs. The backward-compatible serializers handle the legacy formats where this section
		// and the folder sections below are absent — readers of this serializer always see every tail present
		// because the writer above always emits them.
		final int missingCatalogsCount = input.readVarInt(true);
		final String[] missingCatalogs = new String[missingCatalogsCount];
		for (int i = 0; i < missingCatalogsCount; i++) {
			missingCatalogs[i] = input.readString();
		}

		// Read the name-to-folder bindings
		final int catalogFoldersCount = input.readVarInt(true);
		final CatalogFolderBinding[] catalogFolders = new CatalogFolderBinding[catalogFoldersCount];
		for (int i = 0; i < catalogFoldersCount; i++) {
			final String catalogName = input.readString();
			catalogFolders[i] = new CatalogFolderBinding(catalogName, new CatalogFolderId(input.readString()));
		}

		// Read the tombstones
		final int retiredFoldersCount = input.readVarInt(true);
		final RetiredFolder[] retiredFolders = new RetiredFolder[retiredFoldersCount];
		for (int i = 0; i < retiredFoldersCount; i++) {
			final String catalogName = input.readString();
			retiredFolders[i] = new RetiredFolder(catalogName, new CatalogFolderId(input.readString()));
		}

		// Read the generation peaks
		final int generationPeaksCount = input.readVarInt(true);
		final CatalogGenerationPeak[] generationPeaks = new CatalogGenerationPeak[generationPeaksCount];
		for (int i = 0; i < generationPeaksCount; i++) {
			final String catalogName = input.readString();
			generationPeaks[i] = new CatalogGenerationPeak(catalogName, input.readVarInt(true));
		}

		// Create and return a new EngineState with the read values
		return new EngineState<>(
			storageProtocolVersion,
			version,
			introducedAt,
			walFileReference,
			activeCatalogs,
			inactiveCatalogs,
			readOnlyCatalogs,
			missingCatalogs,
			catalogFolders,
			retiredFolders,
			generationPeaks
		);
	}
}
