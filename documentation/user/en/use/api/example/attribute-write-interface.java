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
