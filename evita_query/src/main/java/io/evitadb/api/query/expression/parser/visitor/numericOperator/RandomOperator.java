/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.query.expression.parser.visitor.numericOperator;


import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.PredicateEvaluationContext;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Objects;
import java.util.Random;

/**
 * RandomOperator is an implementation of the ExpressionNode interface that generates random integers.
 * It uses the Random instance provided by the PredicateEvaluationContext to produce these values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class RandomOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = -6261246532556762806L;
	private final ExpressionNode operator;

	public RandomOperator(@Nullable ExpressionNode operator) {
		this.operator = operator;
	}

	@Nonnull
	@Override
	public Long compute(@Nonnull PredicateEvaluationContext context) {
		final Random rnd = context.getRandom();
		return this.operator == null ?
			rnd.nextLong() :
			rnd.nextLong(Objects.requireNonNull(EvitaDataTypes.toTargetType(this.operator.compute(context), Long.class)));
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return BigDecimalNumberRange.INFINITE;
	}

	@Override
	public String toString() {
		return this.operator == null ? "random()" : "random(" + this.operator + ")";
	}
}
