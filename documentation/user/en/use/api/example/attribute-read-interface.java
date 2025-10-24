@EntityRef("Product")
public interface MyEntity {

	// returns attribute `name` if fetched and not null
	@Attribute(name = "name", localized = true)
	@Nullable String getName();

	// returns attribute `name` in requested locale
	// the entity must be fetched in this locale or multiple locales including the requested one
	@AttributeRef("name")
	@Nullable String getName(@Nonnull Locale locale);

	// returns attribute `name` if fetched and not null
	@AttributeRef("name")
	@Nullable String getNameAgain();

	// returns attribute `name` if not null, if not fetched (unknown state) throws exception
	@AttributeRef("name")
	@Nullable String getNameOrThrow() throws ContextMissingException;

	// returns attribute `name` or empty optional wrapper, if not fetched (unknown state) throws exception
	@AttributeRef("name")
	@Nonnull Optional<String> getNameIfPresent() throws ContextMissingException;

	// returns attribute `markets` or null if not fetched or not set
	@AttributeRef("markets")
	@Nullable String[] getMarkets();

	// returns attribute `markets` in requested locale
	// the entity must be fetched in this locale or multiple locales including the requested one
	@AttributeRef("markets")
	@Nullable String[] getMarkets(@Nonnull Locale locale);

	// returns attribute `markets` or empty collection if not fetched or not set (it never returns null value)
	@AttributeRef("markets")
	@Nonnull Collection<String> getMarketsAsCollection();

	// returns attribute `markets` or empty list if not fetched or not set (it never returns null value)
	@AttributeRef("markets")
	@Nonnull List<String> getMarketsAsList();

	// returns attribute `markets` or empty set if not fetched or not set (it never returns null value)
	@AttributeRef("markets")
	@Nonnull Set<String> getMarketsAsSet();

	// returns attribute `markets` as collection (or you can use list/set/array variants)
	// or empty optional if attribute is not set, throws exception when attribute is not fetched
	@AttributeRef("markets")
	@Nonnull Optional<Collection<String>> getMarketsAsCollectionIfPresent() throws ContextMissingException;

}
