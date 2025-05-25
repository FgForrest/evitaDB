/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.StripList;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PaginatedListDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.StripListDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.function.Function;

/**
 * JSON serializer for {@link DataChunk} implementations.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class DataChunkJsonSerializer {

	@Nonnull private final ObjectJsonSerializer objectJsonSerializer;

	@Nonnull
	public <I extends Serializable> JsonNode serialize(@Nonnull DataChunk<I> dataChunk, @Nonnull Function<I, JsonNode> itemSerializer) {
		if (dataChunk instanceof PlainChunk<I> plainChunk) {
			return serializePlainChunk(plainChunk, itemSerializer);
		} else if (dataChunk instanceof PaginatedList<I> paginatedList) {
			return serializePaginatedList(paginatedList, itemSerializer);
		} else if (dataChunk instanceof StripList<I> stripList) {
			return serializeStripList(stripList, itemSerializer);
		} else {
			throw new RestInternalError(
				"Error during data chunk serialization.",
				"Could not serialize unsupported data chunk type `" + dataChunk.getClass().getName() + "`."
			);
		}
	}

	@Nonnull
	private <I extends Serializable> ArrayNode serializePlainChunk(@Nonnull PlainChunk<I> plainChunk,
	                                                               @Nonnull Function<I, JsonNode> itemSerializer) {
		return serializeData(plainChunk, itemSerializer);
	}

	@Nonnull
	private <I extends Serializable> ObjectNode serializePaginatedList(@Nonnull PaginatedList<I> paginatedList,
	                                                                   @Nonnull Function<I, JsonNode> itemSerializer) {
		final ObjectNode paginatedListNode = serializeBaseDataChunk(paginatedList, DataChunkType.PAGE, itemSerializer);

		paginatedListNode.put(PaginatedListDescriptor.PAGE_SIZE.name(), paginatedList.getPageSize());
		paginatedListNode.put(PaginatedListDescriptor.PAGE_NUMBER.name(), paginatedList.getPageNumber());
		paginatedListNode.put(PaginatedListDescriptor.LAST_PAGE_NUMBER.name(), paginatedList.getLastPageNumber());
		paginatedListNode.put(PaginatedListDescriptor.FIRST_PAGE_ITEM_NUMBER.name(), paginatedList.getFirstPageItemNumber());
		paginatedListNode.put(PaginatedListDescriptor.LAST_PAGE_ITEM_NUMBER.name(), paginatedList.getLastPageItemNumber());

		return paginatedListNode;
	}

	@Nonnull
	private <I extends Serializable> ObjectNode serializeStripList(@Nonnull StripList<I> stripList,
	                                                               @Nonnull Function<I, JsonNode> itemSerializer) {
		final ObjectNode stripListNode = serializeBaseDataChunk(stripList, DataChunkType.STRIP, itemSerializer);

		stripListNode.put(StripListDescriptor.OFFSET.name(), stripList.getOffset());
		stripListNode.put(StripListDescriptor.LIMIT.name(), stripList.getLimit());

		return stripListNode;
	}

	@Nonnull
	private <I extends Serializable> ObjectNode serializeBaseDataChunk(@Nonnull DataChunk<I> dataChunk,
																	   @Nonnull DataChunkType dataChunkType,
	                                                                   @Nonnull Function<I, JsonNode> itemSerializer) {
		final ObjectNode dataChunkNode = this.objectJsonSerializer.objectNode();

		final ArrayNode dataNode = serializeData(dataChunk, itemSerializer);

		dataChunkNode.putIfAbsent(DataChunkDescriptor.DATA.name(), dataNode);
		dataChunkNode.put("type", dataChunkType.name());
		dataChunkNode.put(DataChunkDescriptor.TOTAL_RECORD_COUNT.name(), dataChunk.getTotalRecordCount());
		dataChunkNode.put(DataChunkDescriptor.FIRST.name(), dataChunk.isFirst());
		dataChunkNode.put(DataChunkDescriptor.LAST.name(), dataChunk.isLast());
		dataChunkNode.put(DataChunkDescriptor.HAS_PREVIOUS.name(), dataChunk.hasPrevious());
		dataChunkNode.put(DataChunkDescriptor.HAS_NEXT.name(), dataChunk.hasNext());
		dataChunkNode.put(DataChunkDescriptor.SINGLE_PAGE.name(), dataChunk.isSinglePage());
		dataChunkNode.put(DataChunkDescriptor.EMPTY.name(), dataChunk.isEmpty());

		return dataChunkNode;
	}

	@Nonnull
	private <I extends Serializable> ArrayNode serializeData(@Nonnull DataChunk<I> dataChunk, @Nonnull Function<I, JsonNode> itemSerializer) {
		final ArrayNode dataNode = this.objectJsonSerializer.arrayNode();
		for (I item : dataChunk.getData()) {
			dataNode.add(itemSerializer.apply(item));
		}
		return dataNode;
	}
}
