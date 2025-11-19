@EntityRef("Product")
@Data
public class MyEntityEditor {

	// example ComplexDataObject type
	public record Localization(
		@Nonnull Map<String, String> texts
	) { }

	// contains associated data `warrantySpecification` if fetched and not null
	@AssociatedData(name = "warrantySpecification", localized = true)
	@Nullable private String warrantySpecification;

	// contains associated data `warrantySpecification` if fetched and not null
	@AssociatedDataRef("warrantySpecification")
	@Nullable private String warrantySpecificationAgain;

	// contains associated data `parameters` or null if not fetched or not set
	@AssociatedDataRef("parameters")
	@Nullable private String[] parameters;

	// contains associated data `parameters` or empty collection if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull private Collection<String> parametersAsCollection;

	// contains associated data `parameters` or empty list if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull private List<String> parametersAsList;

	// contains associated data `parameters` or empty set if not fetched or not set (it never contains null value)
	@AssociatedDataRef("parameters")
	@Nonnull private Set<String> parametersAsSet;

	// contains associated data `localization` or null if not fetched or not set
	@AssociatedDataRef("localization")
	@Nullable private Localization localization;

}
