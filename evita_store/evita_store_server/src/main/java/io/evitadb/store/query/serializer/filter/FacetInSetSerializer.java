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

package io.evitadb.store.query.serializer.filter;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.filter.FacetInSet;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * This {@link Serializer} implementation reads/writes {@link FacetInSet} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class FacetInSetSerializer extends Serializer<FacetInSet> {

	@Override
	public void write(Kryo kryo, Output output, FacetInSet object) {
		output.writeString(object.getReferenceName());
		final int[] primaryKeys = object.getFacetIds();
		output.writeVarInt(primaryKeys.length, true);
		output.writeInts(primaryKeys, 0, primaryKeys.length);
	}

	@Override
	public FacetInSet read(Kryo kryo, Input input, Class<? extends FacetInSet> type) {
		final String entityType = input.readString();
		final int facetCount = input.readVarInt(true);
		final Integer[] primaryKeys = Arrays.stream(input.readInts(facetCount)).boxed().toArray(Integer[]::new);
		return new FacetInSet(entityType, primaryKeys);
	}

}
