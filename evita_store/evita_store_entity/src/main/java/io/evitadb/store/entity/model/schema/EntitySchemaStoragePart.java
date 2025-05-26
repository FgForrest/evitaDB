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

package io.evitadb.store.entity.model.schema;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Storage part envelops {@link EntitySchema}. Storage part has always id fixed to 1 because there is no other schema
 * in the entity collection than this one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record EntitySchemaStoragePart(
	@Nonnull EntitySchema entitySchema
) implements StoragePart {
	@Serial private static final long serialVersionUID = -1973029963787048578L;

	@Nonnull
	@Override
	public Long getStoragePartPK() {
		return 1L;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return 1L;
	}

	@Nonnull
	@Override
	public String toString() {
		return "EntitySchemaStoragePart{" +
			"schema=" + this.entitySchema.getName() +
			'}';
	}
}
