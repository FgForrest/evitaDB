EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		PriceInPriceLists("vip-group-1-level", "vip-group-2-level", "vip-group-3-level")
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