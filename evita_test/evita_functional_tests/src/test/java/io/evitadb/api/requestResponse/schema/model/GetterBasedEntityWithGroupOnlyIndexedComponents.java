/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Example interface for ClassSchemaAnalyzerTest declaring `indexedComponents` without
 * `REFERENCED_ENTITY` in an indexed scope - once through the general `indexedComponents` and once
 * through `@ScopeReferenceSettings#indexedComponents`. The analyzer must wire both declarations
 * through verbatim, and the session that defines the schema must then refuse it at close, because
 * such a scope builds no reduced entity index and every query over the reference is blind in it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity
public interface GetterBasedEntityWithGroupOnlyIndexedComponents {

	@PrimaryKey
	int getId();

	@Attribute
	@Nonnull
	String getCode();

	/**
	 * Reference indexed for filtering with `indexedComponents = {REFERENCED_GROUP_ENTITY}` in the
	 * default scope.
	 */
	@Reference(
		managed = false,
		indexed = ReferenceIndexType.FOR_FILTERING,
		indexedComponents = { ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY }
	)
	Brand getGroupOnlyComponents();

	/**
	 * Reference valid in LIVE but indexed only for the group entity in ARCHIVED.
	 */
	@Reference(
		managed = false,
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
	Brand getGroupOnlyInArchiveComponents();

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@ReferencedEntityGroup
		int getBrandGroup();

	}

}
