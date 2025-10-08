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

package io.evitadb.store.spi.model.storageParts.index;

import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.store.model.RecordWithCompressedId;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Map;

/**
 * Chain index container stores index for single {@link AttributeSchema} of the single
 * {@link EntitySchema}. This container object serves only as a storage carrier for
 * {@link io.evitadb.index.attribute.ChainIndex} which is a live memory representation of the data stored in this
 * container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@RequiredArgsConstructor
@AllArgsConstructor
@ToString(of = "attributeIndexKey")
public class ChainIndexStoragePart implements AttributeIndexStoragePart, RecordWithCompressedId<AttributeIndexKey> {
	@Serial private static final long serialVersionUID = 8894604958733971199L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Contains name and locale of the indexed attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Index contains information about non-interrupted chains of predecessors for an entity which is not a head entity
	 * but is part of different chain (inconsistent state).
	 */
	@Getter private final Map<Integer, ChainElementState> elementStates;
	/**
	 * Index contains tuples of entity primary key and its predecessor primary key. The conflicting primary key is
	 * a value and the predecessor primary key is a key.
	 *
	 * Conflicting keys are keys that:
	 *
	 * - refer to the same predecessor multiple times
	 * - refer to the predecessor that is transiently referring to them (circular reference)
	 *
	 * The key is the conflicting primary key and the value is the predecessor primary key.
	 */
	@Getter private final int[][] chains;
	/**
	 * Id used for lookups in persistent data storage for this particular container.
	 */
	@Getter @Setter private Long storagePartPK;

	@Nonnull
	@Override
	public AttributeIndexType getIndexType() {
		return AttributeIndexType.CHAIN;
	}

	@Override
	public AttributeIndexKey getStoragePartSourceKey() {
		return this.attributeIndexKey;
	}


}
