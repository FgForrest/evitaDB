@Data
public class MyEntity {

	// contains attribute `name` if fetched and not null
	@Attribute(name = "name", locale = true)
	@Nullable private final String name;

	// contains attribute `name` if fetched and not null
	@AttributeRef("name")
	@Nullable private final String nameAgain;

	// contains attribute `name` or empty optional wrapper
	@AttributeRef("name")
	@Nonnull private final Optional<String> name;

	// contains attribute `markets` or null if not fetched or not set
	@AttributeRef("markets")
	@Nullable private final String[] markets;

	// contains attribute `markets` or empty collection if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull private final Collection<String> marketsAsCollection;

	// contains attribute `markets` or empty list if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull private final List<String> marketsAsList;

	// contains attribute `markets` or empty set if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull private final Set<String> marketsAsSet;

	// contains attribute `markets` as collection (or you can use list/set/array variants)
	// or empty optional if attribute is not set or fetched
	@AttributeRef("markets")
	@Nonnull private final Optional<Collection<String>> marketsAsCollection;

}