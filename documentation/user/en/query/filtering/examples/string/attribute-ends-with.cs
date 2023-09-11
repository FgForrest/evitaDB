EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeEndsWith("code", "solar")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);