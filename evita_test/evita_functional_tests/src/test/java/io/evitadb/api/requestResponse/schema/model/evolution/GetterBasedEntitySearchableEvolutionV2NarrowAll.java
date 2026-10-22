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
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ScopeAttributeSettings;
import io.evitadb.dataType.Scope;

import java.io.Serializable;

/**
 * V2 evolution of {@link GetterBasedEntitySearchableEvolutionV1}: the same attributes with every `searchable` and
 * `acceleratedFor` dropped, while the filter indexes the accelerators sped up stay declared.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntitySearchableEvolutionV1.ENTITY_NAME)
public interface GetterBasedEntitySearchableEvolutionV2NarrowAll {

	@PrimaryKey
	int getId();

	@Attribute(localized = true)
	String getName();

	@Attribute(filterable = true)
	String getCode();

	@Attribute(global = true, localized = true)
	String getSearchableGlobalTitle();

	@Attribute(
		localized = true,
		scope = {
			@ScopeAttributeSettings(
				scope = Scope.LIVE,
				filterable = true
			)
		}
	)
	String getScopedText();

	@Reference(managed = false)
	Brand getMarketingBrand();

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@Attribute(localized = true)
		String getLabel();

	}

}
