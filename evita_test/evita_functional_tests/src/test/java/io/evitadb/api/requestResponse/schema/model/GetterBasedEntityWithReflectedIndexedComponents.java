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
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.dataType.Scope;

/**
 * Example interface for ClassSchemaAnalyzerTest demonstrating `indexedComponents` usage on
 * a `@ReflectedReference`. Covers both the empty-scope branch (general `indexedComponents`)
 * and the per-scope branch.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity
public interface GetterBasedEntityWithReflectedIndexedComponents {

	@PrimaryKey
	int getId();

	@Attribute
	String getCode();

	/**
	 * Reflected reference with general `indexedComponents = {REFERENCED_GROUP_ENTITY}` —
	 * exercises the empty-scope branch of `applyReflectedReferenceScopedProperties`.
	 */
	@ReflectedReference(
		ofName = "items",
		allowEmpty = InheritableBoolean.TRUE,
		attributesInheritanceBehavior = AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
		indexedComponents = { ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY }
	)
	BrandReference getMarketingBrand();

	/**
	 * Reflected reference using per-scope `@ScopeReferenceSettings#indexedComponents` —
	 * exercises the per-scope branch of `applyReflectedReferenceScopedProperties`.
	 */
	@ReflectedReference(
		ofName = "secondaryItems",
		allowEmpty = InheritableBoolean.TRUE,
		attributesInheritanceBehavior = AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
		scope = {
			@ScopeReferenceSettings(
				scope = Scope.LIVE,
				indexed = ReferenceIndexType.FOR_FILTERING,
				indexedComponents = {
					ReferenceIndexedComponents.REFERENCED_ENTITY,
					ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
				}
			),
			@ScopeReferenceSettings(
				scope = Scope.ARCHIVED,
				indexed = ReferenceIndexType.FOR_FILTERING,
				indexedComponents = { ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY }
			)
		}
	)
	BrandReference getSecondaryBrand();

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
		 * Source reference for the primary `marketingBrand` reflected reference.
		 */
		@Reference(indexed = ReferenceIndexType.FOR_FILTERING)
		ItemReference[] getItems();

		/**
		 * Source reference for the per-scope `secondaryBrand` reflected reference. Indexed in both
		 * scopes so the reflected reference can connect entities in either scope.
		 */
		@Reference(
			scope = {
				@ScopeReferenceSettings(
					scope = Scope.LIVE,
					indexed = ReferenceIndexType.FOR_FILTERING
				),
				@ScopeReferenceSettings(
					scope = Scope.ARCHIVED,
					indexed = ReferenceIndexType.FOR_FILTERING
				)
			}
		)
		ItemReference[] getSecondaryItems();

	}

	interface ItemReference {

		@ReferencedEntity
		GetterBasedEntityWithReflectedIndexedComponents getItem();

		@ReferencedEntityGroup
		BrandGroup getBrandGroup();

		@Attribute
		int getOrder();

		@Attribute
		String getBrandNote();

	}

	@Entity
	interface BrandGroup {

		@PrimaryKey
		int getId();

		@Attribute
		String getName();

	}

}
