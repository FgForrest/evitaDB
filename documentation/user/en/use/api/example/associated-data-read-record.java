@EntityRef("Product")
public record MyEntity(
	// contains associated data `warrantySpecification` if fetched and not null
	@AssociatedData(name = "warrantySpecification", localized = true)
	@Nullable String warrantySpecification,

	// contains associated data `warrantySpecification` if fetched and not null
	@AssociatedDataRef("warrantySpecification")
	@Nullable String warrantySpecificationAgain,

	// contains associated data `parameters` or null if not fetched or not set
	@AssociatedDataRef("parameters")
	@Nullable String[] parameters,

	// contains associated data `parameters` or empty collection if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull Collection<String> parametersAsCollection,

	// contains associated data `parameters` or empty list if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull List<String> parametersAsList,

	// contains associated data `parameters` or empty set if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull Set<String> parametersAsSet,

	// contains associated data `localization` or null if not fetched or not set
	@AssociatedDataRef("localization")
	@Nullable Localization localization

) {

	public record Localization(
		@Nonnull Map<String, String> texts
	) { }

}
