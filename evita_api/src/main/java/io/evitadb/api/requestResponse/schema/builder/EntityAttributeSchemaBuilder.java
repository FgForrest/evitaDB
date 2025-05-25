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
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaRepresentativeMutation;
import io.evitadb.dataType.Scope;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Internal {@link AttributeSchemaBuilder} builder used
 * solely from within {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class EntityAttributeSchemaBuilder
	extends AbstractAttributeSchemaBuilder<EntityAttributeSchemaEditor.EntityAttributeSchemaBuilder, EntityAttributeSchemaContract>
	implements EntityAttributeSchemaEditor.EntityAttributeSchemaBuilder {
	@Serial private static final long serialVersionUID = 3063509427974161687L;
	private final List<LocalEntitySchemaMutation> mutations = new LinkedList<>();

	EntityAttributeSchemaBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityAttributeSchemaContract existingSchema
	) {
		super(null, entitySchema, existingSchema);
	}

	EntityAttributeSchemaBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> ofType
	) {
		super(null, entitySchema, EntityAttributeSchema._internalBuild(name, ofType, false));
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
				this.baseSchema.isRepresentative(),
				this.baseSchema.getType(),
				this.baseSchema.getDefaultValue(),
				this.baseSchema.getIndexedDecimalPlaces()
			)
		);
	}

	@Override
	protected Class<EntityAttributeSchemaContract> getAttributeSchemaType() {
		return EntityAttributeSchemaContract.class;
	}

	@Override
	@Nonnull
	public EntityAttributeSchemaBuilder representative() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaRepresentativeMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntityAttributeSchemaBuilder representative(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaRepresentativeMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return this;
	}

	@Override
	protected MutationImpact addMutations(@Nonnull AttributeSchemaMutation mutation) {
		return addMutations(
			this.catalogSchema, this.entitySchema, this.mutations, (LocalEntitySchemaMutation) mutation
		);
	}

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

	@Delegate(types = AttributeSchemaContract.class)
	@Nonnull
	@Override
	public EntityAttributeSchemaContract toInstance() {
		return super.toInstance();
	}

}
