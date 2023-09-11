EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeContains("code", "epix")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);