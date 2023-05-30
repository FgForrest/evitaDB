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

package io.evitadb.documentation.constraint;

import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nonnull;

/**
 * Holds converted {@link io.evitadb.api.query.Constraint} into JSON with `key` being name of constraint and `value`
 * being body depending on {@link io.evitadb.externalApi.api.catalog.dataApi.constraint.ConstraintValueStructure} of
 * particular constraint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public record JsonConstraint(@Nonnull String key, @Nonnull JsonNode value) {
}
