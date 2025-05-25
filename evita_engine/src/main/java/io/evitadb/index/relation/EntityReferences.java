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

package io.evitadb.index.relation;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * EntityReference DTO record contains information about referenced entities from particular entity. The primary key
 * of the source entity is used as a key in {@link io.evitadb.index.ReferencedTypeEntityIndex#entityReferencedEntities}
 * index. The DTO tracks only primary keys of referenced entities.
 *
 * This DTO allows us to quickly access the information about entity relations so that we can handle constraints:
 *
 * - {@link io.evitadb.api.query.filter.EntityHaving}
 * - {@link io.evitadb.api.query.require.ReferenceContent}
 *
 * @param referencedEntityPrimaryKeys      aggregation of all {@link ReferenceContract#getReferencedPrimaryKey()} of the entity
 *                                         the array consists of sorted distinct entity primary keys
 * @param referencedEntityGroupPrimaryKeys aggregation of all {@link ReferenceContract#getGroup()} of the entity
 *                                         the array consists of sorted possibly repeated entity group primary keys
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public record EntityReferences(
	@Nonnull int[] referencedEntityPrimaryKeys,
	@Nonnull int[] referencedEntityGroupPrimaryKeys
) {

	public EntityReferences(int referencedEntityPrimaryKey) {
		this(new int[]{referencedEntityPrimaryKey}, ArrayUtils.EMPTY_INT_ARRAY);
	}

	public EntityReferences(int referencedEntityPrimaryKey, int referencedEntityGroupPrimaryKey) {
		this(new int[]{referencedEntityPrimaryKey}, new int[]{referencedEntityGroupPrimaryKey});
	}

	/**
	 * Creates copy of this immutable DTO adding a new primary key to {@link #referencedEntityPrimaryKeys}.
	 */
	@Nonnull
	public EntityReferences withReferencedEntityPrimaryKey(
		int entityPrimaryKey,
		int referencedEntityPrimaryKey,
		@Nonnull String schemaName
	) {
		Assert.isTrue(
			!containsReferencedEntityPrimaryKey(referencedEntityPrimaryKey),
			() -> "Referenced entity `" + referencedEntityPrimaryKey + "` for `" + schemaName +
				"` entity with pk `" + entityPrimaryKey + "` is already indexed!"
		);
		return new EntityReferences(
			ArrayUtils.insertIntIntoOrderedArray(referencedEntityPrimaryKey, this.referencedEntityPrimaryKeys),
			this.referencedEntityGroupPrimaryKeys
		);
	}

	/**
	 * Creates copy of this immutable DTO removing an existing primary key from {@link #referencedEntityPrimaryKeys}.
	 */
	@Nonnull
	public EntityReferences withoutReferencedEntityPrimaryKey(
		int entityPrimaryKey,
		int referencedEntityPrimaryKey,
		@Nonnull String schemaName
	) {
		Assert.isTrue(
			containsReferencedEntityPrimaryKey(referencedEntityPrimaryKey),
			() -> "Referenced entity `" + referencedEntityPrimaryKey + "` for `" + schemaName +
				"` entity with pk `" + entityPrimaryKey + "` is not indexed!"
		);
		return new EntityReferences(
			ArrayUtils.removeIntFromOrderedArray(referencedEntityPrimaryKey, this.referencedEntityPrimaryKeys),
			this.referencedEntityGroupPrimaryKeys
		);
	}

	/**
	 * Creates copy of this immutable DTO adding a new primary key to {@link #referencedEntityGroupPrimaryKeys}.
	 */
	@Nonnull
	public EntityReferences withReferencedEntityGroupPrimaryKey(int referencedGroupEntityPrimaryKey) {
		final InsertionPosition position = ArrayUtils.computeInsertPositionOfIntInOrderedArray(
			referencedGroupEntityPrimaryKey, this.referencedEntityGroupPrimaryKeys
		);
		return new EntityReferences(
			this.referencedEntityPrimaryKeys,
			ArrayUtils.insertIntIntoArrayOnIndex(
				referencedGroupEntityPrimaryKey, this.referencedEntityGroupPrimaryKeys, position.position()
			)
		);
	}

	/**
	 * Creates copy of this immutable DTO removing an existing primary key from {@link #referencedEntityGroupPrimaryKeys}.
	 */
	public EntityReferences withoutReferencedEntityGroupPrimaryKey(
		int entityPrimaryKey,
		int referencedGroupEntityPrimaryKey,
		@Nonnull String schemaName
	) {
		Assert.isTrue(
			containsReferencedEntityGroupPrimaryKey(referencedGroupEntityPrimaryKey),
			() -> "Referenced entity group `" + referencedGroupEntityPrimaryKey + "` for `" + schemaName +
				"` entity with pk `" + entityPrimaryKey + "` is not indexed!"
		);
		return new EntityReferences(
			this.referencedEntityPrimaryKeys,
			ArrayUtils.removeIntFromOrderedArray(referencedGroupEntityPrimaryKey, this.referencedEntityGroupPrimaryKeys)
		);
	}

	/**
	 * Returns true if this DTO is empty and contains no references to primary keys.
	 */
	public boolean isEmpty() {
		return ArrayUtils.isEmpty(this.referencedEntityPrimaryKeys) &&
			ArrayUtils.isEmpty(this.referencedEntityGroupPrimaryKeys);
	}

	private boolean containsReferencedEntityPrimaryKey(int referencedEntityPrimaryKey) {
		return ArrayUtils.indexOf(referencedEntityPrimaryKey, this.referencedEntityPrimaryKeys) >= 0;
	}

	private boolean containsReferencedEntityGroupPrimaryKey(int referencedGroupEntityPrimaryKey) {
		return ArrayUtils.indexOf(referencedGroupEntityPrimaryKey, this.referencedEntityGroupPrimaryKeys) >= 0;
	}

}
