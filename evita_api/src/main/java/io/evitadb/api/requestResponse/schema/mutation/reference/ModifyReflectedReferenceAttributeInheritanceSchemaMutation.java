/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;

/**
 * Mutation is responsible for setting value to a {@link ReflectedReferenceSchemaContract#getAttributesInheritanceBehavior()}
 * and {@link ReflectedReferenceSchemaContract#getAttributeInheritanceFilter()} in {@link ReferenceSchemaContract}.
 * Mutation can be used for altering also the existing {@link ReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class ModifyReflectedReferenceAttributeInheritanceSchemaMutation
	extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = 2119334468800302361L;

	/**
	 * Behavior of the attribute inheritance.
	 */
	@Nonnull @Getter private final AttributeInheritanceBehavior attributeInheritanceBehavior;
	/**
	 * Filter of the attributes that should be inherited.
	 */
	@Nonnull @Getter private final String[] attributeInheritanceFilter;

	/**
	 * Creates mutation that changes the attribute inheritance behavior of a reflected reference schema.
	 */
	public ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
		@Nonnull String name,
		@Nonnull AttributeInheritanceBehavior attributeInheritanceBehavior,
		@Nonnull String... attributeInheritanceFilter
	) {
		super(name);
		this.attributeInheritanceBehavior = attributeInheritanceBehavior;
		this.attributeInheritanceFilter = attributeInheritanceFilter;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof ModifyReflectedReferenceAttributeInheritanceSchemaMutation theExistingMutation
			&& this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		Assert.isPremiseValid(
			referenceSchema instanceof ReflectedReferenceSchema,
			"Reference schema `" + referenceSchema.getName() + "` represents standard reference and not " +
				"the reflected one! Cannot be mutated by this mutation!"
		);
		return ((ReflectedReferenceSchema) referenceSchema).withAttributeInheritance(
			this.attributeInheritanceBehavior, this.attributeInheritanceFilter
		);
	}

	@Override
	public String toString() {
		return "Modify entity reflected reference `" + this.name + "` schema: " +
			"attributes inherited: " + this.attributeInheritanceBehavior +
			(
				ArrayUtils.isEmpty(this.attributeInheritanceFilter) ?
					"" :
					(this.attributeInheritanceBehavior == AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT ?
						", excluding: " : ", only: ") +
						Arrays.toString(this.attributeInheritanceFilter)
			);
	}
}
