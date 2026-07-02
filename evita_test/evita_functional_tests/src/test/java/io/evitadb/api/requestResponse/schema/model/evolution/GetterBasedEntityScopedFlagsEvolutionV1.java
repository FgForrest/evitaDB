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
import io.evitadb.api.requestResponse.data.annotation.ScopeAttributeSettings;
import io.evitadb.dataType.Scope;

/**
 * Base V1 interface for per-scope `filterable` / `sortable` narrowing schema-evolution tests.
 *
 * The attribute enables both flags for a single scope via `@ScopeAttributeSettings`. A follow-up
 * V2 class drops both flags (bare `@ScopeAttributeSettings(scope = ...)`) to pin that the analyzer
 * narrows previously-set per-scope `filterable` / `sortable` flags back to their defaults.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityScopedFlagsEvolutionV1.ENTITY_NAME)
public interface GetterBasedEntityScopedFlagsEvolutionV1 {

	String ENTITY_NAME = "GetterBasedEntityScopedFlagsEvolution";

	@PrimaryKey
	int getId();

	@Attribute(
		scope = @ScopeAttributeSettings(
			scope = Scope.LIVE,
			filterable = true,
			sortable = true
		)
	)
	String getScopedFlags();

}
