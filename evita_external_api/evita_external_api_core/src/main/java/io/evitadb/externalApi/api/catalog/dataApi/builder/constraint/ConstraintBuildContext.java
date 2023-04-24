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

package io.evitadb.externalApi.api.catalog.dataApi.builder.constraint;

import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import lombok.Builder;

import javax.annotation.Nonnull;

/**
 * Local context for constraint building. It is passed down the constraint tree. Each node can create new
 * context for its children if received context from parent is not relevant
 *
 * @param dataLocator specifies how to get schemas for building in particular place in built constraint tree
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Builder(toBuilder = true)
public record ConstraintBuildContext(@Nonnull DataLocator dataLocator) {
}
