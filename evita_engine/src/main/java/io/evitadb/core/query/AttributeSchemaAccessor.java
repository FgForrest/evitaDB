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

package io.evitadb.core.query;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.ofNullable;

/**
 * Attribute schema accessor provides access to the {@link AttributeSchemaContract} by the attribute name taking
 * the current context into account. The accessor needs to accept also {@link EntitySchemaContract} provided from
 * outside which is used for localization of attributes in "prefetched" entities of different types (i.e. when
 * {@link io.evitadb.api.query.head.Collection} constraint is not specified in the query).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class AttributeSchemaAccessor {
	/**
	 * Mandatory catalog schema where the {@link GlobalAttributeSchemaContract} are stored.
	 */
	@Nonnull private final CatalogSchemaContract catalogSchema;
	/**
	 * Optional {@link EntitySchemaContract} which might be null when {@link io.evitadb.api.query.head.Collection}
	 * constraint is not specified in the query
	 */
	@Nullable private final EntitySchemaContract entitySchema;
	/**
	 * Lambda that allows finding appropriate {@link ReferenceSchemaContract} from provided {@link EntitySchemaContract}.
	 * We need to use lambda because the entity schema may be different each time attribute schema is being looked up
	 * for.
	 */
	@Nullable private final Function<EntitySchemaContract, ReferenceSchemaContract> referenceSchemaAccessor;

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
				.map(it -> referenceSchema == null ? new AttributeNotFoundException(attributeName, it) : new AttributeNotFoundException(attributeName, referenceSchema, it))
				.orElseGet(() -> new AttributeNotFoundException(attributeName, catalogSchema))
		);
		EvitaInvalidUsageException exception = null;
		for (AttributeTrait attributeTrait : requiredTrait) {
			Assert.isTrue(
				referenceSchema == null || referenceSchema.isIndexed(),
				() -> new ReferenceNotIndexedException(referenceSchema.getName(), entitySchema)
			);
			switch (attributeTrait) {
				case UNIQUE ->
					exception = attributeSchema.isUnique() ? null : ofNullable(referenceSchema).map(it -> new AttributeNotFilterableException(attributeName, it, entitySchema)).orElseGet(() -> ofNullable(entitySchema).map(it -> new AttributeNotFilterableException(attributeName, it)).orElseGet(() -> new AttributeNotFilterableException(attributeName, catalogSchema)));
				case FILTERABLE ->
					exception = attributeSchema.isFilterable() || attributeSchema.isUnique() ? null : ofNullable(referenceSchema).map(it -> new AttributeNotFilterableException(attributeName, it, entitySchema)).orElseGet(() -> ofNullable(entitySchema).map(it -> new AttributeNotFilterableException(attributeName, it)).orElseGet(() -> new AttributeNotFilterableException(attributeName, catalogSchema)));
				case SORTABLE ->
					exception = attributeSchema.isSortable() ? null : ofNullable(referenceSchema).map(it -> new AttributeNotSortableException(attributeName, it, entitySchema)).orElseGet(() -> ofNullable(entitySchema).map(it -> new AttributeNotSortableException(attributeName, it)).orElseGet(() -> new AttributeNotSortableException(attributeName, catalogSchema)));
			}
		}
		if (exception != null) {
			throw exception;
		}
		return attributeSchema;
	}

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

	/**
	 * Returns {@link AttributeSchemaContract} of particular `attributeName` or throws exception.
	 * This method looks for the attributes in internal {@link #entitySchema} and doesn't allow provisioning of
	 * the schema from outside.
	 *
	 * @param attributeName name of the looked up attribute
	 * @param requiredTrait set of required attribute traits to check before returning
	 * @return attribute schema
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotFilterableException when filterable traits are requested but the attribute does not
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public AttributeSchemaContract getAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull AttributeTrait... requiredTrait
	) {
		if (entitySchema == null && referenceSchemaAccessor == null) {
			return verifyAndReturn(
				attributeName, catalogSchema.getAttribute(attributeName).orElse(null),
				catalogSchema, null, null, requiredTrait
			);
		} else {
			final ReferenceSchemaContract referenceSchema = referenceSchemaAccessor == null ? null : referenceSchemaAccessor.apply(this.entitySchema);
			final AttributeSchemaProvider<?> attributeSchemaProvider = referenceSchema == null ? entitySchema : referenceSchema;
			return verifyAndReturn(
				attributeName, attributeSchemaProvider.getAttribute(attributeName).orElse(null),
				catalogSchema, this.entitySchema,
				referenceSchema,
				requiredTrait
			);
		}
	}

	/**
	 * Returns {@link AttributeSchemaContract} of particular `attributeName` or throws exception.
	 *
	 * @param entitySchema  the entity schema that should be used for attribute lookup
	 * @param attributeName name of the looked up attribute
	 * @param requiredTrait set of required attribute traits to check before returning
	 * @return attribute schema
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotFilterableException when filterable traits are requested but the attribute does not
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
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
			attributeSchema = Objects.requireNonNullElse(referenceSchema, entitySchema)
				.getAttribute(attributeName)
				.orElse(null);
		}
		return verifyAndReturn(
			attributeName, attributeSchema, catalogSchema, entitySchema, referenceSchema, requiredTrait
		);
	}

	/**
	 * Returns {@link AttributeSchemaContract} or {@link SortableAttributeCompoundSchemaContract} of particular
	 * `attributeName` or throws exception. This method looks for the attributes in internal {@link #entitySchema} and
	 * doesn't allow provisioning of the schema from outside.
	 *
	 * @param attributeName name of the looked up attribute
	 * @return attribute schema
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public NamedSchemaContract getAttributeSchemaOrSortableAttributeCompound(@Nonnull String attributeName) {
		if (entitySchema != null) {
			return getAttributeSchemaOrSortableAttributeCompound(this.entitySchema, attributeName);
		} else {
			return verifyAndReturn(
				attributeName, catalogSchema.getAttribute(attributeName).orElse(null),
				catalogSchema, null, null, new AttributeTrait[] {AttributeTrait.SORTABLE}
			);
		}
	}

	/**
	 * Returns {@link AttributeSchemaContract} or {@link SortableAttributeCompoundSchemaContract} of particular
	 * `attributeName` or throws exception.
	 *
	 * @param entitySchema  the entity schema that should be used for attribute lookup
	 * @param attributeName name of the looked up attribute
	 * @return attribute schema
	 * @throws AttributeNotFoundException      when attribute is not found
	 * @throws AttributeNotSortableException   when sortable traits are requested but the attribute does not
	 */
	@Nonnull
	public NamedSchemaContract getAttributeSchemaOrSortableAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName
	) {
		final ReferenceSchemaContract referenceSchema = ofNullable(referenceSchemaAccessor)
			.map(it -> it.apply(entitySchema))
			.orElse(null);
		final SortableAttributeCompoundSchemaContract compoundSchema;
		compoundSchema = Objects.requireNonNullElse(referenceSchema, entitySchema)
			.getSortableAttributeCompound(attributeName)
			.orElse(null);

		if (compoundSchema != null) {
			return compoundSchema;
		}

		final AttributeSchemaContract resultSchema;
		final Optional<GlobalAttributeSchemaContract> globalAttributeSchema = catalogSchema.getAttribute(attributeName);
		if (globalAttributeSchema.isPresent()) {
			resultSchema = globalAttributeSchema.get();
		} else {
			resultSchema = Objects.requireNonNullElse(referenceSchema, entitySchema)
				.getAttribute(attributeName)
				.orElse(null);
		}
		return verifyAndReturn(
			attributeName, resultSchema, catalogSchema, entitySchema, referenceSchema,
			new AttributeTrait[] {AttributeTrait.SORTABLE}
		);
	}

	/**
	 * Method creates new instance of the accessor with initialized lambda for retrieving {@link ReferenceSchemaContract}
	 * from {@link EntitySchemaContract} based on the `referenceName`.
	 */
	@Nonnull
	public AttributeSchemaAccessor withReferenceSchemaAccessor(@Nonnull String referenceName) {
		return new AttributeSchemaAccessor(
			catalogSchema, entitySchema, entitySchema -> entitySchema.getReferenceOrThrowException(referenceName)
		);
	}

	/**
	 * Set of traits that the {@link AttributeSchemaContract} needs to fulfill in order the schema can be accepted for
	 * the caller. This mechanism allows centralizing all necessary exception handling in this class.
	 */
	public enum AttributeTrait {
		FILTERABLE, UNIQUE, SORTABLE
	}

}
