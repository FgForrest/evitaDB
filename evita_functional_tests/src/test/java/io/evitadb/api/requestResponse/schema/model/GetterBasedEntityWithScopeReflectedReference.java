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
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference.InheritableBoolean;
import io.evitadb.api.requestResponse.data.annotation.ScopeReferenceSettings;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.dataType.Scope;

/**
 * Example interface for ClassSchemaAnalyzerTest demonstrating ScopeReferenceSettings usage with ReflectedReference.
 * This entity shows how to configure different reference settings for different scopes in reflected references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Entity
public interface GetterBasedEntityWithScopeReflectedReference {

	@PrimaryKey
	int getId();

	@Attribute
	String getCode();

	/**
	 * Reflected reference with scope-specific settings.
	 */
	@ReflectedReference(
		ofName = "items",
		allowEmpty = InheritableBoolean.TRUE,
		attributesInheritanceBehavior = AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
		scope = {
			@ScopeReferenceSettings(
				scope = Scope.LIVE,
				indexed = ReferenceIndexType.FOR_FILTERING,
				faceted = true
			),
			@ScopeReferenceSettings(
				scope = Scope.ARCHIVED,
				indexed = ReferenceIndexType.FOR_FILTERING,
				faceted = false
			)
		}
	)
	BrandReference getMarketingBrand();

	interface BrandReference {

		@ReferencedEntity
		Brand getBrand();

		@ReferencedEntityGroup
		BrandGroup getBrandGroup();

		@Attribute
		String getBrandNote();

		@AttributeRef
		int getOrder();

	}

	@Entity
	interface Brand {

		@PrimaryKey
		int getId();

		@Attribute
		String getName();

		/**
		 * Reference with scope-specific settings that will be reflected.
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
		ItemReference[] getItems();

	}

	interface ItemReference {

		@ReferencedEntity
		GetterBasedEntityWithScopeReflectedReference getItem();

		@ReferencedEntityGroup
		BrandGroup getBrandGroup();

		@Attribute
		int getOrder();

		@Attribute
		String getNote();

	}

	@Entity
	interface BrandGroup {

		@PrimaryKey
		int getId();

		@Attribute
		String getName();

	}

}