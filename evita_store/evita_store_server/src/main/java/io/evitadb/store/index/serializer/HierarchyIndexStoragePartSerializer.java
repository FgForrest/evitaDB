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
import io.evitadb.index.hierarchy.HierarchyNode;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart.LevelIndex;
import io.evitadb.store.index.serializer.util.SortedIntArrayCodec;

import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link HierarchyIndexStoragePart} from/to binary format.
 *
 * The per-level children id arrays, the roots and the orphans are each globally ascending and distinct (they are
 * `TransactionalIntArray` snapshots, "unique, strictly ordered ascending"), so they are delta-varint encoded via
 * {@link SortedIntArrayCodec} instead of as raw fixed 4-byte ints; all three are routinely empty, which the codec
 * handles (returning a non-null empty array). The pre-slimming format is read by
 * {@link HierarchyIndexStoragePartSerializer_2026_1}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyIndexStoragePartSerializer extends Serializer<HierarchyIndexStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, HierarchyIndexStoragePart hierarchyIndex) {
		output.writeInt(hierarchyIndex.getEntityIndexPrimaryKey());

		final Map<Integer, HierarchyNode> itemIndex = hierarchyIndex.getItemIndex();
		output.writeVarInt(itemIndex.size(), true);
		for (HierarchyNode node : itemIndex.values()) {
			output.writeInt(node.entityPrimaryKey());
			final boolean parentReferencePresent = node.parentEntityPrimaryKey() != null;
			output.writeBoolean(parentReferencePresent);
			if (parentReferencePresent) {
				output.writeInt(node.parentEntityPrimaryKey());
			}
		}

		final LevelIndex[] levelIndex = hierarchyIndex.getLevelIndex();
		output.writeVarInt(levelIndex.length, true);
		for (LevelIndex entry : levelIndex) {
			output.writeInt(entry.parentId());
			// children ids are ascending and distinct; the codec writes the count itself
			SortedIntArrayCodec.writeAscendingInts(output, entry.childrenIds());
		}

		// roots and orphans are ascending and distinct (and routinely empty); the codec writes the count itself
		SortedIntArrayCodec.writeAscendingInts(output, hierarchyIndex.getRoots());
		SortedIntArrayCodec.writeAscendingInts(output, hierarchyIndex.getOrphans());
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
			final int[] children = SortedIntArrayCodec.readAscendingInts(input);
			levelIndex[i] = new LevelIndex(parentId, children);
		}

		final int[] roots = SortedIntArrayCodec.readAscendingInts(input);
		final int[] orphans = SortedIntArrayCodec.readAscendingInts(input);

		return new HierarchyIndexStoragePart(entityIndexPrimaryKey, itemIndex, roots, levelIndex, orphans);
	}

}
