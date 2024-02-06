EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		PriceInPriceLists("basic"),
        		PriceInCurrency(new Currency("EUR")),
        		PriceValidInNow()
        	),
        	Require(
        		PriceHistogram(20, Standard)
        	)
        )
	)
);