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

package io.evitadb.store.query.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;

/**
 * This {@link Serializer} implementation reads/writes {@link Query} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class QuerySerializer extends Serializer<Query> {

	@Override
	public void write(Kryo kryo, Output output, Query object) {
		kryo.writeObjectOrNull(output, object.getCollection(), Collection.class);
		kryo.writeObjectOrNull(output, object.getFilterBy(), FilterBy.class);
		kryo.writeObjectOrNull(output, object.getOrderBy(), OrderBy.class);
		kryo.writeObjectOrNull(output, object.getRequire(), Require.class);
	}

	@Override
	public Query read(Kryo kryo, Input input, Class<? extends Query> type) {
		return Query.query(
			kryo.readObjectOrNull(input, Collection.class),
			kryo.readObjectOrNull(input, FilterBy.class),
			kryo.readObjectOrNull(input, OrderBy.class),
			kryo.readObjectOrNull(input, Require.class)
		);
	}

}
