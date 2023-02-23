/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.catalog.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.utils.Assert;

import java.util.Map;

/**
 * This {@link Serializer} implementation reads/writes {@link CatalogHeader} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogHeaderSerializer extends AbstractPersistentStorageHeaderSerializer<CatalogHeader> {

	@Override
	public void write(Kryo kryo, Output output, CatalogHeader object) {
		output.writeString(object.getCatalogName());
		output.writeVarLong(object.getVersion(), true);
		output.writeVarInt(object.getLastEntityCollectionPrimaryKey(), true);

		final FileLocation memTableLocation = object.getFileLocation();
		Assert.isPremiseValid(memTableLocation != null, "MemTable location is unexpectedly null!");

		output.writeVarLong(memTableLocation.startingPosition(), true);
		output.writeVarInt(memTableLocation.recordLength(), true);

		serializeKeys(object.getCompressedKeys(), output, kryo);
	}

	@Override
	public CatalogHeader read(Kryo kryo, Input input, Class<? extends CatalogHeader> type) {
		final String catalogName = input.readString();
		final long version = input.readVarLong(true);
		final int lastEntityCollectionPrimaryKey = input.readVarInt(true);
		final long startingPosition = input.readVarLong(true);
		final int recordLength = input.readVarInt(true);
		final Map<Integer, Object> compressedKeys = deserializeKeys(input, kryo);

		return new CatalogHeader(
			version, new FileLocation(startingPosition, recordLength),
			compressedKeys, catalogName, lastEntityCollectionPrimaryKey
		);
	}

}
