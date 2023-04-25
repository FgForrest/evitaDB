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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.filter.ReferenceHaving;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link Or} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ReferenceHavingSerializer extends Serializer<ReferenceHaving> {

	@Override
	public void write(Kryo kryo, Output output, ReferenceHaving object) {
		output.writeString(object.getReferenceName());
		final FilterConstraint[] children = object.getChildren();
		output.writeVarInt(children.length, true);
		for (FilterConstraint child : children) {
			kryo.writeClassAndObject(output, child);
		}
	}

	@Override
	public ReferenceHaving read(Kryo kryo, Input input, Class<? extends ReferenceHaving> type) {
		final String referenceName = input.readString();
		final int childrenCount = input.readVarInt(true);
		final FilterConstraint[] children = new FilterConstraint[childrenCount];
		for (int i = 0; i < childrenCount; i++) {
			children[i] = (FilterConstraint) kryo.readClassAndObject(input);
		}
		return new ReferenceHaving(referenceName, children);
	}

}
