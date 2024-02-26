EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en")),
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("url", "/en/smartwatches")
        		),
        		PriceInPriceLists("basic"),
        		PriceInCurrency(new Currency("EUR")),
        		PriceValidInNow(),
        		UserFilter(
        			PriceBetween(50m, 400m)
        		)
        	),
        	Require(
        		PriceHistogram(10, Standard)
        	)
        )
	)
);