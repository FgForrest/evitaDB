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

	// sets localized associatedData `warrantySpecification`
	// annotation is not specified and will be automatically resolved from the corresponding getter method
	// if the NULL value is set - associatedData will be removed from the instance
	void setWarrantySpecification(@Nullable String warrantySpecification, @Nonnull Locale locale);

	// sets localized associated data `warrantySpecification` in specified locale using explicit associatedData pairing
	// returns reference to self instance to allow builder pattern chaining
	// if the NULL value is set - associatedData will be removed from the instance
	@AssociatedDataRef("warrantySpecification")
	@Nonnull
	MyEntityEditor setWarrantySpecificationAgain(@Nullable String warrantySpecification, @Nonnull Locale locale);

	// sets associatedData `parameters` using explicit associatedData pairing
	// returns reference to self instance to allow builder pattern chaining
	// if the NULL value is set - associatedData will be removed from the instance
	@AssociatedDataRef("parameters")
	@Nonnull
	MyEntityEditor setParameters(@Nullable String[] parameters);

	// sets associatedData `parameters` using explicit associatedData pairing
	// returns reference to self instance to allow builder pattern chaining
	// if the NULL or empty collection is set - associated data will be removed from the instance
	// alternatives accepting `List` or `Set` parameters are also available
	@AssociatedDataRef("parameters")
	@Nonnull
	MyEntityEditor setParametersAsCollection(@Nullable Collection<String> parameters);

	// sets localized associatedData `localization`
	// annotation is not specified and will be automatically resolved from the corresponding getter method
	// if the NULL value is set - associatedData will be removed from the instance
	void setLocalization(@Nullable Localization localization, @Nonnull Locale locale);

	// removes attribute `name` of particular locale from the entity
	// returns reference to self instance to allow builder pattern chaining
	// alternative for calling `setName(null, locale)`
	@RemoveWhenExists
	@AttributeRef("warrantySpecification")
	@Nonnull
	MyEntityEditor removeWarrantySpecification(@Nonnull Locale locale);

	// removes attribute `markets` from the entity and returns the removed value as the result
	// alternatives returning `List` or `Set` or array types are also available
	@AttributeRef("parameters")
	@Nonnull
	Collection<String> removeParameters();

}
