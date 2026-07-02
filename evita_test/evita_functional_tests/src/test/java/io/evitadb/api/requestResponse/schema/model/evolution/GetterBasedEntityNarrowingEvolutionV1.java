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

import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Base V1 interface for broad Pattern-A boolean-narrowing schema-evolution tests.
 *
 * Each property turns on a single annotation flag whose default is `false`/`NOT_UNIQUE`.
 * A follow-up V2 class flips every property back to default `@Attribute` /
 * `@AssociatedData` / `@Reference` settings to pin the analyzer's symmetric contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityNarrowingEvolutionV1.ENTITY_NAME)
public interface GetterBasedEntityNarrowingEvolutionV1 {

	String ENTITY_NAME = "GetterBasedEntityNarrowingEvolution";

	@PrimaryKey
	int getId();

	@Attribute(nullable = true)
	String getNullableCode();

	@Attribute(localized = true)
	String getLocalizedCode();

	@Attribute(representative = true)
	String getRepresentativeCode();

	@Attribute(filterable = true)
	String getFilterableCode();

	@Attribute(sortable = true)
	String getSortableCode();

	@Attribute(unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
	String getUniqueCode();

	@Reference(managed = false, faceted = true)
	@Nonnull
	Brand getFacetedReference();

	@AssociatedData(nullable = true)
	String getNullableAssoc();

	@AssociatedData(localized = true)
	String getLocalizedAssoc();

	/**
	 * Reference target carrying no attributes of its own.
	 */
	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

	}

}
