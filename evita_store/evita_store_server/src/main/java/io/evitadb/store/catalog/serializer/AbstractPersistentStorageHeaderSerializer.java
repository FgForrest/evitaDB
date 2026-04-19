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

package io.evitadb.store.catalog.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.model.header.PersistentStorageHeader;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This {@link Serializer} contains helper methods to read/write data from {@link PersistentStorageHeader} from/to
 * binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
abstract class AbstractPersistentStorageHeaderSerializer<T> extends Serializer<T> {

	/**
	 * Method is targeted to serialize compressed keys from {@link PersistentStorageHeader#compressedKeys()}.
	 */
	protected void serializeKeys(@Nonnull Map<Integer, Object> keys, @Nonnull Output output, @Nonnull Kryo kryo) {
		output.writeVarInt(keys.size(), true);
		for (Entry<Integer, Object> entry : keys.entrySet()) {
			output.writeVarInt(entry.getKey(), true);
			kryo.writeClassAndObject(output, entry.getValue());
		}
	}

	/**
	 * Method is targeted to deserialize compressed keys to {@link PersistentStorageHeader#compressedKeys()}.
	 * Callers that also need the highest observed id (for example when reconstructing a
	 * {@link PersistentStorageHeader} which stores the peak explicitly) should use
	 * {@link #deserializeKeysAndPeak(Input, Kryo)} instead — it tracks the peak during the single read pass
	 * at no extra cost.
	 */
	protected Map<Integer, Object> deserializeKeys(@Nonnull Input input, @Nonnull Kryo kryo) {
		return deserializeKeysAndPeak(input, kryo).keys();
	}

	/**
	 * Reads the compressed-keys map from the given input and returns both the map and the highest id observed
	 * in a single pass. The peak is captured while the entries are being read, so there is no second scan.
	 */
	@Nonnull
	protected DeserializedKeys deserializeKeysAndPeak(@Nonnull Input input, @Nonnull Kryo kryo) {
		final int keyCount = input.readVarInt(true);
		final Map<Integer, Object> keys = CollectionUtils.createHashMap(keyCount);
		int peak = 0;
		for (int i = 1; i <= keyCount; i++) {
			final int key = input.readVarInt(true);
			final Object value = kryo.readClassAndObject(input);
			keys.put(key, value);
			if (key > peak) {
				peak = key;
			}
		}
		return new DeserializedKeys(keys, peak);
	}

	/**
	 * Pair of deserialized compressed keys and the highest id present in the map. Passed back to callers
	 * that need to populate the `peakCompressedKeyId` of a {@link PersistentStorageHeader} or similar
	 * record without performing a second scan of the map.
	 */
	protected record DeserializedKeys(@Nonnull Map<Integer, Object> keys, int peakId) {
	}

}
