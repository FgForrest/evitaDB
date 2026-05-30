/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema.model.evolution;

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;

import javax.annotation.Nullable;

/**
 * Base V1 interface for nullable-narrowing schema-evolution tests.
 *
 * Defines a minimal schema with a single `code` attribute that is explicitly `nullable = true`
 * so a follow-up V2 class can attempt to narrow it back to non-null via default `@Attribute`
 * settings and pin the analyzer's symmetry contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityNullableEvolutionV1.ENTITY_NAME)
public interface GetterBasedEntityNullableEvolutionV1 {

	String ENTITY_NAME = "GetterBasedEntityNullableEvolution";

	@PrimaryKey
	int getId();

	@Attribute(nullable = true)
	@Nullable
	String getCode();

}
