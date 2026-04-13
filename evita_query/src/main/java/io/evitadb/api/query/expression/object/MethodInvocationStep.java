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

package io.evitadb.api.query.expression.object;

import io.evitadb.api.query.expression.object.accessor.ObjectAccessorRegistry;
import io.evitadb.api.query.expression.object.accessor.ObjectMethodAccessor;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * An {@link ObjectOperationStep} that invokes a method on the current operand using
 * dot-notation syntax (`.method(...)`). The method is resolved via {@link ObjectMethodAccessor}
 * looked up from the {@link ObjectAccessorRegistry}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class MethodInvocationStep implements ObjectOperationStep {

	@Serial private static final long serialVersionUID = 2616337062617784804L;

	@Nonnull @Getter private final String methodIdentifier;
	@Nonnull @Getter private final List<ExpressionNode> argumentOperands;

	@Nullable @Getter private final ObjectOperationStep next;

	@Nullable
	@Override
	public Serializable compute(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Serializable operand
	) throws ExpressionEvaluationException {
		if (operand == null) {
			throw new ExpressionEvaluationException(
				"Cannot invoke method `" + this.methodIdentifier + "`, object is null. If this is expected, use " +
					"optional chaining (`?.method(...)`) instead."
			);
		}

		final ObjectAccessorRegistry registry = ObjectAccessorRegistry.getInstance();
		final ObjectMethodAccessor methodAccessor = registry.getMethodAccessor(operand.getClass())
			.orElseThrow(
				() -> new ExpressionEvaluationException(
					"Method accessor for class `" + operand.getClass().getName() + "` not found.",
					"Cannot invoke method `" + this.methodIdentifier + "`. Not supported."
				)
			);

		final Serializable result = methodAccessor.invoke(
			context,
			operand,
			this.methodIdentifier,
			this.argumentOperands
		);
		if (getNext() == null) {
			return result;
		}
		return getNext().compute(context, result);
	}

	@Override
	public String toString() {
		final int size = this.argumentOperands.size();
		// estimate: function name + parens + ~10 chars per arg + separators
		final StringBuilder sb = new StringBuilder(".");
		sb.append(this.methodIdentifier).append('(');
		for (int i = 0; i < size; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(this.argumentOperands.get(i).toString());
		}
		sb.append(')');
		return sb.toString();
	}
}
