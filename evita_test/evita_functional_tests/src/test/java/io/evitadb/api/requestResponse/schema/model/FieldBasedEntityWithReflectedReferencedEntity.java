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
import lombok.Data;

/**
 * Example interface for ClassSchemaAnalyzerTest.
 * The entity references link different entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Entity
@Data
public class FieldBasedEntityWithReflectedReferencedEntity {

	@PrimaryKey
	private int id;

	@ReflectedReference(
		ofName = "items",
		allowEmpty = InheritableBoolean.TRUE,
		attributesInheritanceBehavior = AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED
	)
	private BrandReference marketingBrand;

	@Data
	public static class BrandReference {

		@ReferencedEntity
		private Brand brand;

		@Attribute
		private String brandNote;

		@AttributeRef
		private int order;

	}

	@Entity
	@Data
	public static class Brand {

		@PrimaryKey
		private int id;

		@Reference(indexed = ReferenceIndexType.FOR_FILTERING)
		private ItemReference[] items;

	}

	@Data
	public static class ItemReference {

		@ReferencedEntity
		private FieldBasedEntityWithReflectedReferencedEntity item;

		@ReferencedEntityGroup
		private BrandGroup brandGroup;

		@Attribute
		private int order;

		@Attribute
		private String note;

	}

	@Entity
	@Data
	public static class BrandGroup {

		@PrimaryKey
		private int id;

	}

}
