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

package io.evitadb.core.query;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotFoundException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Function;

import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.ofNullable;

/**
 * TODO JNO - document me and methods
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class AttributeSchemaAccessor {
	@Nonnull private final CatalogSchemaContract catalogSchema;
	@Nullable private final EntitySchemaContract entitySchema;
	@Nullable private final Function<EntitySchemaContract, ReferenceSchemaContract> referenceSchemaAccessor;

	public AttributeSchemaAccessor(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.referenceSchemaAccessor = null;
	}

	public AttributeSchemaAccessor(@Nonnull QueryContext queryContext) {
		this(
			queryContext.getCatalogSchema(),
			queryContext.isEntityTypeKnown() ? queryContext.getSchema() : null
		);
	}

	@Nonnull
	public AttributeSchemaContract getAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull AttributeTrait... requiredTrait
	) {
		if (entitySchema != null) {
			return getAttributeSchema(this.entitySchema, attributeName, requiredTrait);
		} else {
			return verifyAndReturn(
				attributeName, catalogSchema.getAttribute(attributeName).orElse(null),
				catalogSchema, null, null, requiredTrait
			);
		}
	}

	@Nonnull
	public AttributeSchemaContract getAttributeSchema(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nonnull AttributeTrait... requiredTrait
	) {
		final ReferenceSchemaContract referenceSchema = ofNullable(referenceSchemaAccessor)
			.map(it -> it.apply(entitySchema))
			.orElse(null);
		final AttributeSchemaContract attributeSchema;
		final Optional<GlobalAttributeSchemaContract> globalAttributeSchema = catalogSchema.getAttribute(attributeName);
		if (globalAttributeSchema.isPresent()) {
			attributeSchema = globalAttributeSchema.get();
		} else {
			attributeSchema = ofNullable((AttributeSchemaProvider<AttributeSchemaContract>) referenceSchema)
				.orElse(entitySchema)
				.getAttribute(attributeName).orElse(null);
		}
		return verifyAndReturn(
			attributeName, attributeSchema, catalogSchema, entitySchema, referenceSchema, requiredTrait
		);
	}

	@Nonnull
	private static AttributeSchemaContract verifyAndReturn(
		@Nonnull String attributeName,
		@Nullable AttributeSchemaContract attributeSchema,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeTrait[] requiredTrait
	) {
		notNull(
			attributeSchema,
			() -> ofNullable(entitySchema)
				.map(it -> new AttributeNotFoundException(attributeName, it))
				.orElseGet(() -> new AttributeNotFoundException(attributeName, catalogSchema))
		);
		EvitaInvalidUsageException exception = null;
		for (AttributeTrait attributeTrait : requiredTrait) {
			Assert.isTrue(
				referenceSchema == null || referenceSchema.isFilterable(),
				() -> new ReferenceNotIndexedException(referenceSchema.getName(), entitySchema)
			);
			switch (attributeTrait) {
				case UNIQUE -> exception = attributeSchema.isUnique() ? null : ofNullable(referenceSchema).map(it -> new AttributeNotFilterableException(attributeName, it, entitySchema)).orElseGet(() -> ofNullable(entitySchema).map(it -> new AttributeNotFilterableException(attributeName, it)).orElseGet(() -> new AttributeNotFilterableException(attributeName, catalogSchema)));
				case FILTERABLE -> exception = attributeSchema.isFilterable() || attributeSchema.isUnique() ? null : ofNullable(referenceSchema).map(it -> new AttributeNotFilterableException(attributeName, it, entitySchema)).orElseGet(() -> ofNullable(entitySchema).map(it -> new AttributeNotFilterableException(attributeName, it)).orElseGet(() -> new AttributeNotFilterableException(attributeName, catalogSchema)));
				case SORTABLE -> exception = attributeSchema.isSortable() ? null : ofNullable(referenceSchema).map(it -> new AttributeNotSortableException(attributeName, it, entitySchema)).orElseGet(() -> ofNullable(entitySchema).map(it -> new AttributeNotSortableException(attributeName, it)).orElseGet(() -> new AttributeNotSortableException(attributeName, catalogSchema)));
			}
		}
		if (exception != null) {
			throw exception;
		}
		return attributeSchema;
	}

	@Nonnull
	public AttributeSchemaAccessor withReferenceSchemaAccessor(@Nonnull String referenceName) {
		return new AttributeSchemaAccessor(
			catalogSchema, entitySchema, entitySchema -> entitySchema.getReferenceOrThrowException(referenceName)
		);
	}

	public enum AttributeTrait {
		FILTERABLE, UNIQUE, SORTABLE
	}

}
