evita.DefineCatalog("testCatalog")
	.WithEntitySchema(
		"Brand",
		entitySchema => entitySchema
			.WithDescription("""
				Brand is entity that represents manufacturer or
				supplier of the product.""")
			.WithoutGeneratedPrimaryKey()
			.WithLocale(new CultureInfo("en")), new CultureInfo("de")))
			.WithAttribute<string>(
				"name",
				whichIs => whichIs
					.WithDescription("The apt brand name.")
					.Filterable()
					.Sortable()
			)
	)
	.WithEntitySchema(
		"Category",
		entitySchema => entitySchema
			.WithDescription("""
				Category is entity that forms a hierarchical tree and
				categorizes items on the e-commerce site into a better
				accessible form for the customer.""")
			.WithoutGeneratedPrimaryKey()
			.WithHierarchy()
			.WithLocale(new CultureInfo("en")), new CultureInfo("de")))
			.WithAttribute<string>(
				"name",
				whichIs => whichIs
					.WithDescription("The apt category name.")
					.Localized()
					.Filterable()
					.Sortable()
			)
	)
	.WithEntitySchema(
		"Product",
		entitySchema => entitySchema
			.WithDescription("""
				Product represents an article that can be displayed and sold on e-shop.
				Product can be organized in categories or groups.
				Product can relate to groups or brands.
				Product have prices.""")
			.WithGeneratedPrimaryKey()
			.WithLocale(new CultureInfo("en")), new CultureInfo("de")))
			.WithPriceInCurrency(
				new Currency("USD"), new Currency("EUR")
			)
			.WithAttribute<string>(
				"name",
				whichIs => whichIs
					.WithDescription("The apt product name.")
					.Localized()
					.Filterable()
					.Sortable()
					.Nullable()
			)
			.WithAttribute<string>(
				"catalogCode",
				whichIs => whichIs
					.WithDescription("Product designation in your sales catalogue.")
					.Filterable()
					.Sortable()
					.Nullable()
			)
			.WithAttribute<int>(
				"stockQuantity",
				whichIs => whichIs
					.WithDescription("Number of pieces in stock.")
					.Filterable()
					.Sortable()
					.WithDefaultValue(0)
			)
			.WithAssociatedData<string[]>(
				"gallery",
				whichIs => whichIs
					.Nullable()
					.WithDescription("List of links to images in the product gallery.")
			)
			.WithReferenceToEntity(
				"brand", "Brand", Cardinality.ZeroOrMore,
				whichIs => whichIs
					.WithDescription("Reference to the brand or manufacturer of the product.")
					.Indexed()
					.Faceted()
			)
			.WithReferenceToEntity(
				"categories", "Category", Cardinality.ZeroOrMore,
				whichIs => whichIs
					.WithDescription("Reference to one or more categories the product is listed in.")
					.Indexed()
			)
	)
	.UpdateAndFetchViaNewSession(evita);