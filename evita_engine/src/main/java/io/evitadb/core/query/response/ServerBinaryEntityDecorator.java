/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.response;

import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * This class is a server-side model decorator that adds the number of I/O fetches and bytes fetched from underlying
 * storage to the binary entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Getter
public class ServerBinaryEntityDecorator extends BinaryEntity implements EntityFetchAwareDecorator {
	@Serial private static final long serialVersionUID = -8818514623860359139L;

	/**
	 * The count of I/O fetches used to load this entity from underlying storage.
	 */
	private final int ioFetchCount;
	/**
	 * The count of bytes fetched from underlying storage to load this entity.
	 */
	private final int ioFetchedBytes;

	public ServerBinaryEntityDecorator(
		@Nonnull BinaryEntity entity,
		int ioFetchCount,
		int ioFetchedBytes
	) {
		super(
			entity.getSchema(), entity.getPrimaryKey(),
			entity.getEntityStoragePart(), entity.getAttributeStorageParts(), entity.getAssociatedDataStorageParts(),
			entity.getPriceStoragePart(), entity.getReferenceStoragePart(), entity.getReferencedEntities()
		);
		this.ioFetchCount = ioFetchCount;
		this.ioFetchedBytes = ioFetchedBytes;
	}

}
