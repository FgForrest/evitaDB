/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.annotation.ScopeReferenceSettings;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Example interface for ClassSchemaAnalyzerTest demonstrating ScopeReferenceSettings usage.
 * This entity shows how to configure different reference settings for different scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Entity
public interface GetterBasedEntityWithScopeReferenceSettings {

	@PrimaryKey
	int getId();

	@Attribute
	@Nonnull
	String getCode();

	/**
	 * Reference with scope-specific settings - indexed and faceted in LIVE scope only.
	 */
	@Reference(
		scope = {
			@ScopeReferenceSettings(
				scope = Scope.LIVE,
				indexed = ReferenceIndexType.FOR_FILTERING,
				faceted = true
			)
		}
	)
	Brand getMarketingBrand();

	/**
	 * Reference with different settings for different scopes.
	 */
	@Reference(
		scope = {
			@ScopeReferenceSettings(
				scope = Scope.LIVE,
				indexed = ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
				faceted = true
			),
			@ScopeReferenceSettings(
				scope = Scope.ARCHIVED,
				indexed = ReferenceIndexType.FOR_FILTERING,
				faceted = false
			)
		}
	)
	Brand[] getSupplierBrands();

	/**
	 * Reference with no scope settings - should use defaults (LIVE scope only).
	 */
	@Reference(indexed = ReferenceIndexType.FOR_FILTERING, faceted = true)
	Brand getDefaultBrand();

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@ReferencedEntityGroup
		int getBrandGroup();

		@Attribute
		String getMarket();

		@Attribute
		int getInceptionYear();

	}

}