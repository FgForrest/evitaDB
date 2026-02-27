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
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;

/**
 * Example interface for ClassSchemaAnalyzerTest.
 * The entity references link different entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Entity
public interface GetterBasedEntityWithReflectedReferencedEntity {

	@PrimaryKey
	int getId();

	@ReflectedReference(
		ofName = "items",
		allowEmpty = InheritableBoolean.TRUE,
		attributesInheritanceBehavior = AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED
	)
	BrandReference getMarketingBrand();

	interface BrandReference {

		@ReferencedEntity
		Brand getBrand();

		@Attribute
		String getBrandNote();

		@AttributeRef
		int getOrder();

	}

	@Entity
	interface Brand {

		@PrimaryKey
		int getId();

		@Reference(indexed = ReferenceIndexType.FOR_FILTERING)
		ItemReference[] getItems();

	}

	interface ItemReference {

		@ReferencedEntity
		GetterBasedEntityWithReflectedReferencedEntity getItem();

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

	}

}
