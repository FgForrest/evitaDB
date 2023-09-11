EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeInSet(
        			"code", 
        			"garmin-fenix-6-solar", 
        			"garmin-approach-s42-2", 
        			"garmin-vivomove-luxe", 
        			"garmin-vivomove-luxe-2"
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);