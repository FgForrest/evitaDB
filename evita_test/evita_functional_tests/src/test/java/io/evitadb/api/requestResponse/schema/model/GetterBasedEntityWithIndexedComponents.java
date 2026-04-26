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
 * Example interface for ClassSchemaAnalyzerTest demonstrating `indexedComponents` usage.
 * Covers the empty-scope branch (general `indexedComponents`) and the per-scope branch
 * (`indexedComponents` on `@ScopeReferenceSettings`).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity
public interface GetterBasedEntityWithIndexedComponents {

	@PrimaryKey
	int getId();

	@Attribute
	@Nonnull
	String getCode();

	/**
	 * Reference indexed for filtering, no `indexedComponents` configured — should resolve to the
	 * default `{REFERENCED_ENTITY}` of the schema, matching the annotation default.
	 */
	@Reference(
		managed = false,
		indexed = ReferenceIndexType.FOR_FILTERING
	)
	Brand getDefaultComponents();

	/**
	 * Reference indexed for filtering with `indexedComponents = {REFERENCED_GROUP_ENTITY}` —
	 * verifies the analyzer wires the override through.
	 */
	@Reference(
		managed = false,
		indexed = ReferenceIndexType.FOR_FILTERING,
		indexedComponents = { ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY }
	)
	Brand getGroupOnlyComponents();

	/**
	 * Reference indexed with both components — verifies multiple component selection.
	 */
	@Reference(
		managed = false,
		indexed = ReferenceIndexType.FOR_FILTERING,
		indexedComponents = {
			ReferenceIndexedComponents.REFERENCED_ENTITY,
			ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
		}
	)
	Brand getBothComponents();

	/**
	 * Reference using per-scope `@ScopeReferenceSettings#indexedComponents` — verifies the
	 * scope-driven branch of the analyzer. LIVE indexes both sides; ARCHIVED indexes only the
	 * group entity.
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
	Brand getPerScopeComponents();

	/**
	 * Reference with `indexed = NONE` and a non-default `indexedComponents` — components should
	 * be silently ignored because the reference is not indexed.
	 */
	@Reference(
		managed = false,
		indexed = ReferenceIndexType.NONE,
		indexedComponents = { ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY }
	)
	Brand getIgnoredWhenNotIndexed();

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@ReferencedEntityGroup
		int getBrandGroup();

		@Attribute
		String getMarket();

	}

}
