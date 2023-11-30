public interface ProductEditor implements InstanceEditor<Product> {

	void setCode(@Nonnull String code);

	@AttributeRef("manufacturedBefore")
	void setYears(int... year);

	void setReferencedFiles(@Nonnull Product.ReferencedFiles files);

	void setParentEntity(@Nullable Integer parentId);

	@Price
	void setPrices(PriceContract... price);

	@Reference
	void setMarketingBrand(int brandId, @Nonnull Consumer<BrandEditor> brandEditor);

	@Reference
	void addOrUpdateLicensingBrand(int brandId, @Nonnull Consumer<BrandEditor> brandEditor);

	@ReferenceRef("licensingBrands")
	void removeLicensingBrandById(int brandId);

	interface BrandEditor extends Product.Brand {

		void setBrandGroup(@Nullable Integer brandGroupId);

		void setMarket(@NullableString market);

	}

}