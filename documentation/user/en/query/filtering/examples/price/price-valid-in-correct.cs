EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "christmas-electronics")
        		),
        		PriceInPriceLists("christmas-prices", "basic"),
        		PriceInCurrency(new Currency("EUR")),
        		PriceValidIn(
        			DateTimeOffset.Parse("2023-12-03T10:15:30+01:00", DateTimeFormatInfo.InvariantInfo)
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