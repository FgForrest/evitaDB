@Entity(
	allowedEvolution = {
		EvolutionMode.ADDING_LOCALES,
		EvolutionMode.ADDING_CURRENCIES
	}
)
public interface Product extends Serializable {
	@PrimaryKey
	int getId();

	@Attribute
	@Nonnull
	String getCode();

	@Attribute(
		name = "manufacturedBefore",
		description = "How many years ago the product was manufactured.",
		deprecated = "This attribute is obsolete.",
		filterable = true
	)
	@Nonnull
	default int[] getYears() {
		// the default implementation defines default value
		return new int[] {1978,2005,2020};
	}

	@AssociatedData
	@Nonnull
	ReferencedFiles getReferencedFiles();

	@ParentEntity
	int getParentEntity();

	@PriceForSale
	PriceContract getSellingPrice();

	@Reference
	Brand getMarketingBrand();

	@Reference
	Brand[] getLicensingBrands();

	record ReferencedFiles(@Nonnull int... fileId) implements Serializable {}

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@ReferencedEntityGroup
		int getBrandGroup();

		@Attribute
		String getMarket();

	}

}