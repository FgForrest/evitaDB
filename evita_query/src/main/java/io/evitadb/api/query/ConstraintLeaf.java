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

package io.evitadb.api.query;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Leaf query is final query of the query tree. It cannot contain any other constraints. On the contrary
 * it contains on arguments.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ConstraintLeaf<T extends Constraint<T>> extends BaseConstraint<T> {
	@Serial private static final long serialVersionUID = 3842640572690004094L;

	protected ConstraintLeaf(@Nonnull String name, @Nonnull Serializable... arguments) {
		super(name, arguments);
		if (Arrays.stream(arguments).anyMatch(Constraint.class::isInstance)) {
			throw new EvitaInvalidUsageException("Constraint argument is not allowed for leaf query (" + getName() + ").");
		}
	}

	protected ConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
		if (Arrays.stream(arguments).anyMatch(Constraint.class::isInstance)) {
			throw new EvitaInvalidUsageException("Constraint argument is not allowed for leaf query (" + getName() + ").");
		}
	}

}
