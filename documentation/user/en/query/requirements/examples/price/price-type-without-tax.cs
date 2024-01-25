EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		PriceInPriceLists("basic"),
        		PriceInCurrency(new Currency("EUR")),
        		PriceBetween(100m, 103m)
        	),
        	Require(
        		PriceType(WithoutTax),
        		EntityFetch(
        			AttributeContent("code"),
        			PriceContentRespectingFilter()
        		)
        	)
        )
	)
);