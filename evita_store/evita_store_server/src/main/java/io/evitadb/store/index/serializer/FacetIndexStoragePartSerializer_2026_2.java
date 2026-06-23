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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FacetIndexStoragePart;

import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads {@link FacetIndexStoragePart} from binary format.
 *
 * It reads the pre-granular-slimming format current in the 2026.2 development line; retained for backward
 * compatibility only. That format wrote each facet's referencing entity id array as raw fixed 4-byte ints; the current
 * serializer delta-varints those (globally ascending) arrays. Like the other deprecated readers its {@link #write(Kryo,
 * Output, FacetIndexStoragePart)} throws — this format must never be written again. The dispatcher delegates writes
 * only to the current serializer; the backward-compatible reading is validated end-to-end by the backward-compatibility
 * suite.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2026.2", forRemoval = true)
public class FacetIndexStoragePartSerializer_2026_2 extends Serializer<FacetIndexStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, FacetIndexStoragePart storagePart) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
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

	private static Map<Integer, Bitmap> readGroup(Input input) {
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
