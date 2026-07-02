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
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;

/**
 * Base V1 interface for tri-state uniqueness *kind-switch* schema-evolution tests.
 *
 * Each property declares the within-locale uniqueness variant (the `*_LOCALE` enum value).
 * A follow-up V2 class switches every property to the matching plain variant (dropping the
 * `_LOCALE` suffix) to pin that the analyzer reconciles a change of uniqueness *kind* rather
 * than only the on/off transition. Within-locale uniqueness requires `localized = true`, so
 * each attribute keeps that flag set.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityUniquenessKindEvolutionV1.ENTITY_NAME)
public interface GetterBasedEntityUniquenessKindEvolutionV1 {

	String ENTITY_NAME = "GetterBasedEntityUniquenessKindEvolution";

	@PrimaryKey
	int getId();

	@Attribute(unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE, localized = true)
	String getLocaleUniqueCode();

	@Attribute(
		global = true,
		uniqueGlobally = GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE,
		localized = true
	)
	String getGlobalLocaleUniqueCode();

	@Attribute(
		scope = @ScopeAttributeSettings(
			scope = Scope.LIVE,
			unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE
		),
		localized = true
	)
	String getScopedLocaleUniqueCode();

}
