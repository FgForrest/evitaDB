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
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.store.model.RecordWithCompressedId;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Filter index container stores cardinality index for single {@link AttributeSchema} of the single
 * {@link EntitySchema}. This container object serves only as a storage carrier for
 * {@link io.evitadb.index.cardinality.CardinalityIndex} which is a live memory representation of the data stored in
 * this container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
@AllArgsConstructor
@ToString(of = {"attributeIndexKey", "entityIndexPrimaryKey"})
public class CardinalityIndexStoragePart implements AttributeIndexStoragePart, RecordWithCompressedId<AttributeIndexKey> {
	@Serial private static final long serialVersionUID = -929865952179187357L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Contains name and locale of the indexed attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * This map contains cardinality of the attribute values. Key is the combination of attribute value and entity id.
	 * Value is the number of occurrences of this combination in the index.
	 */
	@Getter private final CardinalityIndex cardinalityIndex;
	/**
	 * Id used for lookups in file offset index for this particular container.
	 */
	@Getter @Setter private Long storagePartPK;

	@Nonnull
	@Override
	public AttributeIndexType getIndexType() {
		return AttributeIndexType.CARDINALITY;
	}

	@Override
	public AttributeIndexKey getStoragePartSourceKey() {
		return this.attributeIndexKey;
	}

}
