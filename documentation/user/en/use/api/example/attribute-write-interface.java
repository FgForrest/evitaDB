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

	// sets localized attribute `name` in specified locale
	// annotation is not specified and will be automatically resolved from the corresponding getter method
	// if the NULL value is set - attribute will be removed from the instance
	void setName(@Nullable String name, @Nonnull Locale locale);

	// sets localized attribute `name` in specified locale using explicit attribute pairing
	// returns reference to self instance to allow builder pattern chaining
	// if the NULL value is set - attribute will be removed from the instance
	@AttributeRef("name")
	@Nonnull
	MyEntityEditor setNameAgain(@Nullable String name, @Nonnull Locale locale);

	// sets attribute `code`
	// annotation is not specified and will be automatically resolved from the corresponding getter method
	// if the NULL value is set - attribute will be removed from the instance
	void setCode(@Nullable String code);

	// sets attribute `code` using explicit attribute pairing
	// returns reference to self instance to allow builder pattern chaining
	// if the NULL value is set - attribute will be removed from the instance
	@AttributeRef("name")
	@Nonnull
	MyEntityEditor setCodeAgain(@Nullable String code);

	// sets attribute `markets` using explicit attribute pairing
	// returns reference to self instance to allow builder pattern chaining
	// if the NULL value is set - attribute will be removed from the instance
	@AttributeRef("markets")
	@Nonnull
	MyEntityEditor setMarkets(@Nullable String[] markets);

	// sets attribute `markets` using explicit attribute pairing
	// returns reference to self instance to allow builder pattern chaining
	// if the NULL or empty collection is set - attribute will be removed from the instance
	// alternatives accepting `List` or `Set` parameters are also available
	@AttributeRef("markets")
	@Nonnull
	MyEntityEditor setMarketsAsCollection(@Nullable Collection<String> markets);

	// removes attribute `name` of particular locale from the entity
	// returns reference to self instance to allow builder pattern chaining
	// alternative for calling `setName(null, locale)`
	@RemoveWhenExists
	@AttributeRef("name")
	@Nonnull
	MyEntityEditor removeName(@Nonnull Locale locale);

	// removes attribute `code` from the entity
	// alternative for calling `setCode(null)`
	@RemoveWhenExists
	@AttributeRef("code")
	void removeCode();

	// removes attribute `code` from the entity and returns the removed value as the result
	@RemoveWhenExists
	@AttributeRef("code")
	String removeCodeAndReturnIt();

	// removes attribute `markets` from the entity and returns the removed value as the result
	// alternatives returning `List` or `Set` or array types are also available
	@AttributeRef("markets")
	@Nonnull
	Collection<String> removeMarkets();

}
