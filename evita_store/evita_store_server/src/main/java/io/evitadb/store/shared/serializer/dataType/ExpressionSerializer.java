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

package io.evitadb.store.shared.serializer.dataType;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.expression.Expression;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes {@link Expression} from/to binary format.
 * The expression is stored as its string representation and reconstructed by parsing it back
 * via {@link ExpressionFactory#parse(String)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ExpressionSerializer extends Serializer<Expression> {

	@Override
	public void write(Kryo kryo, Output output, Expression expression) {
		output.writeString(expression.toExpressionString());
	}

	@Override
	@Nonnull
	public Expression read(Kryo kryo, Input input, Class<? extends Expression> type) {
		final String expressionString = input.readString();
		return ExpressionFactory.parse(expressionString);
	}

}
