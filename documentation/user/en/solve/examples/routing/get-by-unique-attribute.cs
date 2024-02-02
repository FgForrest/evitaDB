EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeEquals("code", "macbook-pro-13-2022")
        	),
        	Require(
        		EntityFetch(
        			AttributeContentAll()
        		)
        	)
        )
	)
);