/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.requestResponse;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.dataType.DataChunk;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * This class passes simple references to the found entities - i.e. full {@link EntityContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class EvitaEntityResponse<T extends Serializable> extends EvitaResponse<T> {
	@Getter private final int[] primaryKeys;

	public EvitaEntityResponse(
		@Nonnull Query sourceQuery,
		@Nonnull DataChunk<T> recordPage,
		@Nonnull int[] primaryKeys
	) {
		super(sourceQuery, recordPage);
		this.primaryKeys = primaryKeys;
	}

	public EvitaEntityResponse(
		@Nonnull Query sourceQuery,
		@Nonnull DataChunk<T> recordPage,
		@Nonnull int[] primaryKeys,
		@Nonnull EvitaResponseExtraResult... extraResults
	) {
		super(sourceQuery, recordPage, extraResults);
		this.primaryKeys = primaryKeys;
	}

}
