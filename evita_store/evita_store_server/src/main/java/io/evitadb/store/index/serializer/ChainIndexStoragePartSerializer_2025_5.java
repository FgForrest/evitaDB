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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.index.attribute.ChainIndex.ElementState;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.store.spi.model.storageParts.index.ChainIndexStoragePart;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * This {@link Serializer} implementation reads/writes {@link ChainIndex} from/to binary format.
 *
 * @deprecated only for backward compatibility purposes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2025.5", forRemoval = true)
@RequiredArgsConstructor
public class ChainIndexStoragePartSerializer_2025_5 extends Serializer<ChainIndexStoragePart>
	implements AttributeKeyToAttributeKeyIndexBridge {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, ChainIndexStoragePart chainIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public ChainIndexStoragePart read(Kryo kryo, Input input, Class<? extends ChainIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = getAttributeIndexKey(input, this.keyCompressor);

		final int stateCount = input.readInt(true);
		final Map<Integer, ChainElementState> elementStates = CollectionUtils.createHashMap(stateCount);
		for(int i = 0; i < stateCount; i++) {
			final int primaryKey = input.readInt();
			final int inChainOfHeadWithPrimaryKey = input.readInt();
			final int predecessorPrimaryKey = input.readInt();
			final ElementState state = ElementState.values()[input.readInt()];
			elementStates.put(
				primaryKey,
				new ChainElementState(
					inChainOfHeadWithPrimaryKey, predecessorPrimaryKey, state
				)
			);
		}

		final int chainCount = input.readInt(true);
		final int[][] chains = new int[chainCount][];
		for(int i = 0; i < chainCount; i++) {
			final int chainLength = input.readInt(true);
			chains[i] = input.readInts(chainLength);
		}

		return new ChainIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, elementStates, chains, uniquePartId
		);
	}

}
