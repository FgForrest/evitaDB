evita.updateCatalog(
	"evita",
	session -> {

		// create new product using the editor directly
		session.createNewEntity(
			ProductEditor.class, 100
		)
		
		// fill the data
		.setCode("JP328a01a")
		.setName("Creative OUTLIER FREE PRO", Locale.ENGLISH)
		.setEAN("51EF1081AA000")
		.setReferencedFiles(new Product.ReferencedFiles(5, 7))
		.addOrUpdateMarketingBrand(
			1100,
			whichIs -> whichIs
				.setMarket("EU")
				.setBrandGroup(42)
		)
		// store the product
		.upsertVia(session);

		// get existing product
		final ProductEditor storedProduct = session.getEntity(
			ProductEditor.class, 100, entityFetchAllContent()
		).orElseThrow();

		// update the data
		storedProduct
			.setName("Creative OUTLIER FREE PRO", Locale.GERMAN)
			.addOrUpdateLicensingBrand(
				1740,
				whichIs -> whichIs
					.setMarket("Asia")
					.setBrandGroup(31)
			)
			// and store the modified product back
			.upsertVia(session);
	}
);
