/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.lab.tools.schemaDiff.openApi;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Holds all differences between two OpenAPI schemas categorized by their severity.
 *
 * @param breakingChanges possible incompatible changes that may break existing clients
 * @param nonBreakingChanges compatible changes that should not break existing clients
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public record SchemaDiff(@Nonnull Set<Change> breakingChanges, @Nonnull Set<Change> nonBreakingChanges) {

	/**
	 * A single change (difference) in the schema.
	 *
	 * @param actionType type of action in the change
	 * @param breaking severity of change, whether the change is breaking
	 * @param method HTTP method of changed endpoint
	 * @param path URL path of changed endpoint
	 * @param detail detailed MarkDown description of the change
	 */
	public record Change(@Nonnull ActionType actionType,
	                     boolean breaking,
	                     @Nonnull String method,
	                     @Nonnull String path,
	                     @Nonnull String detail) {}

	/**
	 * Type of action of a change.
	 */
	public enum ActionType {
		ADDITION,
		REMOVAL,
		MODIFICATION,
		DEPRECATION
	}
}
