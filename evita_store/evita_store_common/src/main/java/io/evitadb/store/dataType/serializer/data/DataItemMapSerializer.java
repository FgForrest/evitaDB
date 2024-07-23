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

package io.evitadb.store.dataType.serializer.data;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * This {@link Serializer} implementation reads/writes {@link DataItemMap} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class DataItemMapSerializer extends Serializer<DataItemMap> {

	@Override
	public void write(Kryo kryo, Output output, DataItemMap dataItem) {
		output.writeVarInt(dataItem.getPropertyCount(), true);
		dataItem.forEach((propertyName, child, hasNext) -> {
			output.writeString(propertyName);
			kryo.writeClassAndObject(output, child);
		});
	}

	@Override
	public DataItemMap read(Kryo kryo, Input input, Class<? extends DataItemMap> type) {
		final int childrenCount = input.readVarInt(true);
		final Map<String, DataItem> dataItems = CollectionUtils.createLinkedHashMap(childrenCount);
		for (int i = 0; i < childrenCount; i++) {
			dataItems.put(
				input.readString(),
				(DataItem) kryo.readClassAndObject(input)
			);
		}
		return new DataItemMap(dataItems);
	}

}
