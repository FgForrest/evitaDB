EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		PriceInPriceLists("basic"),
        		PriceInCurrency(new Currency("EUR"))
        	),
        	OrderBy(
        		PriceNatural(Desc)
        	),
        	Require(
        		EntityFetch(
        			PriceContentRespectingFilter()
        		)
        	)
        )
	)
);