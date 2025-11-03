@EntityRef("Product")
@Data
public class MyEntityEditor {

	// contains attribute `name` if fetched and not null
	@Attribute(name = "name", localized = true)
	@Nullable private String name;

	// contains attribute `name` if fetched and not null
	@AttributeRef("name")
	@Nullable private String nameAgain;

	// contains attribute `markets` or null if not fetched or not set
	@AttributeRef("markets")
	@Nullable private String[] markets;

	// contains attribute `markets` or empty collection if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull private Collection<String> marketsAsCollection;

	// contains attribute `markets` or empty list if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull private List<String> marketsAsList;

	// contains attribute `markets` or empty set if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull private Set<String> marketsAsSet;

}
