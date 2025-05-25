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

package io.evitadb.api.query.expression.parser.visitor.boolOperator;


import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.PredicateEvaluationContext;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * The EqualsOperator class implements the ExpressionNode interface and is used to compare two operands for equality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class EqualsOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = -1594479195657082056L;
	private final ExpressionNode leftOperator;
	private final ExpressionNode rightOperator;

	public EqualsOperator(@Nonnull ExpressionNode leftOperator, @Nonnull ExpressionNode rightOperator) {
		this.leftOperator = leftOperator;
		this.rightOperator = rightOperator;
	}

	@Nonnull
	@Override
	public Boolean compute(@Nonnull PredicateEvaluationContext context) {
		final Serializable value1 = this.leftOperator.compute(context);
		Assert.isTrue(
			value1 instanceof Comparable,
			() -> new ParserException("Equals function left operand must be comparable!")
		);
		final Serializable value2 = this.rightOperator.compute(context);
		Assert.isTrue(
			value2 instanceof Comparable,
			() -> new ParserException("Equals function right operand must be comparable!")
		);
		final Serializable convertedValue2 = Objects.requireNonNull(EvitaDataTypes.toTargetType(value2, value1.getClass()));
		//noinspection rawtypes,unchecked
		return ((Comparable) value1).compareTo(convertedValue2) == 0;
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		final BigDecimalNumberRange range1 = this.leftOperator.determinePossibleRange();
		final BigDecimalNumberRange range2 = this.rightOperator.determinePossibleRange();
		if (range1 == BigDecimalNumberRange.INFINITE || range2 == BigDecimalNumberRange.INFINITE) {
			return BigDecimalNumberRange.INFINITE;
		} else {
			return BigDecimalNumberRange.intersect(range1, range2);
		}
	}

	@Override
	public String toString() {
		return this.leftOperator + " == " + this.rightOperator;
	}

}
