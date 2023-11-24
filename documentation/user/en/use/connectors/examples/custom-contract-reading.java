evita.queryCatalog(
	"testCatalog",
	session -> {
		// get single product by primary key
		final Optional<Product> product = session.getEntity(
			Product.class, 1, entityFetchAllContent()
		);

		// get single product by specific query
		final Optional<ProductInterface> product = session.queryOne(
			query(
				filterBy(
					attributeEquals("code", "macbook-pro-13")
				),
				require(
					entityFetchAll()
				)
			),
			ProductInterface.class
		);

		// get multiple products in category
		final List<ProductInterface> products = session.queryList(
			query(
				filterBy(
					hierarchyWithin(
						"categories", 
						filterBy(
							attributeEquals("code", "laptops")
						)
					)
				),
				require(
					entityFetchAll()
				)
			),
			ProductInterface.class
		);

		// or finally get page of products in category
		final EvitaResponse<ProductInterface> productResponse = session.query(
			query(
				filterBy(
					hierarchyWithin(
						"categories",
						filterBy(
							attributeEquals("code", "laptops")
						)
					)
				),
				require(
					entityFetchAll()
				)
			),
			ProductInterface.class
		);
	}
);