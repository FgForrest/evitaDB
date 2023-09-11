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
        			ReferenceContentWithAttributes(
        				"parameterValues",
        				AttributeContent("variant"),
        				EntityFetch(
        					AttributeContent("code")
        				),
        				EntityGroupFetch(
        					AttributeContent("code")
        				)
        			)
        		)
        	)
        )
	)
);