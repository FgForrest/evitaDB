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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.test.client.query.graphql;

import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.RecordPageFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.RecordStripFieldHeaderDescriptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Gathers paging and entity fetch constraints into records requirement.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RecordsConverter extends RequireConverter {

	private final EntityFetchConverter entityFetchConverter;

	public RecordsConverter(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull GraphQLInputJsonPrinter inputJsonPrinter) {
		super(catalogSchema, inputJsonPrinter);
		this.entityFetchConverter = new EntityFetchConverter(catalogSchema, inputJsonPrinter);
	}

	public void convert(@Nonnull GraphQLOutputFieldsBuilder requireBuilder,
						@Nonnull String entityType,
						@Nullable Locale locale,
						@Nullable EntityFetch entityFetch,
	                    @Nullable Page page,
	                    @Nullable Strip strip) {

		if (page != null) {
			requireBuilder.addObjectField(
				ResponseDescriptor.RECORD_PAGE,
				getRecordPageArgumentsBuilder(page),
				recordPageBuilder -> recordPageBuilder
					.addObjectField(DataChunkDescriptor.DATA, b2 ->
						entityFetchConverter.convert(b2, entityType, locale, entityFetch))
			);
		} else if (strip != null) {
			requireBuilder.addObjectField(
				ResponseDescriptor.RECORD_STRIP,
				getRecordStripArgumentsBuilder(strip),
				recordPageBuilder -> recordPageBuilder
					.addObjectField(DataChunkDescriptor.DATA, b2 ->
						entityFetchConverter.convert(b2, entityType, locale, entityFetch))
			);
		} else {
			requireBuilder.addObjectField(
				ResponseDescriptor.RECORD_PAGE,
				recordPageBuilder -> recordPageBuilder
					.addObjectField(DataChunkDescriptor.DATA, b2 ->
						entityFetchConverter.convert(b2, entityType, locale, entityFetch))
			);
		}
	}

	@Nullable
	private Consumer<GraphQLOutputFieldsBuilder> getRecordPageArgumentsBuilder(@Nonnull Page page) {
		if (page.getPageNumber() == 1 && page.getPageSize() == 20) {
			// we can ignore defaults, to make the query simpler
			return null;
		} else {
			return recordPageArgumentsBuilder -> {
				recordPageArgumentsBuilder.addFieldArgument(
					RecordPageFieldHeaderDescriptor.NUMBER,
					__ -> String.valueOf(page.getPageNumber())
				);
				recordPageArgumentsBuilder.addFieldArgument(
					RecordPageFieldHeaderDescriptor.SIZE,
					__ -> String.valueOf(page.getPageSize())
				);
			};
		}
	}

	@Nullable
	private Consumer<GraphQLOutputFieldsBuilder> getRecordStripArgumentsBuilder(@Nonnull Strip strip) {
		if (strip.getOffset() == 0 && strip.getLimit() == 20) {
			// we can ignore defaults, to make the query simpler
			return null;
		} else {
			return recordPageArgumentsBuilder -> {
				recordPageArgumentsBuilder.addFieldArgument(
					RecordStripFieldHeaderDescriptor.OFFSET,
					__ -> String.valueOf(strip.getOffset())
				);
				recordPageArgumentsBuilder.addFieldArgument(
					RecordStripFieldHeaderDescriptor.LIMIT,
					__ -> String.valueOf(strip.getLimit())
				);
			};
		}
	}
}
