EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeIs("catalogNumber", Null)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "catalogNumber")
        		)
        	)
        )
	)
);