/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.store.spi.model.storageParts.index.FacetIndexStoragePart;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link FacetIndexStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class FacetIndexStoragePartSerializer extends Serializer<FacetIndexStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, FacetIndexStoragePart storagePart) {

		output.writeVarInt(storagePart.getEntityIndexPrimaryKey(), true);
		output.writeString(storagePart.getReferenceName());

		final Map<Integer, Bitmap> noGroupFacetingEntities = storagePart.getNoGroupFacetingEntities();
		output.writeBoolean(noGroupFacetingEntities != null);
		if (noGroupFacetingEntities != null) {
			writeGroup(output, noGroupFacetingEntities);
		}

		output.writeVarInt(storagePart.getFacetingEntities().size(), true);
		for (Entry<Integer, Map<Integer, Bitmap>> groupEntry : storagePart.getFacetingEntities().entrySet()) {
			output.writeInt(groupEntry.getKey());
			writeGroup(output, groupEntry.getValue());
		}
	}

	@Override
	public FacetIndexStoragePart read(Kryo kryo, Input input, Class<? extends FacetIndexStoragePart> type) {
		final int entityIndexId = input.readVarInt(true);
		final String entityType = input.readString();

		final Map<Integer, Bitmap> noGroupFacetingEntities;
		if (input.readBoolean()) {
			noGroupFacetingEntities = readGroup(input);
		} else {
			noGroupFacetingEntities = null;
		}

		final int groupCount = input.readVarInt(true);
		final Map<Integer, Map<Integer, Bitmap>> groupFacetingEntities = createHashMap(groupCount);
		for (int i = 0; i < groupCount; i++) {
			final int groupId = input.readInt();
			final Map<Integer, Bitmap> groupIndex = readGroup(input);
			groupFacetingEntities.put(groupId, groupIndex);
		}

		return new FacetIndexStoragePart(
			entityIndexId, entityType, noGroupFacetingEntities, groupFacetingEntities
		);
	}

	private void writeGroup(Output output, Map<Integer, Bitmap> groupFacets) {
		output.writeVarInt(groupFacets.size(), true);
		for (Entry<Integer, Bitmap> facetEntry : groupFacets.entrySet()) {
			output.writeInt(facetEntry.getKey());
			final int[] referencingEntityIds = facetEntry.getValue().getArray();
			output.writeVarInt(referencingEntityIds.length, true);
			output.writeInts(referencingEntityIds, 0, referencingEntityIds.length);
		}
	}

	private Map<Integer, Bitmap> readGroup(Input input) {
		final int facetCount = input.readVarInt(true);
		final Map<Integer, Bitmap> result = createHashMap(facetCount);
		for (int i = 0; i < facetCount; i++) {
			final int facetPrimaryKey = input.readInt();
			final int entityIdsCount = input.readVarInt(true);
			final int[] referencingEntityIds = input.readInts(entityIdsCount);
			result.put(facetPrimaryKey, new BaseBitmap(referencingEntityIds));
		}
		return result;
	}
}
