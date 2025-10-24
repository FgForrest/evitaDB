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
