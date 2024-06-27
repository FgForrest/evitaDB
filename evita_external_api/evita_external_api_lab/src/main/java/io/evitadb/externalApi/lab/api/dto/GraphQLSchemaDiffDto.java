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

package io.evitadb.externalApi.lab.api.dto;

import io.evitadb.externalApi.lab.tools.schemaDiff.graphql.SchemaDiff.ActionType;
import io.evitadb.externalApi.lab.tools.schemaDiff.graphql.SchemaDiff.ChangeType;
import io.evitadb.externalApi.lab.tools.schemaDiff.graphql.SchemaDiff.Severity;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2024
 */
public record GraphQLSchemaDiffDto(@Nonnull Set<ChangeDto> breakingChanges,
                                   @Nonnull Set<ChangeDto> nonBreakingChanges,
                                   @Nonnull Set<ChangeDto> unclassifiedChanges) {

	/**
	 * A single change (difference) in the schema.
	 *
	 * @param type type of the change
	 * @param args arguments of the change describing what specifically has changed
	 */
	public record ChangeDto(@Nonnull ChangeTypeDto type, @Nonnull Object... args) {}

	public record ChangeTypeDto(@Nonnull String code, @Nonnull Severity severity, @Nonnull ActionType actionType) {}
}
