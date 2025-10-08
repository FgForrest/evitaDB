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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Arrays;

import static java.util.Optional.ofNullable;

/**
 * Binary form of the {@link Entity}. For detailed documentation and reasons why the binary form exits see
 * {@link io.evitadb.api.requestResponse.EvitaBinaryEntityResponse}.
 *
 * @see io.evitadb.api.requestResponse.EvitaBinaryEntityResponse
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class BinaryEntity implements EntityClassifier {
	@Serial private static final long serialVersionUID = -913146908981560231L;

	/**
	 * Contains definition of the entity.
	 */
	@Getter private final EntitySchemaContract schema;

	@Getter @Nonnull private final Integer primaryKey;
	@Getter private final byte[] entityStoragePart;
	@Getter private final byte[][] attributeStorageParts;
	@Getter private final byte[][] associatedDataStorageParts;
	@Getter private final byte[] priceStoragePart;
	@Getter private final byte[] referenceStoragePart;
	@Getter private final BinaryEntity[] referencedEntities;

	@Nonnull
	@Override
	public String getType() {
		return this.schema.getName();
	}

	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE
			+ 5 * MemoryMeasuringConstants.ARRAY_BASE_SIZE
			+ this.entityStoragePart.length
			+ ofNullable(this.attributeStorageParts).stream().flatMap(Arrays::stream).mapToInt(it -> MemoryMeasuringConstants.ARRAY_BASE_SIZE + it.length).sum()
			+ ofNullable(this.associatedDataStorageParts).stream().flatMap(Arrays::stream).mapToInt(it -> MemoryMeasuringConstants.ARRAY_BASE_SIZE + it.length).sum()
			+ ofNullable(this.priceStoragePart).map(it -> it.length).orElse(0)
			+ ofNullable(this.referenceStoragePart).map(it -> it.length).orElse(0);
	}

}
