EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeLessThan("battery-capacity", 125)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "battery-capacity")
        		)
        	)
        )
	)
);