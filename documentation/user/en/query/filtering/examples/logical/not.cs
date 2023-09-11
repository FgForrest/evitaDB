EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		Not(
        			EntityPrimaryKeyInSet(110066, 106742, 110513)
        		)
        	)
        )
	)
);