/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

@EntityRef("Product")
public interface MyEntityEditor extends MyEntity {

	// simplified Brand entity editor interface
	// this example demonstrates the option to create referenced entities directly from the main entity
	public interface BrandEditor extends Brand {

		// setter for Brand `code` attribute
		// annotation is not specified and will be automatically resolved from the corresponding getter method
		@Nonnull BrandEditor setCode(@Nonnull String code);

	}

	// simplified Parameter entity editor interface
	// this example demonstrates the option to create referenced entities directly from the main entity reference
	public interface ParameterEditor extends Parameter {

		// setter for Parameter `code` attribute
		// annotation is not specified and will be automatically resolved from the corresponding getter method
		void setCode(String code);

	}

	// simplified ParameterGroup entity editor interface
	// this example demonstrates the option to create referenced entities directly from the main entity reference
	public interface ParameterGroupEditor extends ParameterGroup {

		// setter for ParameterGroup `code` attribute
		// annotation is not specified and will be automatically resolved from the corresponding getter method
		void setCode(String code);

	}

	// simplified product parameter reference entity editor interface
	// this example demonstrates the option to create referenced entities directly from the main entity
	public interface ProductParameterEditor extends ProductParameter {

		// setter for reference attribute
		// annotation is not specified and will be automatically resolved from the corresponding getter method
		// returns reference to self instance to allow builder pattern chaining
		@Nonnull ProductParameterEditor setPriority(@Nullable Long priority);

		// returns editor for the Parameter entity referenced by this reference
		// throws ContextMissingException if the referenced entity was not fetched from the database
		// changes in the referenced entity can be persisted separately or using upsertDeeply on product entity
		@ReferencedEntity
		@Nonnull ParameterEditor getParameterForUpdate() throws ContextMissingException;

		// removes group of the reference to ParameterGroup entity if it exists
		@ReferencedEntityGroup
		@RemoveWhenExists
		void removeParameterGroup();

		// sets group of the reference to ParameterGroup entity with passed primary key
		// if null is passed, group information on this reference is removed
		void setParameterGroup(@Nullable Integer parameterGroup);

		// sets group of the reference to ParameterGroup entity using passed `EntityReferenceContract`
		// if null is passed, group information on this reference is removed
		void setParameterGroupEntityClassifier(@Nullable EntityReferenceContract entityClassifier);

		// sets group of the reference to ParameterGroup entity using passed ParameterGroup entity
		// if null is passed, group information on this reference is removed
		void setParameterGroupEntity(@Nullable ParameterGroup groupEntity);

		// returns editor for new (non-existing) ParameterGroup entity and uses its primary key as group of the reference
		// passed Consumer implementation can setup the new ParameterGroup entity
		// changes in the referenced entity can be persisted separately or using upsertDeeply on product entity
		// returns reference to self instance to allow builder pattern chaining
		@ReferencedEntityGroup
		@CreateWhenMissing
		@Nonnull ProductParameterEditor getOrCreateParameterGroupEntity(@Nonnull Consumer<ParameterGroupEditor> groupEntity);

	}

	// sets ZERO_OR_ONE brand reference to entity with passed primary key
	// if null is passed, reference is removed
	// annotation is not specified and will be automatically resolved from the corresponding getter method
	// returns reference to self instance to allow builder pattern chaining
	@Nonnull MyEntityEditor setBrand(@Nullable Integer brandId);

	// sets ZERO_OR_ONE brand reference to passed entity
	// if null is passed, reference is removed
	// annotation is not specified and will be automatically resolved from the corresponding getter method
	// returns reference to self instance to allow builder pattern chaining
	@Nonnull void setBrand(@Nullable Brand brand);

	// sets ZERO_OR_ONE brand reference to newly created brand entity
	// which is setup in the Consumer implementation
	// returns reference to self instance to allow builder pattern chaining
	@ReferenceRef("brand")
	@Nonnull MyEntityEditor setNewBrand(@CreateWhenMissing @Nonnull Consumer<BrandEditor> brandConsumer);

	// updates existing ZERO_OR_ONE brand reference entity data
	// this method cannot be used to remove the reference or create referenced Brand entity - just to update its data
	// it's not casual to update referenced entity data, usually this form is used to update the reference itself and
	// its attributes
	// returns reference to self instance to allow builder pattern chaining
	@ReferenceRef("brand")
	@Nonnull MyEntityEditor updateBrand(@Nonnull Consumer<BrandEditor> brandConsumer);

	// creates new or updates existing ZERO_OR_ONE reference to brand entity
	// this is the alternative way of creating or updating the reference to the Brand entity
	// Brand may or may not exists and if it does not exist, it will be created when the entity is persisted
	@ReferenceRef("brand")
	@CreateWhenMissing
	@Nonnull BrandEditor getOrCreateBrand();

	// removes existing ZERO_OR_ONE brand reference (the entity itself is not removed)
	// returns reference to self instance to allow builder pattern chaining
	@ReferenceRef("brand")
	@RemoveWhenExists
	@Nonnull MyEntityEditor removeBrand();

	// removes existing ZERO_OR_ONE brand reference (the entity itself is not removed)
	// returns the removed entity or NULL if the reference was not set
	@ReferenceRef("brand")
	@RemoveWhenExists
	@Nullable Brand removeBrandAndReturnItsBody();

	// creates reference to parameter with passed primary key
	// if the reference already exists the method does nothing
	// returns reference to self instance to allow builder pattern chaining
	@ReferenceRef("parameters")
	@Nonnull MyEntityEditor addParameter(int parameterId);

	// replaces all existing references to parameters with references to passed primary keys
	// if the list is empty, all existing references to parameters are removed
	// returns reference to self instance to allow builder pattern chaining
	@ReferenceRef("parameters")
	@Nonnull MyEntityEditor setParameters(@Nonnull List<Integer> storeIds);

	// replaces all existing references to parameters with references to passed parameter entities
	// if the list is empty, all existing references to parameters are removed
	// returns reference to self instance to allow builder pattern chaining
	@ReferenceRef("parameters")
	@Nonnull MyEntityEditor setParametersAsEntities(@Nonnull List<Parameter> parameters);

	// replaces all existing references to parameters with references to passed parameter primary keys
	// if the array is empty, all existing references to parameters are removed
	@ReferenceRef("parameters")
	@Nonnull void setStoresByPrimaryKeys(int... parameterId);

	// replaces all existing references to parameters with references to passed parameter entities
	// if the array is empty, all existing references to parameters are removed
	@ReferenceRef("parameters")
	@Nonnull void setStores(Parameter... parameter);

	// similar method to `setNewBrand` but works with references with cardinality ZERO_OR_MORE or ONE_OR_MORE
	// it tries to find existing reference to parameter with primary key equal to `parameterId` and if such is found
	// its passed to `parameterEditor`, if not - new reference is created and passed to `parameterEditor`
	@ReferenceRef("parameters")
	@Nonnull MyEntityEditor createOrUpdateParameter(int parameterId, @CreateWhenMissing @Nonnull Consumer<ProductParameterEditor> parameterEditor);

	// alternative to previous method but this doesn't create new reference if it doesn't exist since it's not annotated
	// with `@CreateWhenMissing` annotation
	@ReferenceRef("parameters")
	@Nonnull MyEntityEditor updateParameter(int parameterId, @Nonnull Consumer<ProductParameterEditor> parameterEditor);

	// removes existing reference to parameter with passed primary key
	// returns reference to the removed reference or NULL if the reference was not set
	@ReferenceRef("parameters")
	@RemoveWhenExists
	@Nullable ProductParameter removeParameterAndReturnItsBody(int parameterId);

	// removes all existing references to parameters and returns their primary keys in a list
	// returns empty list if there are no references to parameters
	// method can also return Collection, Set or array with the same result
	@ReferenceRef("parameters")
	@RemoveWhenExists
	List<Integer> removeAllProductParametersAndReturnTheirIds();

	// removes all existing references to parameters and returns removed references in a list
	// returns empty list if there are no references to parameters
	// method can also return Collection, Set or array with the same result
	@ReferenceRef("parameters")
	@RemoveWhenExists
	List<ProductParameterEditor> removeAllProductCategoriesAndReturnTheirBodies();

}
