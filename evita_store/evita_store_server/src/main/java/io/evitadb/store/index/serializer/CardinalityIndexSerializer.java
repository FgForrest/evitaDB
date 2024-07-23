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
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.index.cardinality.CardinalityIndex.CardinalityKey;
import io.evitadb.utils.CollectionUtils;

import java.io.Serializable;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Class handles Kryo (de)serialization of {@link CardinalityIndex} instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class CardinalityIndexSerializer extends Serializer<CardinalityIndex> {

	@Override
	public void write(Kryo kryo, Output output, CardinalityIndex object) {
		kryo.writeClass(output, object.getValueType());
		final Map<CardinalityKey, Integer> cardinalities = object.getCardinalities();
		output.writeVarInt(cardinalities.size(), true);
		for (Entry<CardinalityKey, Integer> entry : cardinalities.entrySet()) {
			kryo.writeObject(output, entry.getKey().value());
			output.writeVarInt(entry.getKey().recordId(), false);
			output.writeVarInt(entry.getValue(), true);
		}
	}

	@Override
	public CardinalityIndex read(Kryo kryo, Input input, Class<? extends CardinalityIndex> type) {
		@SuppressWarnings("unchecked") final Class<? extends Serializable> valueType = kryo.readClass(input).getType();
		final int cardinalityCount = input.readVarInt(true);
		final Map<CardinalityKey, Integer> cardinalities = CollectionUtils.createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final Serializable value = kryo.readObject(input, valueType);
			final int recordId = input.readVarInt(false);
			final int cardinality = input.readVarInt(true);
			cardinalities.put(new CardinalityKey(recordId, value), cardinality);
		}
		return new CardinalityIndex(valueType, cardinalities);
	}

}
