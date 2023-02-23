/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.constraint;

/**
 * Specifies data structure of input JSON value. Mainly for defining how to extract individual data from input JSON value.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public enum ConstraintValueStructure {

	/**
	 * No particular value needed. Represented by boolean to enable/disable query due to the limitations of JSONs
	 * that can have no value (null represents unknown value).
	 */
	NONE,
	/**
	 * Primitive value, such as string, integer and so on.
	 */
	PRIMITIVE,
	/**
	 * JSON object containing more primitive values or children as inner fields.
	 */
	WRAPPER_OBJECT,
	/**
	 * Special case of {@link #WRAPPER_OBJECT} where there are only {@code from} and {@code to} value parameters which
	 * should result in range tuple.
	 */
	WRAPPER_RANGE,
	/**
	 * Single child or list of children constraints.
	 */
	CHILD
}