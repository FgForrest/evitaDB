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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.system.dto;

import io.evitadb.api.CatalogState;

import javax.annotation.Nullable;

/**
 * Request body for {@link io.evitadb.externalApi.rest.api.system.model.SystemRootDescriptor#UPDATE_CATALOG}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public record UpdateCatalogRequestDto(@Nullable String name,
                                      @Nullable Boolean overwriteTarget,
                                      @Nullable CatalogState catalogState) {
}
