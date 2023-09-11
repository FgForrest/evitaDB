EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeGreaterThan("battery-life", "40")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "battery-life")
        		)
        	)
        )
	)
);