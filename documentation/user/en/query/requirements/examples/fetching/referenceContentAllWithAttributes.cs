EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
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
        			ReferenceContentAllWithAttributes(
        				AttributeContentAll(),
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