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

package io.evitadb.api.query.expression.visitor;

import javax.annotation.Nonnull;

/**
 * Represents a single item in an accessed data path within an expression tree.
 * Each item carries semantic information about the kind of access it represents,
 * allowing downstream consumers to distinguish between variable references,
 * property accesses, and element accesses.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 * @see AccessedDataFinder
 */
public sealed interface PathItem
	permits VariablePathItem, IdentifierPathItem, ElementPathItem {

	/**
	 * Returns the string value of this path item.
	 */
	@Nonnull
	String value();

}
