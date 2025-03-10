/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Interface must be implemented by all {@link Constraint} that use default values for certain arguments. Without it,
 * serialization and pretty printing of the constraint will lead to displaying unnecessary default values.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface ConstraintWithDefaults<T extends Constraint<T>> extends Constraint<T> {

	/**
	 * Returns list of all constraint arguments excluding those that match the default (implicit) values.
	 * This array will be used when converting constraint to string format. Excluding default parameters will
	 * make the string representation more readable and shorter.
	 */
	@Nonnull
	Serializable[] getArgumentsExcludingDefaults();

	/**
	 * Checks whether the passed argument is a default value. This is primarily used to make a constraint representation
	 * more readable and shorter
	 */
	boolean isArgumentImplicit(@Nonnull Serializable serializable);
}
