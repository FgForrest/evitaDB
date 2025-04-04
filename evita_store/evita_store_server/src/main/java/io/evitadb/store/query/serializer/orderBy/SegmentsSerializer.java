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

package io.evitadb.store.query.serializer.orderBy;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.Segment;
import io.evitadb.api.query.order.Segments;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link Segments} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class SegmentsSerializer extends Serializer<Segments> {

	@Override
	public void write(Kryo kryo, Output output, Segments object) {
		output.writeVarInt(object.getChildrenCount(), true);
		for (OrderConstraint child : object.getChildren()) {
			kryo.writeObject(output, child);
		}
	}

	@Override
	public Segments read(Kryo kryo, Input input, Class<? extends Segments> type) {
		final int childrenCount = input.readVarInt(true);
		final Segment[] children = new Segment[childrenCount];
		for (int i = 0; i < childrenCount; i++) {
			children[i] = kryo.readObject(input, Segment.class);
		}
		return new Segments(children);
	}

}
