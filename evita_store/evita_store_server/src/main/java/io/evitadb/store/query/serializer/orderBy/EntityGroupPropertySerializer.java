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

package io.evitadb.store.query.serializer.orderBy;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.EntityGroupProperty;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link EntityGroupProperty} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class EntityGroupPropertySerializer extends Serializer<EntityGroupProperty> {

	@Override
	public void write(Kryo kryo, Output output, EntityGroupProperty object) {
		final OrderConstraint[] children = object.getChildren();
		output.writeVarInt(children.length, true);
		for (OrderConstraint child : children) {
			kryo.writeClassAndObject(output, child);
		}
	}

	@Override
	public EntityGroupProperty read(Kryo kryo, Input input, Class<? extends EntityGroupProperty> type) {
		final OrderConstraint[] children = new OrderConstraint[input.readVarInt(true)];
		for (int i = 0; i < children.length; i++) {
			children[i] = (OrderConstraint) kryo.readClassAndObject(input);
		}
		return new EntityGroupProperty(children);
	}

}
