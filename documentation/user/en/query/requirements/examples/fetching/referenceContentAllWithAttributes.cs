EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityPrimaryKeyInSet(105703)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContentAllWithAttributes()
        		)
        	)
        )
	)
);