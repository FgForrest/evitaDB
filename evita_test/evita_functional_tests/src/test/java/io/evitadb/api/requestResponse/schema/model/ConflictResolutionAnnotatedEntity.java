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

import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityConflictResolution;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Fixture for the class schema analyzer covering the conflict-resolution annotation elements: the entity-level
 * {@link EntityConflictResolution} and the per-item {@link ConflictResolutionOverride} on an entity attribute,
 * a global attribute, associated data, a reference and an attribute of that reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(
	name = "ConflictResolutionEntity",
	conflictResolution = @EntityConflictResolution(
		inherited = false,
		policy = ConflictPolicy.ENTITY,
		granularity = { GranularConflictPolicy.PRICE }
	)
)
@Data
@NoArgsConstructor
public class ConflictResolutionAnnotatedEntity {

	@PrimaryKey(autoGenerate = false)
	private int id;

	@Attribute(name = "inheritedCode")
	private String inheritedCode;

	@Attribute(name = "granularQuantity", conflictResolution = ConflictResolutionOverride.GRANULAR)
	private java.math.BigDecimal granularQuantity;

	@Attribute(name = "entityPinnedName", conflictResolution = ConflictResolutionOverride.ENTITY)
	private String entityPinnedName;

	@Attribute(name = "globalGranularCode", global = true, conflictResolution = ConflictResolutionOverride.GRANULAR)
	private String globalGranularCode;

	@AssociatedData(name = "granularData", conflictResolution = ConflictResolutionOverride.GRANULAR)
	private ReferencedFiles granularData;

	@Reference(
		name = "granularBrand",
		managed = false,
		entity = "brand",
		indexed = ReferenceIndexType.FOR_FILTERING,
		conflictResolution = ConflictResolutionOverride.GRANULAR
	)
	private BrandRelation granularBrand;

	record ReferencedFiles(int... fileId) implements Serializable {}

	@Data
	public static class BrandRelation {

		@ReferencedEntity
		private int brand;

		@Attribute(name = "pinnedRefAttr", conflictResolution = ConflictResolutionOverride.ENTITY)
		private String pinnedRefAttr;

	}

}
