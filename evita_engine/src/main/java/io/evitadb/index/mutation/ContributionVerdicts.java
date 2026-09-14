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

package io.evitadb.index.mutation;

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * A condition's answer, carried at the granularity it was actually answered.
 *
 * A histogram contribution is a `(referencedEntityPK, ownerPK)` pair, but a filter answers in owner PKs.
 * Where the condition reads the referenced entity, one owner's two references can disagree — and applying
 * a single owner-level verdict to both is what lets a non-qualifying reference be indexed on a qualifying
 * sibling's answer, or a withdrawn contribution survive because a sibling still holds. `perReferencedEntity`
 * is that answer resolved per reference; `null` means the condition provably cannot tell an owner's
 * references apart (it reads neither the referenced entity nor the group), so the owner-level bitmap *is*
 * the per-contribution answer and no per-reference evaluation was spent.
 *
 * **A missing key means "no contribution", not "unknown".** The map is built from the same affected-entity
 * resolution its consumers iterate, so a referenced entity absent from it was absent from the resolution —
 * it contributed nothing, and nothing about it may be added or removed. Answering a
 * miss with the owner-level bitmap instead would silently reinstate exactly the collapse this record exists
 * to prevent.
 *
 * @param allOwnerPKs         union of the owners answered positively, across every referenced entity
 * @param perReferencedEntity the same answer keyed by referenced entity PK, or `null` when owner-level
 */
public record ContributionVerdicts(
	@Nonnull Bitmap allOwnerPKs,
	@Nullable Map<Integer, Bitmap> perReferencedEntity
) {

	/**
	 * Wraps an owner-level answer — one the condition cannot resolve any finer.
	 *
	 * @param ownerPKs the owners the condition held for
	 * @return verdicts answering every referenced entity with `ownerPKs`
	 */
	@Nonnull
	public static ContributionVerdicts ownerLevel(@Nonnull Bitmap ownerPKs) {
		return new ContributionVerdicts(ownerPKs, null);
	}

	/**
	 * Returns the owners the condition held for *within the contribution of* `referencedEntityPK`.
	 *
	 * @param referencedEntityPK the referenced entity whose contribution is being decided
	 * @return the owners to act on; empty when this referenced entity contributed nothing
	 */
	@Nonnull
	public Bitmap forReferencedEntity(int referencedEntityPK) {
		if (this.perReferencedEntity == null) {
			return this.allOwnerPKs;
		}
		final Bitmap owners = this.perReferencedEntity.get(referencedEntityPK);
		return owners == null ? EmptyBitmap.INSTANCE : owners;
	}

	/**
	 * @return `true` when no contribution was answered positively at all
	 */
	public boolean isEmpty() {
		return this.allOwnerPKs.isEmpty();
	}
}
