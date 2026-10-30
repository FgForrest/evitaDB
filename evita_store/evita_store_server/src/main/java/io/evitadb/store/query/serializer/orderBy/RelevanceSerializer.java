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

package io.evitadb.store.query.serializer.orderBy;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.Relevance;
import io.evitadb.utils.Assert;

/**
 * This {@link Serializer} implementation reads/writes {@link Relevance} from/to binary format.
 *
 * The constraint accepts no children yet, but the child count is written anyway, so that the binary format stays the
 * same once the ranking-policy children are added.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RelevanceSerializer extends Serializer<Relevance> {

	@Override
	public void write(Kryo kryo, Output output, Relevance relevance) {
		final OrderConstraint[] children = relevance.getChildren();
		output.writeVarInt(children.length, true);
		for (final OrderConstraint child : children) {
			kryo.writeClassAndObject(output, child);
		}
	}

	@Override
	public Relevance read(Kryo kryo, Input input, Class<? extends Relevance> type) {
		final int childCount = input.readVarInt(true);
		Assert.isPremiseValid(
			childCount == 0,
			() -> "Constraint `relevance` accepts no children, but " + childCount + " were serialized!"
		);
		return new Relevance();
	}

}
