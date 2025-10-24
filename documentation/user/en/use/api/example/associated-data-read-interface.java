@EntityRef("Product")
public interface MyEntity {

	// returns associated data `warrantySpecification` if fetched and not null
	@AssociatedData(name = "warrantySpecification", localized = true)
	@Nullable String getWarrantySpecification();

	// returns associated data `warrantySpecification` in requested locale
	// the entity must be fetched in this locale or multiple locales including the requested one
	@AssociatedDataRef("warrantySpecification")
	@Nullable String getWarrantySpecification(@Nonnull Locale locale);

	// returns associated data `warrantySpecification` if fetched and not null
	@AssociatedDataRef("warrantySpecification")
	@Nullable String getWarrantySpecificationAgain();

	// returns associated data `warrantySpecification` if not null, if not fetched (unknown state) throws exception
	@AssociatedDataRef("warrantySpecification")
	@Nullable String getWarrantySpecificationOrThrow() throws ContextMissingException;

	// returns associated data `warrantySpecification` or empty optional wrapper, if not fetched (unknown state) throws exception
	@AssociatedDataRef("warrantySpecification")
	@Nonnull Optional<String> getWarrantySpecificationIfPresent() throws ContextMissingException;

	// returns associated data `parameters` or null if not fetched or not set
	@AssociatedDataRef("parameters")
	@Nullable String[] getParameters();

	// returns associated data `parameters` in requested locale
	// the entity must be fetched in this locale or multiple locales including the requested one
	@AssociatedDataRef("parameters")
	@Nullable String[] getParameters(@Nonnull Locale locale);

	// returns associated data `parameters` or empty collection if not fetched or not set (it never returns null value)
	@AssociatedDataRef("parameters")
	@Nonnull Collection<String> getParametersAsCollection();

	// returns associated data `parameters` or empty list if not fetched or not set (it never returns null value)
	@AssociatedDataRef("parameters")
	@Nonnull List<String> getParametersAsList();

	// returns associated data `parameters` or empty set if not fetched or not set (it never returns null value)
	@AssociatedDataRef("parameters")
	@Nonnull Set<String> getParametersAsSet();

	// returns associated data `parameters` as collection (or you can use list/set/array variants)
	// or empty optional if associated data is not set, throws exception when associated data is not fetched
	@AssociatedDataRef("parameters")
	@Nonnull Optional<Collection<String>> getParametersAsCollectionIfPresent() throws ContextMissingException;

	// returns associated data `localization` or null if not fetched or not set
	@AssociatedDataRef("localization")
	@Nullable Localization getLocalization();

	// returns associated data `localization` in requested locale
	// the entity must be fetched in this locale or multiple locales including the requested one
	@AssociatedDataRef("localization")
	@Nullable Localization getLocalization(@Nonnull Locale locale);

	// returns associated data `localization` or empty optional if not fetched or not set
	@AssociatedDataRef("localization")
	@Nullable Optional<Localization> getLocalizationIfPresent();

	// returns associated data `localization` or empty optional if not set
	// if not fetched (unknown state) throws exception
	@AssociatedDataRef("localization")
	@Nullable Optional<Localization> getLocalizationIfPresentOrThrow() throws ContextMissingException;

	public record Localization(
		@Nonnull Map<String, String> texts
	) { }

}
