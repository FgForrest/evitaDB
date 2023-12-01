public record MyEntity(
	// contains attribute `name` if fetched and not null
	@Attribute(name = "name", locale = true)
	@Nullable String name,

	// contains attribute `name` if fetched and not null
	@AttributeRef("name")
	@Nullable String nameAgain,

	// contains attribute `name` or empty optional wrapper
	@AttributeRef("name")
	@Nonnull Optional<String> name,

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
	@Nonnull Set<String> marketsAsSet,

	// contains attribute `markets` as collection (or you can use list/set/array variants)
	// or empty optional if attribute is not set or fetched
	@AttributeRef("markets")
	@Nonnull Optional<Collection<String>> marketsAsCollection
) {
	
}