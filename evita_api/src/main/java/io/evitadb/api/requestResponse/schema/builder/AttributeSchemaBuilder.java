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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.dataType.Scope;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Internal {@link io.evitadb.api.requestResponse.schema.AttributeSchemaEditor.AttributeSchemaBuilder} builder used
 * solely from within {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class AttributeSchemaBuilder
	extends AbstractAttributeSchemaBuilder<AttributeSchemaEditor.AttributeSchemaBuilder, AttributeSchemaContract>
	implements AttributeSchemaEditor.AttributeSchemaBuilder {
	@Serial private static final long serialVersionUID = 3063509427974161687L;
	private final List<LocalEntitySchemaMutation> mutations = new LinkedList<>();

	AttributeSchemaBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AttributeSchemaContract existingSchema
	) {
		super(null, entitySchema, existingSchema);
	}

	AttributeSchemaBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> ofType
	) {
		super(null, entitySchema, AttributeSchema._internalBuild(name, ofType, false));
		this.mutations.add(
			new CreateAttributeSchemaMutation(
				this.baseSchema.getName(),
				this.baseSchema.getDescription(),
				this.baseSchema.getDeprecationNotice(),
				Arrays.stream(Scope.values())
					.map(scope -> new ScopedAttributeUniquenessType(scope, this.baseSchema.getUniquenessType(scope)))
					// filter out default values
					.filter(it -> it.uniquenessType() != AttributeUniquenessType.NOT_UNIQUE)
					.toArray(ScopedAttributeUniquenessType[]::new),
				Arrays.stream(Scope.values()).filter(this.baseSchema::isFilterableInScope).toArray(Scope[]::new),
				Arrays.stream(Scope.values()).filter(this.baseSchema::isSortableInScope).toArray(Scope[]::new),
				this.baseSchema.isLocalized(),
				this.baseSchema.isNullable(),
				false,
				this.baseSchema.getType(),
				this.baseSchema.getDefaultValue(),
				this.baseSchema.getIndexedDecimalPlaces()
			)
		);
	}

	@Override
	protected Class<AttributeSchemaContract> getAttributeSchemaType() {
		return AttributeSchemaContract.class;
	}

	@Override
	protected MutationImpact addMutations(@Nonnull AttributeSchemaMutation mutation) {
		return addMutations(
			this.catalogSchema, this.entitySchema, this.mutations, (LocalEntitySchemaMutation) mutation
		);
	}

	@Override
	@Nonnull
	public Collection<LocalEntitySchemaMutation> toMutation() {
		return this.mutations;
	}

	@Nonnull
	@Override
	public List<AttributeSchemaMutation> toAttributeMutation() {
		// faster version of the:
		/* return this.mutations
			.stream()
			.map(it -> (AttributeSchemaMutation) it)
			.collect(Collectors.toList());
			*/
		//noinspection unchecked,rawtypes
		return (List) this.mutations;
	}

	@Override
	@Nonnull
	public Collection<ReferenceSchemaMutation> toReferenceMutation(@Nonnull String referenceName) {
		return this.mutations
			.stream()
			.map(it -> new ModifyReferenceAttributeSchemaMutation(referenceName, (ReferenceAttributeSchemaMutation) it))
			.collect(Collectors.toList());
	}

	@Delegate(types = AttributeSchemaContract.class)
	@Nonnull
	@Override
	public AttributeSchemaContract toInstance() {
		return super.toInstance();
	}

}
