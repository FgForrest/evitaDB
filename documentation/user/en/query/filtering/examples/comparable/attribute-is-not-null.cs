EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeIs("catalogNumber", NotNull)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "catalogNumber")
        		)
        	)
        )
	)
);