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
import io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor;
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

/**
 * An {@link ObjectAccessStep} that resolves an element by index or key using bracket-notation
 * syntax (`[index]` or `['key']`). The element identifier expression is evaluated at runtime and
 * must produce either a {@link String} or {@link Long} value. The element is then resolved via
 * {@link ObjectElementAccessor} looked up from the {@link ObjectAccessorRegistry}.
 *
 * For example, in the expression `$items[0]`, this step handles the `[0]` access, and in
 * `$map['name']`, it handles the `['name']` access. If the operand is null, an
 * {@link ExpressionEvaluationException} is thrown — use {@link NullSafeAccessStep} (`?[key]`)
 * to handle nullable operands gracefully.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class ElementAccessStep implements ObjectAccessStep {
	@Serial private static final long serialVersionUID = 2760082902212762061L;

	@Nonnull @Getter private final ExpressionNode elementIdentifierOperand;

	@Nullable @Getter private final ObjectAccessStep next;

	@Nullable
	@Override
	public Serializable compute(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Serializable operand
	) throws ExpressionEvaluationException {
		final Serializable elementIdentifier = this.elementIdentifierOperand.compute(context);
		if (elementIdentifier == null) {
			throw new ExpressionEvaluationException("Element identifier is required, but evaluated to null.");
		}
		if (!(elementIdentifier instanceof String) && !(elementIdentifier instanceof Long)) {
			throw new ExpressionEvaluationException(
				"Element identifier must be either string or integer, was `" + elementIdentifier.getClass().getName() + "`",
				"Element identifier must be either string or integer."
			);
		}

		if (operand == null) {
			throw new ExpressionEvaluationException(
				"Cannot access element `" + elementIdentifier + "`, item is null. If this is expected, use " +
					"optional chaining (`*?[element]`) instead."
			);
		}

		final ObjectAccessorRegistry registry = ObjectAccessorRegistry.getInstance();
		final ObjectElementAccessor elementAccessor = registry.getElementAccessor(operand.getClass())
			.orElseThrow(
				() -> new ExpressionEvaluationException(
					"Element accessor for class `" + operand.getClass().getName() + "` not found.",
					"Cannot access element `" + elementIdentifier + "`. Not supported."
				)
			);

		final Serializable result;
		if (elementIdentifier instanceof String elementName) {
			result = elementAccessor.get(operand, elementName);
		} else if (elementIdentifier instanceof Long elementIndex) {
			result = elementAccessor.get(operand, elementIndex.intValue());
		} else {
			throw new ExpressionEvaluationException(
				"Element identifier must be either string or integer, was `" + elementIdentifier.getClass().getName() + "`",
				"Element identifier must be either string or integer."
			);
		}

		if (getNext() == null) {
			return result;
		}

		return getNext().compute(context, result);
	}

	@Override
	public String toString() {
		return "[" + this.elementIdentifierOperand + "]" + (this.next != null ? this.next.toString() : "");
	}
}
