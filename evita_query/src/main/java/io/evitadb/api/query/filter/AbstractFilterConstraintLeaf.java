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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents base query leaf accepting only filtering constraints.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
abstract class AbstractFilterConstraintLeaf extends ConstraintLeaf<FilterConstraint> implements FilterConstraint {
	@Serial private static final long serialVersionUID = -474943967147232148L;

	public AbstractFilterConstraintLeaf(@Nonnull String name, @Nonnull Serializable... arguments) {
		super(name, arguments);
	}

	protected AbstractFilterConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Nonnull
	@Override
	public Class<FilterConstraint> getType() {
		return FilterConstraint.class;
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 0;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

	/**
	 * Helper method for creating serializable arrays.
	 * @param firstArg
	 * @param rest
	 * @param <T>
	 * @return
	 */
	protected static <T extends Serializable> Serializable[] concat(T firstArg, T[] rest) {
		final Serializable[] result = new Serializable[rest.length + 1];
		result[0] = firstArg;
		System.arraycopy(rest, 0, result, 1, rest.length);
		return result;
	}

	/**
	 * Helper method for creating serializable arrays.
	 * @param firstArg
	 * @param rest
	 * @param <T>
	 * @return
	 */
	protected static <T extends Serializable> Serializable[] concat(T firstArg, T secondArg, T[] rest) {
		final Serializable[] result = new Serializable[rest.length + 2];
		result[0] = firstArg;
		result[1] = secondArg;
		System.arraycopy(rest, 0, result, 2, rest.length);
		return result;
	}

}
