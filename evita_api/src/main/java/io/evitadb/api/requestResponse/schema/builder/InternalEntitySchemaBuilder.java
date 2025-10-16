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

import io.evitadb.api.exception.AssociatedDataAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.AttributeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.ReferenceAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.SortableAttributeCompoundSchemaException;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.ReferenceSchemaBuilder.ReferenceSchemaBuilderResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.RemoveAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.UseGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.*;
import io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.NamingConvention;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Internal implementation of the {@link EntitySchemaBuilder} builder returned by {@link SealedEntitySchema} when
 * {@link SealedEntitySchema#openForWrite()} is called. This implementation does the heavy lifting of this particular
 * interface. The class should never be instantiated by the client code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public final class InternalEntitySchemaBuilder implements EntitySchemaBuilder, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = -2643204562100111998L;
	private static final LocalEntitySchemaMutation[] EMPTY_ARRAY = new LocalEntitySchemaMutation[0];

	private final EntitySchemaContract baseSchema;
	private final List<LocalEntitySchemaMutation> mutations = new LinkedList<>();
	private Supplier<CatalogSchemaContract> catalogSchemaAccessor;
	private MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	private int lastMutationReflectedInSchema = 0;
	private EntitySchemaContract updatedSchema;

	public InternalEntitySchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract baseSchema,
		@Nonnull Collection<LocalEntitySchemaMutation> schemaMutations
	) {
		this.catalogSchemaAccessor = () -> catalogSchema;
		this.baseSchema = baseSchema;
		this.updatedSchemaDirty = addMutations(
			this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
			schemaMutations.toArray(EMPTY_ARRAY)
		);
	}

	public InternalEntitySchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract baseSchema
	) {
		this(catalogSchema, baseSchema, Collections.emptyList());
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder cooperatingWith(@Nonnull Supplier<CatalogSchemaContract> catalogSupplier) {
		this.catalogSchemaAccessor = catalogSupplier;
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder verifySchemaStrictly() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.values())
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder verifySchemaButAllow(@Nonnull EvolutionMode... evolutionMode) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.values()),
				new AllowEvolutionModeInEntitySchemaMutation(evolutionMode)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder verifySchemaButCreateOnTheFly() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.values())
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withGeneratedPrimaryKey() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithGeneratedPrimaryKeyMutation(true)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutGeneratedPrimaryKey() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithGeneratedPrimaryKeyMutation(false)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withHierarchyIndexedInScope(@Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithHierarchyMutation(true, inScopes)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutHierarchy() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithHierarchyMutation(false, Scope.NO_SCOPE)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withoutHierarchyIndexedInScope(@Nonnull Scope... inScopes) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScopes);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithHierarchyMutation(
					true,
					Arrays.stream(Scope.values())
						.filter(this::isHierarchyIndexedInScope)
						.filter(it -> !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withPriceIndexedInScope(@Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(true, inScopes, 2)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withPriceIndexedInScope(int indexedDecimalPlaces, @Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(true, inScopes, indexedDecimalPlaces)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withIndexedPriceInCurrency(@Nonnull Currency[] currency, @Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(true, inScopes, 2),
				new AllowCurrencyInEntitySchemaMutation(currency)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withPriceInCurrencyIndexedInScope(int indexedPricePlaces, @Nonnull Currency[] currency, @Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(true, inScopes, indexedPricePlaces),
				new AllowCurrencyInEntitySchemaMutation(currency)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutPrice() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(false, Scope.NO_SCOPE, 0)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withoutPriceIndexedInScope(@Nonnull Scope... inScopes) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScopes);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(
					true,
					Arrays.stream(Scope.values())
						.filter(this::isPriceIndexedInScope)
						.filter(it -> !excludedScopes.contains(it))
						.toArray(Scope[]::new),
					getIndexedPricePlaces()
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutPriceInCurrency(@Nonnull Currency currency) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new DisallowCurrencyInEntitySchemaMutation(currency)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withLocale(@Nonnull Locale... locale) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new AllowLocaleInEntitySchemaMutation(locale)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutLocale(@Nonnull Locale locale) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new DisallowLocaleInEntitySchemaMutation(locale)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withGlobalAttribute(@Nonnull String attributeName) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new UseGlobalAttributeSchemaMutation(attributeName)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withAssociatedData(@Nonnull String dataName, @Nonnull Class<? extends Serializable> ofType) {
		return withAssociatedData(dataName, ofType, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withAssociatedData(
		@Nonnull String dataName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<AssociatedDataSchemaEditor> whichIs
	) {
		final Optional<AssociatedDataSchemaContract> existingAssociatedData = getAssociatedData(dataName);
		final CatalogSchemaContract catalogSchema = this.catalogSchemaAccessor.get();
		final Class<? extends Serializable> toBeAssignedType = EvitaDataTypes.isSupportedTypeOrItsArray(ofType) ?
			EvitaDataTypes.toWrappedForm(ofType) :
			ComplexDataObject.class;
		final AssociatedDataSchemaBuilder associatedDataSchemaBuilder = existingAssociatedData
			.map(it -> {
				isTrue(
					toBeAssignedType.equals(it.getType()),
					() -> new InvalidMutationException(
						"Associated data " + dataName + " has already assigned type " + it.getType() +
							", cannot change this type to: " + ofType + "!"
					)
				);
				return new AssociatedDataSchemaBuilder(catalogSchema, this.baseSchema, it);
			})
			.orElseGet(() -> new AssociatedDataSchemaBuilder(catalogSchema, this.baseSchema, dataName, ofType));

		ofNullable(whichIs).ifPresent(it -> it.accept(associatedDataSchemaBuilder));
		final AssociatedDataSchemaContract associatedDataSchema = associatedDataSchemaBuilder.toInstance();

		if (existingAssociatedData.map(it -> !it.equals(associatedDataSchema)).orElse(true)) {
			ClassifierUtils.validateClassifierFormat(ClassifierType.ASSOCIATED_DATA, dataName);
			// check the names in all naming conventions are unique in the entity schema
			getAssociatedData()
				.values()
				.stream()
				.filter(it -> !Objects.equals(it.getName(), associatedDataSchema.getName()))
				.flatMap(it -> it.getNameVariants()
					.entrySet()
					.stream()
					.filter(nameVariant -> nameVariant.getValue().equals(associatedDataSchema.getNameVariant(nameVariant.getKey())))
					.map(nameVariant -> new AssociatedDataNamingConventionConflict(it, nameVariant.getKey(), nameVariant.getValue()))
				)
				.forEach(conflict -> {
					throw new AssociatedDataAlreadyPresentInEntitySchemaException(
						conflict.conflictingSchema(), associatedDataSchema,
						conflict.convention(), conflict.conflictingName()
					);
				});
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					catalogSchema, this.baseSchema, this.mutations,
					associatedDataSchemaBuilder.toMutation().toArray(EMPTY_ARRAY)
				)
			);
		}
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutAssociatedData(@Nonnull String dataName) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new RemoveAssociatedDataSchemaMutation(dataName)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withReferenceTo(@Nonnull String name, @Nonnull String externalEntityType, @Nonnull Cardinality cardinality) {
		return withReferenceTo(name, externalEntityType, cardinality, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withReferenceTo(
		@Nonnull String name,
		@Nonnull String externalEntityType,
		@Nonnull Cardinality cardinality,
		@Nullable Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> whichIs
	) {
		final EntitySchemaContract currentSchema = toInstance();
		final ReferenceSchemaContract existingReference = currentSchema.getReference(name).orElse(null);
		final ReferenceSchemaBuilder referenceBuilder = new ReferenceSchemaBuilder(
			this.catalogSchemaAccessor.get(),
			this.baseSchema,
			existingReference,
			name,
			externalEntityType,
			false,
			cardinality,
			this.mutations,
			this.baseSchema.getReference(name).isEmpty()
		);
		ofNullable(whichIs).ifPresent(it -> it.accept(referenceBuilder));

		final ReferenceSchemaBuilderResult result = referenceBuilder.toResult();
		redefineReferenceType(
			existingReference,
			result.schema(),
			result.mutations()
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withReferenceToEntity(@Nonnull String name, @Nonnull String entityType, @Nonnull Cardinality cardinality) {
		return withReferenceToEntity(name, entityType, cardinality, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withReferenceToEntity(
		@Nonnull String name,
		@Nonnull String entityType,
		@Nonnull Cardinality cardinality,
		@Nullable Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> whichIs
	) {
		final EntitySchemaContract currentSchema = toInstance();
		final ReferenceSchemaContract existingReference = currentSchema.getReference(name).orElse(null);
		final ReferenceSchemaBuilder referenceSchemaBuilder = new ReferenceSchemaBuilder(
			this.catalogSchemaAccessor.get(),
			this.baseSchema,
			existingReference,
			name,
			entityType,
			true,
			cardinality,
			this.mutations,
			this.baseSchema.getReference(name)
				.map(ReflectedReferenceSchemaContract.class::isInstance)
				.orElse(true)
		);
		ofNullable(whichIs).ifPresent(it -> it.accept(referenceSchemaBuilder));

		final ReferenceSchemaBuilderResult result = referenceSchemaBuilder.toResult();
		redefineReferenceType(
			existingReference,
			result.schema(),
			result.mutations()
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withReflectedReferenceToEntity(@Nonnull String name, @Nonnull String entityType, @Nonnull String reflectedReferenceName) {
		return withReflectedReferenceToEntity(name, entityType, reflectedReferenceName, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withReflectedReferenceToEntity(
		@Nonnull String referenceName,
		@Nonnull String entityType,
		@Nonnull String reflectedReferenceName,
		@Nullable Consumer<ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder> whichIs
	) {
		final EntitySchemaContract currentSchema = toInstance();
		final ReferenceSchemaContract existingReference = currentSchema.getReference(referenceName).orElse(null);
		Assert.isTrue(
			existingReference == null || existingReference instanceof ReflectedReferenceSchemaContract,
			() -> new InvalidSchemaMutationException(
				"Reference `" + referenceName + "` is already created as standard reference, " +
					"you need first to remove it to create a reflected reference of such name."
			)
		);
		final ReflectedReferenceSchemaBuilder referenceSchemaBuilder = new ReflectedReferenceSchemaBuilder(
			this.catalogSchemaAccessor.get(),
			this.baseSchema,
			(ReflectedReferenceSchemaContract) existingReference,
			referenceName,
			entityType,
			reflectedReferenceName,
			this.mutations,
			this.baseSchema.getReference(referenceName)
				.map(it -> !(it instanceof ReflectedReferenceSchemaContract))
				.orElse(true)
		);
		ofNullable(whichIs).ifPresent(it -> it.accept(referenceSchemaBuilder));

		final ReflectedReferenceSchemaBuilder.ReferenceSchemaBuilderResult result = referenceSchemaBuilder.toResult();
		redefineReferenceType(
			existingReference,
			result.schema(),
			result.mutations()
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutReferenceTo(@Nonnull String name) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new RemoveReferenceSchemaMutation(name)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new ModifyEntitySchemaDescriptionMutation(description)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new ModifyEntitySchemaDeprecationNoticeMutation(deprecationNotice)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder notDeprecatedAnymore() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new ModifyEntitySchemaDeprecationNoticeMutation(null)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withAttribute(@Nonnull String attributeName, @Nonnull Class<? extends Serializable> ofType) {
		return withAttribute(attributeName, ofType, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<EntityAttributeSchemaEditor.EntityAttributeSchemaBuilder> whichIs
	) {
		final CatalogSchemaContract catalogSchema = this.catalogSchemaAccessor.get();
		catalogSchema.getAttribute(attributeName)
			.ifPresent(it -> {
				throw new AttributeAlreadyPresentInCatalogSchemaException(
					catalogSchema.getName(), Objects.requireNonNull(it)
				);
			});
		final Optional<EntityAttributeSchemaContract> existingAttribute = getAttribute(attributeName);
		final io.evitadb.api.requestResponse.schema.builder.EntityAttributeSchemaBuilder attributeSchemaBuilder =
			existingAttribute
				.map(it -> {
					final io.evitadb.api.requestResponse.schema.builder.EntityAttributeSchemaBuilder builder = new io.evitadb.api.requestResponse.schema.builder.EntityAttributeSchemaBuilder(this.baseSchema, it);
					isTrue(
						ofType.equals(it.getType()),
						() -> new AttributeAlreadyPresentInEntitySchemaException(
							it, builder.toInstance(), null, attributeName
						)
					);
					return builder;
				})
				.orElseGet(() -> new io.evitadb.api.requestResponse.schema.builder.EntityAttributeSchemaBuilder(this.baseSchema, attributeName, ofType));

		ofNullable(whichIs).ifPresent(it -> it.accept(attributeSchemaBuilder));
		final EntityAttributeSchemaContract attributeSchema = attributeSchemaBuilder.toInstance();

		EntitySchema.assertNotReferencedEntityPredecessor(attributeName, attributeSchema.getType());
		checkSortableTraits(attributeName, attributeSchema);

		// check the names in all naming conventions are unique in the catalog schema
		checkNamesAreUniqueInAllNamingConventions(
			this.getAttributes().values(),
			this.getSortableAttributeCompounds().values(),
			attributeSchema
		);

		if (existingAttribute.map(it -> !it.equals(attributeSchema)).orElse(true)) {
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					catalogSchema, this.baseSchema, this.mutations,
					attributeSchemaBuilder.toMutation().toArray(EMPTY_ARRAY)
				)
			);
		}
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutAttribute(@Nonnull String attributeName) {
		checkSortableAttributeCompoundsWithoutAttribute(
			attributeName, this.getSortableAttributeCompounds().values()
		);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new RemoveAttributeSchemaMutation(attributeName)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement... attributeElements
	) {
		return withSortableAttributeCompound(
			name, attributeElements, null
		);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement[] attributeElements,
		@Nullable Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
	) {
		final Optional<EntitySortableAttributeCompoundSchemaContract> existingCompound = getSortableAttributeCompound(name);
		final CatalogSchemaContract catalogSchema = this.catalogSchemaAccessor.get();
		final SortableAttributeCompoundSchemaBuilder builder = new SortableAttributeCompoundSchemaBuilder(
			catalogSchema,
			this,
			null,
			existingCompound.orElse(null),
			name,
			Arrays.asList(attributeElements),
			Collections.emptyList(),
			true
		);
		final SortableAttributeCompoundSchemaBuilder schemaBuilder =
			existingCompound
				.map(it -> {
					isTrue(
						it.getAttributeElements().equals(Arrays.asList(attributeElements)),
						() -> new AttributeAlreadyPresentInEntitySchemaException(
							it, builder.toInstance(), null, name
						)
					);
					return builder;
				})
				.orElse(builder);

		ofNullable(whichIs).ifPresent(it -> it.accept(schemaBuilder));
		final SortableAttributeCompoundSchemaContract compoundSchema = schemaBuilder.toInstance();
		isTrue(
			compoundSchema.getAttributeElements().size() > 1,
			() -> new SortableAttributeCompoundSchemaException(
				"Sortable attribute compound requires more than one attribute element!",
				compoundSchema
			)
		);
		isTrue(
			compoundSchema.getAttributeElements().size() ==
				compoundSchema.getAttributeElements()
					.stream()
					.map(AttributeElement::attributeName)
					.distinct()
					.count(),
			() -> new SortableAttributeCompoundSchemaException(
				"Attribute names of elements in sortable attribute compound must be unique!",
				compoundSchema
			)
		);
		checkSortableTraits(name, compoundSchema, this.getAttributes());

		// check the names in all naming conventions are unique in the catalog schema
		checkNamesAreUniqueInAllNamingConventions(
			this.getAttributes().values(),
			this.getSortableAttributeCompounds().values(),
			compoundSchema
		);

		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				catalogSchema, this, this.mutations,
				new CreateSortableAttributeCompoundSchemaMutation(
					compoundSchema.getName(),
					compoundSchema.getDescription(),
					compoundSchema.getDeprecationNotice(),
					Arrays.stream(Scope.values())
						.filter(compoundSchema::isIndexedInScope)
						.toArray(Scope[]::new),
					attributeElements
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withoutSortableAttributeCompound(@Nonnull String name) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new RemoveSortableAttributeCompoundSchemaMutation(name)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public String getName() {
		return this.baseSchema.getName();
	}

	@Nullable
	@Override
	public String getDescription() {
		return toInstance().getDescription();
	}

	@Nonnull
	@Override
	public Map<NamingConvention, String> getNameVariants() {
		return this.baseSchema.getNameVariants();
	}

	@Nonnull
	@Override
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.baseSchema.getNameVariant(namingConvention);
	}

	@Nonnull
	@Override
	public Optional<ModifyEntitySchemaMutation> toMutation() {
		return this.mutations.isEmpty() ?
			empty() :
			of(new ModifyEntitySchemaMutation(getName(), this.mutations.toArray(EMPTY_ARRAY)));
	}

	@Nonnull
	@Delegate(types = EntitySchemaContract.class, excludes = NamedSchemaContract.class)
	@Override
	public EntitySchemaContract toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flat is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			EntitySchemaContract currentSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			// apply the mutations not reflected in the schema
			for (int i = this.lastMutationReflectedInSchema; i < this.mutations.size(); i++) {
				final EntitySchemaMutation mutation = this.mutations.get(i);
				currentSchema = mutation.mutate(this.catalogSchemaAccessor.get(), currentSchema);
				if (currentSchema == null) {
					throw new GenericEvitaInternalError("Catalog schema unexpectedly removed from inside!");
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = this.mutations.size();
		}
		return this.updatedSchema;
	}

	/**
	 * Redefines the reference type in the internal entity schema builder.
	 *
	 * @param existingReference    The existing reference to be replaced. Can be null.
	 * @param newReference         The new reference to replace the existing reference.
	 * @param newReferenceMutation The collection of new reference mutations.
	 */
	void redefineReferenceType(
		@Nullable ReferenceSchemaContract existingReference,
		@Nonnull ReferenceSchemaContract newReference,
		@Nonnull Collection<LocalEntitySchemaMutation> newReferenceMutation
	) {
		if (!Objects.equals(existingReference, newReference)) {
			// remove all existing mutations for the reference schema (it needs to be replaced)
			if (this.mutations.removeIf(it -> shouldRemoveReferenceMutation(newReference, it))) {
				this.updatedSchemaDirty = updateMutationImpact(this.updatedSchemaDirty, MutationImpact.MODIFIED_PREVIOUS);
			}
			// check the names in all naming conventions are unique in the entity schema
			toInstance()
				.getReferences()
				.values()
				.stream()
				.filter(it -> !Objects.equals(it.getName(), newReference.getName()))
				.flatMap(it -> it.getNameVariants()
					.entrySet()
					.stream()
					.filter(nameVariant -> nameVariant.getValue().equals(newReference.getNameVariant(nameVariant.getKey())))
					.map(nameVariant -> new ReferenceNamingConventionConflict(it, nameVariant.getKey(), nameVariant.getValue()))
				)
				.forEach(conflict -> {
					throw new ReferenceAlreadyPresentInEntitySchemaException(
						conflict.conflictingSchema(), newReference,
						conflict.convention(), conflict.conflictingName()
					);
				});
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
					newReferenceMutation.toArray(EMPTY_ARRAY)
				)
			);
		}
	}

	/**
	 * Determines whether a reference mutation should be removed based on provided schema mutation.
	 * Method handles the situation when stored version of the schema contains reference with particular name, and
	 * we need to keep record of its removal and replacement with completely different reference sharing the same name.
	 *
	 * @param newReference The new reference schema contract to be checked.
	 * @param mutation     The local entity schema mutation to evaluate.
	 * @return true if the reference mutation should be removed, otherwise false.
	 */
	private boolean shouldRemoveReferenceMutation(
		@Nonnull ReferenceSchemaContract newReference,
		@Nonnull LocalEntitySchemaMutation mutation
	) {
		if (!(mutation instanceof ReferenceSchemaMutation referenceSchemaMutation)) {
			return false;
		} else if (referenceSchemaMutation.getName().equals(newReference.getName())) {
			// we need to respect removal mutations targeting previous references
			return !(referenceSchemaMutation instanceof RemoveReferenceSchemaMutation) ||
				this.baseSchema.getReference(newReference.getName()).isEmpty();
		} else {
			return false;
		}
	}

	/**
	 * DTO for passing the identified conflict in attribute names for certain naming convention.
	 */
	record AttributeNamingConventionConflict(
		@Nullable AttributeSchemaContract conflictingAttributeSchema,
		@Nullable SortableAttributeCompoundSchemaContract conflictingCompoundSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

	/**
	 * DTO for passing the identified conflict in associated data names for certain naming convention.
	 */
	record AssociatedDataNamingConventionConflict(
		@Nonnull AssociatedDataSchemaContract conflictingSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

	/**
	 * DTO for passing the identified conflict in reference names for certain naming convention.
	 */
	record ReferenceNamingConventionConflict(
		@Nonnull ReferenceSchemaContract conflictingSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

}
