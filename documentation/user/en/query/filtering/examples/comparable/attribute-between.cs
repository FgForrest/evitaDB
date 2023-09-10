EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeBetween("battery-capacity", 125, 160)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "battery-capacity")
        		)
        	)
        )
	)
);