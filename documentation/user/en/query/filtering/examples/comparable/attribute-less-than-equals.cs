EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeLessThanEquals("battery-capacity", 125)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "battery-capacity")
        		)
        	)
        )
	)
);