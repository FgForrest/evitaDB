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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.SpacingGap;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.store.shared.serializer.dataType.ExpressionSerializer;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link SpacingGap} from/to binary format.
 *
 * The payload is the gap size followed by the `onPage` expression that decides which pages the gap applies to. The
 * expression is delegated to {@link ExpressionSerializer} rather than written through the Kryo instance, so that a
 * gap can be read by any configuration that registers this serializer - the query configuration alone does not
 * register `Expression`, and only the traffic-recording chain adds one ahead of it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class SpacingGapSerializer extends Serializer<SpacingGap> {
	/**
	 * The encoding of the `onPage` expression, shared with every other place an expression is persisted so that the
	 * gap payload cannot drift away from it.
	 */
	private final ExpressionSerializer expressionSerializer = new ExpressionSerializer();

	@Override
	public void write(Kryo kryo, Output output, SpacingGap object) {
		output.writeVarInt(object.getSize(), true);
		this.expressionSerializer.write(kryo, output, object.getOnPage());
	}

	@Override
	public SpacingGap read(Kryo kryo, Input input, Class<? extends SpacingGap> type) {
		final int size = input.readVarInt(true);
		return new SpacingGap(size, this.expressionSerializer.read(kryo, input, Expression.class));
	}

}
