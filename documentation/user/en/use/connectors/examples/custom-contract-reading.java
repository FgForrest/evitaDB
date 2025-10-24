evita.queryCatalog(
	"evita",
	session -> {
		// get single product by primary key
		final Optional<Product> product = session.getEntity(
			Product.class, 1, entityFetchAllContent()
		);

		// get single product by specific query
		final Optional<Product> optionalProduct = session.queryOne(
			query(
				filterBy(
					attributeEquals("code", "macbook-pro-13")
				),
				require(
					entityFetchAll()
				)
			),
			Product.class
		);

		// get multiple products in category
		final List<Product> products = session.queryList(
			query(
				filterBy(
					referenceHaving(
						"marketingBrand",
						entityHaving(
							filterBy(
								attributeEquals("code", "sony")
							)
						)
					)
				),
				require(
					entityFetchAll()
				)
			),
			Product.class
		);

		// or finally get page of products in category
		final EvitaResponse<Product> productResponse = session.query(
			query(
				filterBy(
					referenceHaving(
						"marketingBrand",
						entityHaving(
							filterBy(
								attributeEquals("code", "sony")
							)
						)
					)
				),
				require(
					entityFetchAll()
				)
			),
			Product.class
		);
	}
);
