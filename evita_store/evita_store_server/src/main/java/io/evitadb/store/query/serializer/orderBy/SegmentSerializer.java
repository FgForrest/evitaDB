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
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.Segment;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.OptionalInt;

import static io.evitadb.api.query.QueryConstraints.limit;

/**
 * This {@link Serializer} implementation reads/writes {@link Segment} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class SegmentSerializer extends Serializer<Segment> {

	@Override
	public void write(Kryo kryo, Output output, Segment segment) {
		final OrderBy orderBy = segment.getOrderBy();
		kryo.writeObject(output, orderBy);
		final Optional<EntityHaving> entityHaving = segment.getEntityHaving();
		output.writeBoolean(entityHaving.isPresent());
		entityHaving.ifPresent(having -> kryo.writeObject(output, having));
		final OptionalInt limit = segment.getLimit();
		output.writeBoolean(limit.isPresent());
		limit.ifPresent(output::writeInt);
	}

	@Override
	public Segment read(Kryo kryo, Input input, Class<? extends Segment> type) {
		final OrderBy orderBy = kryo.readObject(input, OrderBy.class);
		final boolean hasEntityHaving = input.readBoolean();
		final Optional<EntityHaving> entityHaving = hasEntityHaving ? Optional.of(kryo.readObject(input, EntityHaving.class)) : Optional.empty();
		final boolean hasLimit = input.readBoolean();
		final OptionalInt limit = hasLimit ? OptionalInt.of(input.readInt()) : OptionalInt.empty();
		if (hasEntityHaving && hasLimit) {
			return new Segment(entityHaving.get(), orderBy, limit(limit.getAsInt()));
		} else if (hasEntityHaving) {
			return new Segment(entityHaving.get(), orderBy);
		} else if (hasLimit) {
			return new Segment(orderBy, limit(limit.getAsInt()));
		} else {
			return new Segment(orderBy);
		}
	}

}
