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
import io.evitadb.api.requestResponse.data.annotation.ScopeAttributeSettings;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;

/**
 * V2 evolution: switches every previously within-locale uniqueness flag to its plain (non-locale)
 * variant by overriding each property, keeping `localized = true` and the same scope structure.
 *
 * This pins that the analyzer reconciles a change of uniqueness *kind*
 * (`*_LOCALE` to the plain variant) — not only the on/off transition.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface GetterBasedEntityUniquenessKindEvolutionV2SwitchToCollection
	extends GetterBasedEntityUniquenessKindEvolutionV1 {

	@Override
	@Attribute(unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, localized = true)
	String getLocaleUniqueCode();

	@Override
	@Attribute(
		global = true,
		uniqueGlobally = GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG,
		localized = true
	)
	String getGlobalLocaleUniqueCode();

	@Override
	@Attribute(
		scope = @ScopeAttributeSettings(
			scope = Scope.LIVE,
			unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
		),
		localized = true
	)
	String getScopedLocaleUniqueCode();

}
