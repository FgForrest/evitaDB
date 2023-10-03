EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityPrimaryKeyInSet(110066, 106742, 107546, 110513)
        	),
        	OrderBy(
        		EntityPrimaryKeyInFilter()
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);