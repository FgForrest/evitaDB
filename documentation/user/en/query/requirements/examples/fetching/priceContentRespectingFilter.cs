EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityPrimaryKeyInSet(103885),
        		PriceInCurrency(new Currency("EUR")),
        		PriceInPriceLists("employee-basic-price", "basic")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			PriceContentRespectingFilter("reference")
        		)
        	)
        )
	)
);