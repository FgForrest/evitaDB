EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	OrderBy(
        		Random()
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);