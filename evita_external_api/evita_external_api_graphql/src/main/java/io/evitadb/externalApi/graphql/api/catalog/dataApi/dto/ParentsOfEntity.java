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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.dto;

import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * DTO for holding all referenced parent entities for single entity.
 * Equivalent to single entry of {@link ParentsByReference#getParents()}.
 *
 * @param primaryKey primary key of entity for which parents are returned
 * @param references references to parent entities
 *
 * @see HierarchyParents
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record ParentsOfEntity(int primaryKey, @Nonnull List<ParentsOfReference> references) {}
