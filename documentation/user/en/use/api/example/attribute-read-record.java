@EntityRef("Product")
public record MyEntity(
	// contains attribute `name` if fetched and not null
	@Attribute(name = "name", localized = true)
	@Nullable String name,

	// contains attribute `name` if fetched and not null
	@AttributeRef("name")
	@Nullable String nameAgain,

	// contains attribute `markets` or null if not fetched or not set
	@AttributeRef("markets")
	@Nullable String[] markets,

	// contains attribute `markets` or empty collection if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull Collection<String> marketsAsCollection,

	// contains attribute `markets` or empty list if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull List<String> marketsAsList,

	// contains attribute `markets` or empty set if not fetched or not set (it never contains null value)
	@AttributeRef("markets")
	@Nonnull Set<String> marketsAsSet

) {

}
