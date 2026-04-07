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

package io.evitadb.api.requestResponse.schema.model;

import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;

import javax.annotation.Nonnull;

/**
 * V1 of `GetterBasedEntityWithReferenceAndReferenceRef`. Declares only the mandatory
 * `@Reference` getter, returning a reference DTO that contains **no** attributes. Used to bring
 * the entity schema into a known persisted state with the reference present but no attribute on
 * it before the dual-mapped V2 (which adds the attribute via both `@Reference` and
 * `@ReferenceRef`) is analyzed against it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityWithReferenceAndReferenceRef.ENTITY_NAME)
public interface GetterBasedEntityWithReferenceAndReferenceRefV1 {

	/**
	 * Returns the primary key of the entity.
	 *
	 * @return primary key
	 */
	@PrimaryKey
	int getId();

	/**
	 * Mandatory accessor for the marketing brand reference. The reference DTO is empty (no
	 * attributes) so that V1 persists only the reference itself.
	 *
	 * @return marketing brand reference (never `null`)
	 */
	@Reference(description = GetterBasedEntityWithReferenceAndReferenceRef.REFERENCE_DESCRIPTION)
	@Nonnull
	BareMarketingBrandRef getMarketingBrand();

	/**
	 * Bare reference DTO with no attributes - only the target entity link.
	 */
	interface BareMarketingBrandRef {

		/**
		 * Returns the referenced marketing brand entity.
		 *
		 * @return referenced brand entity
		 */
		@ReferencedEntity
		GetterBasedEntityWithReferenceAndReferenceRef.MarketingBrand getBrand();

	}

}
