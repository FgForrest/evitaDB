/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.dataType.PaginatedList;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * This class is used to convert information from {@link io.evitadb.dataType.PaginatedList} into form serializable into JSON.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Getter
public class PaginatedListDto extends DataChunkDto {

	private final int pageSize;
	private final int pageNumber;
	private final int lastPageNumber;
	private final int firstPageItemNumber;
	private final int lastPageItemNumber;

	public PaginatedListDto(@Nonnull PaginatedList<?> paginatedList, @Nonnull JsonNode data) {
		super(paginatedList, data, DataChunkType.PAGE);
		pageSize = paginatedList.getPageSize();
		pageNumber = paginatedList.getPageNumber();
		lastPageNumber = paginatedList.getLastPageNumber();
		firstPageItemNumber = paginatedList.getFirstPageItemNumber();
		lastPageItemNumber = paginatedList.getLastPageItemNumber();
	}
}
