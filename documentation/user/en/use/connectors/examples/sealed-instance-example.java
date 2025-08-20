@Entity(
	allowedEvolution = {
		EvolutionMode.ADDING_LOCALES,
		EvolutionMode.ADDING_CURRENCIES
	}
)
public interface Product extends SealedInstance<Product, ProductEditor>, Serializable {

	@PrimaryKey
	int getId();

	@Attribute(
		name = "code",
		description = "Unique code of the product.",
		unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
	)
	@Nonnull
	String getCode();

	@Attribute(localized = true)
	String getName();

	@Attribute
	String getEAN();

	@Attribute(
		name = "manufacturedBefore",
		description = "How many years ago the product was manufactured.",
		deprecated = "This attribute is obsolete.",
		filterable = true,
		nullable = true
	)
	default int[] getYears() {
		// the default implementation defines default value
		return new int[] {1978,2005,2020};
	}

	@AssociatedData
	ReferencedFiles getReferencedFiles();

	@ParentEntity
	int getParentEntity();

	@PriceForSale
	PriceContract getSellingPrice();

	@Reference(indexed = FOR_FILTERING, managed = false, groupEntityManaged = false)
	Brand getMarketingBrand();

	@Reference(managed = false, groupEntityManaged = false)
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