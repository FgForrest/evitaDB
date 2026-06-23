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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.hierarchy.HierarchyNode;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart.LevelIndex;

import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads {@link HierarchyIndexStoragePart} from binary format.
 *
 * It reads the pre-granular-slimming format current in the 2026.2 development line; retained for backward
 * compatibility only. That format wrote the children id arrays, the roots and the orphans as raw fixed 4-byte ints; the
 * current serializer delta-varints those (globally ascending) arrays. Like the other deprecated readers its
 * {@link #write(Kryo, Output, HierarchyIndexStoragePart)} throws — this format must never be written again. The
 * dispatcher delegates writes only to the current serializer; the backward-compatible reading is validated end-to-end
 * by the backward-compatibility suite.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2026.2", forRemoval = true)
public class HierarchyIndexStoragePartSerializer_2026_2 extends Serializer<HierarchyIndexStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, HierarchyIndexStoragePart hierarchyIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public HierarchyIndexStoragePart read(Kryo kryo, Input input, Class<? extends HierarchyIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();

		final int itemIndexSize = input.readVarInt(true);
		final Map<Integer, HierarchyNode> itemIndex = createHashMap(itemIndexSize);
		for (int i = 0; i < itemIndexSize; i++) {
			final int entityPrimaryKey = input.readInt();
			final boolean parentReferencePresent = input.readBoolean();
			Integer parentEntityPrimaryKey = null;
			if (parentReferencePresent) {
				parentEntityPrimaryKey = input.readInt();
			}
			itemIndex.put(entityPrimaryKey, new HierarchyNode(entityPrimaryKey, parentEntityPrimaryKey));
		}

		final int levelIndexSize = input.readVarInt(true);
		final LevelIndex[] levelIndex = new LevelIndex[levelIndexSize];
		for (int i = 0; i < levelIndexSize; i++) {
			final int parentId = input.readInt();
			final int childrenCount = input.readVarInt(true);
			final int[] children = input.readInts(childrenCount);
			levelIndex[i] = new LevelIndex(parentId, children);
		}

		final int rootCount = input.readVarInt(true);
		final int[] roots = input.readInts(rootCount);

		final int orphanCount = input.readVarInt(true);
		final int[] orphans = input.readInts(orphanCount);

		return new HierarchyIndexStoragePart(entityIndexPrimaryKey, itemIndex, roots, levelIndex, orphans);
	}

}
