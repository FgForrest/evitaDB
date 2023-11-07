EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "e-readers")
        		),
        		PriceInPriceLists("basic"),
        		PriceInCurrency(new Currency("EUR")),
        		UserFilter(
        			PriceBetween(150.0m, 170.5m)
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			PriceContentRespectingFilter()
        		)
        	)
        )
	)
);