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

package io.evitadb.store.query.serializer.head;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.head.Head;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link Head} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HeadSerializer extends Serializer<Head> {

	@Override
	public void write(Kryo kryo, Output output, Head object) {
		output.writeVarInt(object.getChildrenCount(), true);
		for (HeadConstraint child : object.getChildren()) {
			kryo.writeClassAndObject(output, child);
		}
	}

	@Override
	public Head read(Kryo kryo, Input input, Class<? extends Head> type) {
		final int childrenCount = input.readVarInt(true);
		final HeadConstraint[] children = new HeadConstraint[childrenCount];
		for (int i = 0; i < childrenCount; i++) {
			children[i] = (HeadConstraint) kryo.readClassAndObject(input);
		}
		return new Head(children);
	}

}
