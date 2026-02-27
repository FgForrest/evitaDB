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

package io.evitadb.test.client.query.graphql;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.SegmentDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.PaginatedListFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.RecordPageFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.RecordStripFieldHeaderDescriptor;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.Argument;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.ArgumentSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Gathers paging and entity fetch constraints into records requirement.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RecordsConverter extends RequireConverter {

	private final EntityFetchConverter entityFetchConverter;

	public RecordsConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                        @Nonnull Query query) {
		super(catalogSchema, query);
		this.entityFetchConverter = new EntityFetchConverter(catalogSchema, query);
	}

	public void convert(@Nonnull GraphQLOutputFieldsBuilder requireBuilder,
	                    @Nonnull String entityType,
	                    @Nullable Locale locale,
	                    @Nullable EntityFetch entityFetch,
	                    @Nullable Page page,
	                    @Nullable Strip strip,
	                    boolean hasExtraResults) {
		if (page != null) {
			requireBuilder.addObjectField(
				ResponseDescriptor.RECORD_PAGE,
				recordPageBuilder -> recordPageBuilder
					.addObjectField(DataChunkDescriptor.DATA, b2 ->
						this.entityFetchConverter.convert(b2, entityType, locale, entityFetch)),
				getRecordPageArguments(entityType, page)
			);
		} else if (strip != null) {
			requireBuilder.addObjectField(
				ResponseDescriptor.RECORD_STRIP,
				recordPageBuilder -> recordPageBuilder
					.addObjectField(DataChunkDescriptor.DATA, b2 ->
						this.entityFetchConverter.convert(b2, entityType, locale, entityFetch)),
				getRecordStripArguments(strip)
			);
		} else if (entityFetch != null || !hasExtraResults) {
			requireBuilder.addObjectField(
				ResponseDescriptor.RECORD_PAGE,
				recordPageBuilder -> recordPageBuilder
					.addObjectField(DataChunkDescriptor.DATA, b2 ->
						this.entityFetchConverter.convert(b2, entityType, locale, entityFetch)),
				getRecordPageArguments(entityType, new Page(null, null))
			);
		}
	}

	@Nonnull
	private ArgumentSupplier[] getRecordPageArguments(@Nonnull String entityType, @Nullable Page page) {
		if (page == null) {
			return new ArgumentSupplier[0];
		}

		final ArrayList<ArgumentSupplier> argumentSuppliers = new ArrayList<>(4);
		if (page.getPageNumber() != 1) {
			argumentSuppliers.add(
				(offset, multipleArguments) -> new Argument(
					PaginatedListFieldHeaderDescriptor.NUMBER,
					offset,
					multipleArguments,
					page.getPageNumber()
				)
			);
		}
		if (page.getPageSize() != 20) {
			argumentSuppliers.add(
				(offset, multipleArguments) -> new Argument(
					PaginatedListFieldHeaderDescriptor.SIZE,
					offset,
					multipleArguments,
					page.getPageSize()
				)
			);
		}
		if (page.getSpacing().isPresent()) {
			argumentSuppliers.add(
				(offset, multipleArguments) -> new Argument(
					RecordPageFieldHeaderDescriptor.SPACING,
					offset,
					multipleArguments,
					convertRequireConstraint(
						new SegmentDataLocator(new ManagedEntityTypePointer(entityType)),
						page.getSpacing().get()
					).orElseThrow(() -> new GenericEvitaInternalError("Spacing constraint is present but converted constraint is empty. This is weird!"))
				)
			);
		};
		return argumentSuppliers.toArray(new ArgumentSupplier[0]);
	}

	@Nonnull
	private static ArgumentSupplier[] getRecordStripArguments(@Nonnull Strip strip) {
		final ArrayList<ArgumentSupplier> argumentSuppliers = new ArrayList<>(3);

		if (strip.getOffset() != 0) {
			argumentSuppliers.add(
				(offset, multipleArguments) -> new Argument(
					RecordStripFieldHeaderDescriptor.OFFSET,
					offset,
					multipleArguments,
					strip.getOffset()
				)
			);
		}
		if (strip.getLimit() != 20) {
			argumentSuppliers.add(
				(offset, multipleArguments) -> new Argument(
					RecordStripFieldHeaderDescriptor.LIMIT,
					offset,
					multipleArguments,
					strip.getLimit()
				)
			);
		}

		return argumentSuppliers.toArray(new ArgumentSupplier[0]);
	}
}
