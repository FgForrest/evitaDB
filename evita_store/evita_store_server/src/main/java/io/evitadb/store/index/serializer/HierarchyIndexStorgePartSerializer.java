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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.hierarchy.HierarchyNode;
import io.evitadb.store.spi.model.storageParts.index.HierarchyIndexStoragePart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link HierarchyIndexStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchyIndexStorgePartSerializer extends Serializer<HierarchyIndexStoragePart> {

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

		final Map<Integer, int[]> levelIndex = hierarchyIndex.getLevelIndex();
		output.writeVarInt(levelIndex.size(), true);
		for (Entry<Integer, int[]> entry : levelIndex.entrySet()) {
			output.writeInt(entry.getKey());
			final int[] children = entry.getValue();
			output.writeVarInt(children.length, true);
			output.writeInts(children, 0, children.length);
		}

		final List<Integer> roots = hierarchyIndex.getRoots();
		output.writeVarInt(roots.size(), true);
		output.writeInts(roots.stream().mapToInt(it -> it).toArray(), 0, roots.size());

		final int[] orphans = hierarchyIndex.getOrphans();
		output.writeVarInt(orphans.length, true);
		output.writeInts(orphans, 0, orphans.length);
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
		final Map<Integer, int[]> levelIndex = createHashMap(levelIndexSize);
		for (int i = 0; i < levelIndexSize; i++) {
			final int key = input.readInt();
			final int childrenCount = input.readVarInt(true);
			final int[] children = input.readInts(childrenCount);
			levelIndex.put(key, children);
		}

		final int rootCount = input.readVarInt(true);
		final List<Integer> roots = new ArrayList<>(rootCount);
		Arrays.stream(input.readInts(rootCount)).forEach(roots::add);

		final int orphanCount = input.readVarInt(true);
		final int[] orphans = input.readInts(orphanCount);

		return new HierarchyIndexStoragePart(entityIndexPrimaryKey, itemIndex, roots, levelIndex, orphans);
	}

}
