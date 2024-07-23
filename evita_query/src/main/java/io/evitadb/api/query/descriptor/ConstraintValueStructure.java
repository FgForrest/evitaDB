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

package io.evitadb.api.query.descriptor;

/**
 * High-level indicator of how the constraint may look like based on its parameters.
 * This is useful for generating APIs from it.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public enum ConstraintValueStructure {

	/**
	 * No particular value needed. Can be represented by a boolean to enable/disable constraint.
	 */
	NONE,
	/**
	 * Primitive value, such as string, integer and so on.
	 */
	PRIMITIVE,
	/**
	 * Special case of what would otherwise end up as a {@link #COMPLEX} structure where there are only {@code from} and {@code to} value parameters which
	 * should result in range tuple.
	 */
	RANGE,
	/**
	 * Single child or list of children constraints.
	 */
	CONTAINER,
	/**
	 * Possibly a struct/object containing more primitive values or children as inner named values.
	 */
	COMPLEX
}
