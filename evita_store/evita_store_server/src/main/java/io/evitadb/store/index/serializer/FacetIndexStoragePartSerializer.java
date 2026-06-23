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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FacetIndexStoragePart;
import io.evitadb.store.index.serializer.util.SortedIntArrayCodec;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link FacetIndexStoragePart} from/to binary format.
 *
 * Each facet's referencing entity id array is globally ascending (a {@link Bitmap#getArray()} of a bitmap), so it is
 * delta-varint encoded via {@link SortedIntArrayCodec} instead of as raw fixed 4-byte ints. The pre-slimming format
 * is read by {@link FacetIndexStoragePartSerializer_2026_1}.
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

	/**
	 * Writes a single group's facet index to the output. The group is a map of facet primary key to the bitmap of
	 * entity ids that reference that facet. The facet count is written first, then for each facet its primary key
	 * (raw 4-byte int) followed by its referencing entity ids. Because a {@link Bitmap#getArray()} is always sorted
	 * in ascending order, the id array is delta-varint encoded via {@link SortedIntArrayCodec} rather than as raw
	 * fixed-width ints.
	 *
	 * @param output     the Kryo output to write to
	 * @param groupFacets the map of facet primary key to its referencing entity id bitmap
	 */
	private static void writeGroup(@Nonnull Output output, @Nonnull Map<Integer, Bitmap> groupFacets) {
		output.writeVarInt(groupFacets.size(), true);
		for (Entry<Integer, Bitmap> facetEntry : groupFacets.entrySet()) {
			output.writeInt(facetEntry.getKey());
			// the bitmap's backing array is ascending, so delta-varint it
			SortedIntArrayCodec.writeAscendingInts(output, facetEntry.getValue().getArray());
		}
	}

	/**
	 * Reads a single group's facet index from the input. This is the exact inverse of {@link #writeGroup(Output, Map)}:
	 * it reads the facet count, then for each facet its primary key and its delta-varint encoded referencing entity ids
	 * (decoded via {@link SortedIntArrayCodec} and wrapped in a {@link BaseBitmap}).
	 *
	 * @param input the Kryo input to read from
	 * @return the reconstructed map of facet primary key to its referencing entity id bitmap
	 */
	@Nonnull
	private static Map<Integer, Bitmap> readGroup(@Nonnull Input input) {
		final int facetCount = input.readVarInt(true);
		final Map<Integer, Bitmap> result = createHashMap(facetCount);
		for (int i = 0; i < facetCount; i++) {
			final int facetPrimaryKey = input.readInt();
			final int[] referencingEntityIds = SortedIntArrayCodec.readAscendingInts(input);
			result.put(facetPrimaryKey, new BaseBitmap(referencingEntityIds));
		}
		return result;
	}
}
