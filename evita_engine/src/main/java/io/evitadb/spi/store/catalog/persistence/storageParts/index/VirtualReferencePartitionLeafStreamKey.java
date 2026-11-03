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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Identifies the page stream of one {@link io.evitadb.index.VirtualReferencePartitionIndex}. Its leaf pages are keyed
 * by `pack(streamId, pageSequence)` ({@link VirtualReferencePartitionIndexLeafPagePart}), and since the page sequence
 * consumes one half of that key, the stream id must fold the whole identity of the index - the owning entity index and
 * the reference name - into a single `int`. Registering this key with the catalog's {@link KeyCompressor} yields that
 * `int`: unique, restart-stable and never reassigned.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public class VirtualReferencePartitionLeafStreamKey
	implements Comparable<VirtualReferencePartitionLeafStreamKey>, Serializable {
	@Serial private static final long serialVersionUID = 7229991143002596929L;

	/**
	 * Primary key of the owning {@link io.evitadb.index.EntityIndex}. Part of the identity because the same reference
	 * name exists independently in every entity index.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * Name of the reference the index belongs to.
	 */
	@Nonnull @Getter private final String referenceName;

	/**
	 * Creates the page-stream key of the index of the given reference in the given entity index.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning {@link io.evitadb.index.EntityIndex}
	 * @param referenceName         name of the reference the index belongs to
	 */
	public VirtualReferencePartitionLeafStreamKey(int entityIndexPrimaryKey, @Nonnull String referenceName) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
	}

	@Override
	public int compareTo(@Nonnull VirtualReferencePartitionLeafStreamKey o) {
		final int pkResult = Integer.compare(this.entityIndexPrimaryKey, o.entityIndexPrimaryKey);
		return pkResult != 0 ? pkResult : this.referenceName.compareTo(o.referenceName);
	}

}
