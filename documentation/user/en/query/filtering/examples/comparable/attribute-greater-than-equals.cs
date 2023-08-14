EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeGreaterThanEquals("battery-life", "40")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "battery-life")
        		)
        	)
        )
	)
);