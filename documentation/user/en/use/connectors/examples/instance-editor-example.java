public interface ProductEditor extends Product, InstanceEditor<Product> {

	ProductEditor setCode(String code);

	ProductEditor setName(String name, Locale locale);

	ProductEditor setEAN(String ean);

	@AttributeRef("manufacturedBefore")
	ProductEditor setYears(int... year);

	ProductEditor setReferencedFiles(ReferencedFiles files);

	ProductEditor setParentEntity(Integer parentId);

	@Price
	ProductEditor setPrices(PriceContract... price);

	@ReferenceRef("marketingBrand")
	ProductEditor addOrUpdateMarketingBrand(int brandId, @CreateWhenMissing Consumer<BrandEditor> brandEditor);

	@ReferenceRef("licensingBrands")
	ProductEditor addOrUpdateLicensingBrand(int brandId, @CreateWhenMissing Consumer<BrandEditor> brandEditor);

	@ReferenceRef("licensingBrands")
	@RemoveWhenExists
	ProductEditor removeLicensingBrandById(int brandId);

	interface BrandEditor extends Brand {

		BrandEditor setBrandGroup(Integer brandGroupId);

		BrandEditor setMarket(String market);

	}

}
