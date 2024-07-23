/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.referential.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.referential.nosql.config.Config;
import io.evitadb.referential.nosql.model.*;
import io.evitadb.referential.nosql.repository.EntityRepository;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;

/***
 *  No extra information provided - see (selfexplanatory) method signatures.
 *  I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 *  @author Stɇvɇn Kamenik (kamenik.stepan@gmail.cz) (c) 2020
 **/
@Log4j2
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = Config.class)
public class ESSpikeTest {

    @Autowired
    private EntityRepository entityRepository;
    @Autowired
    private ElasticsearchOperations elasticsearchTemplate;

    @Test
    public void initIndex() throws IOException {

//        Map<String, Object> mapping = elasticsearchTemplate.indexOps(Entity.class).getMapping();
//        elasticsearchTemplate.indexOps(Entity.class).delete();
//
//        Map<String, Object> map = new HashMap<>();
//        map.put("refresh_interval", "1m");
//        map.put("number_of_shards", "6");
//        map.put("number_of_replicas", "1");
//        elasticsearchTemplate.indexOps(Entity.class).create(Document.from(map));
//        elasticsearchTemplate.indexOps(Entity.class).putMapping(Document.from(mapping));

        // In case you doesn't have any geneated data, generate them first!
//         generateDataToFile();

        ObjectMapper mapper = new ObjectMapper();
        System.out.println("saving to file");
        // Reading entities from file
        List<Entity> entities = mapper.readValue(new File("generated_entities_with_hierarchy.json"), new TypeReference<>() {
        });

        System.out.println("saving to repo");
        int page = 1;
        for (int i = 0; i < 1000; i++) {
            entityRepository.saveAll(entities.stream().skip((page * 100L) - 100).limit(100).collect(Collectors.toList()));
            page++;
        }
    }

    protected void generateDataToFile() throws IOException {

        Random random = new Random();
        Map<Integer, String> randomStrings = new HashMap<>();

        for (int i = 0; i < 5000; i++) {
            randomStrings.put(i, getRndmString());
        }
        List<Entity> entities = new ArrayList<>(100001);
        List<Entity> categories = new LinkedList<>();
        entities.add(generateCategory(randomStrings,random,1,categories));
        for (int j = 0; j < 100000; j++) {
            if (j%100 == 0) log.info("---------" + j);
            entities.add(generateEntity(randomStrings,random,categories));
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(new File("generated_entities_with_hierarchy.json"), entities );
    }

    protected Entity generateCategory(Map<Integer, String> randomStrings,Random random, int level, List<Entity> categories){
        Entity entity = new Entity();
        entity.setName(randomStrings.get(random.nextInt(5000)));
        entity.setEntityType("category");
        entity.setPrimaryKey(random.nextInt(5000));

        if (level < 5){
            List<Entity> subCategories = new LinkedList<>();
            for (int i = 0; i <random.nextInt(8); i++) {
                Entity category = generateCategory(randomStrings, random, ++level, categories);
                categories.add(category);
                subCategories.add(category);
            }
            entity.setHierarchyChild(subCategories);
        }

        List<String> subCategoryCodes = new LinkedList<>();
        getSubCategoryCodes(subCategoryCodes,entity);
        entity.setFacets(Collections.singletonList(getFacet(random, subCategoryCodes)));

        return entity;
    }

    private void getSubCategoryCodes(List<String> categoryCodes,Entity entity){
        Optional
            .ofNullable(entity.getHierarchyChild())
            .orElse(Collections.emptyList())
            .forEach(i->{
                categoryCodes.add(i.getName());
                getSubCategoryCodes(categoryCodes,i);
        });
    }

    protected Entity generateEntity(Map<Integer, String> randomStrings, Random random, List<Entity> categories){

        Entity entity = new Entity();
        entity.setName(randomStrings.get(random.nextInt(5000)));
        entity.setEntityType("product");
        entity.setPrimaryKey(random.nextInt(5000));
        entity.setAttributes(asList(getAttribute(random, randomStrings), getAttribute(random, randomStrings), getAttribute(random, randomStrings)));

        entity.setFacets(asList(getProductFacet(random,categories), getFacet(random, randomStrings), getFacet(random, randomStrings)));
        entity.setAssociatedData(asList(getValue(random, randomStrings), getValue(random, randomStrings), getValue(random, randomStrings)));
        entity.setPriceInnerEntityReferenceHandling("FIRST_VARIANT");
        entity.setPrices(asList(getPrice(random), getPrice(random), getPrice(random)));

        return entity;
    }
    protected LowerEntity generateLowerEntity(Map<Integer, String> randomStrings,String type){

        Random random = new Random();
        LowerEntity entity = new LowerEntity();
        entity.setName(randomStrings.get(random.nextInt(5000)));
        entity.setEntityType(type);
        entity.setPrimaryKey(random.nextInt(5000));
        entity.setAttributes(asList(getAttribute(random, randomStrings), getAttribute(random, randomStrings), getAttribute(random, randomStrings)));

        return entity;
    }

    private Price getPrice(Random random) {
        Price price = new Price();
        price.setPriceId(random.nextInt(5000));
        double priceWithoutVat = random.nextInt(5000);
        double vat = priceWithoutVat * 0.21;
        double priceWithVat = priceWithoutVat + vat;
        price.setPriceWithVAT(priceWithVat);
        price.setPriceWithoutVAT(priceWithoutVat);
        price.setPriceVAT(vat);
        price.setInnerEntityId(random.nextInt(5000));
        price.setCurrency(random.nextBoolean()?"CZK":"EUR");
        price.setPriceListId(random.nextBoolean()?"basic":"reference");
        price.setIndexed(random.nextBoolean());
        price.setPriority(random.nextInt(10000));
        price.setValidFrom(new DateTime().minusDays(25).toDate());
        price.setValidTo(new DateTime().plusDays(25).toDate());
        return price;
    }

    //    Attributes
    private Attribute getAttribute(Random random, Map<Integer, String> randomStrings) {
        Attribute facet = new Attribute();

        facet.setName(randomStrings.get(random.nextInt(5000)));
        facet.setUnique(random.nextBoolean());
        facet.setFilterable(random.nextBoolean());
        facet.setSortable(random.nextBoolean());
        facet.setDecimalPlacesCount(2);
        facet.setValues(asList(getValue(random, randomStrings), getValue(random, randomStrings)));
        return facet;
    }

    private Value getValue(Random random, Map<Integer, String> randomStrings) {
        return random.nextBoolean()
                ? new Value(null, null, null, new Integer[]{1, 2, 3, 4}, null)
                : new Value(random.nextBoolean() ? "cs" : "en", randomStrings.get(random.nextInt(5000)), null, null, null);
    }

    //    FACET
    private Facet getFacet(Random random, Map<Integer, String> randomStrings) {
        Facet facet = new Facet();
        facet.setFacetId(random.nextInt(5000));
        facet.setEntityType("tag");
        facet.setIndexed(random.nextBoolean());
        facet.setFacetGroups(asList(getFacetGroup(random,randomStrings), getFacetGroup(random,randomStrings)));
        facet.setEntity(generateLowerEntity(randomStrings,asList("brand","category","group","parameterItem").get(random.nextInt(4))));
        return facet;
    }


    private Facet getProductFacet(Random random, List<Entity> categories) {

        Entity entity = categories.get(random.nextInt(categories.size()));
        List<String> stringStream = entity.getFacets().get(0).getAttributes().stream().map(Facet.Attribute::getPath).collect(Collectors.toList());
        return getFacet(random,stringStream);
    }


    //    FACET
    private Facet getFacet(Random random, List<String> paths) {
        Facet facet = new Facet();
        facet.setFacetId(5111);
        facet.setEntityType("category");
        facet.setIndexed(true);
        facet.setAttributes(paths.stream().map(i->getFacetAttributes(random,i)).collect(Collectors.toList()));
        return facet;
    }

    private Facet.FacetGroup getFacetGroup(Random random, Map<Integer, String> randomStrings){
        Facet.FacetGroup facetGroup = new Facet.FacetGroup();
        facetGroup.setEntityType("parameterType");
        facetGroup.setPrimaryKey(random.nextInt(5000));
        facetGroup.setEntity(generateLowerEntity(randomStrings,"parameterType"));
        return facetGroup;
    }

    private Facet.Attribute getFacetAttributes(Random random, String path) {
        Facet.Attribute facetAttr = new Facet.Attribute();
        facetAttr.setParentCatalog(random.nextBoolean()?"demo":"secondCatalog");
        facetAttr.setPath(path);
        return facetAttr;
    }

    private String getRndmString() {
        return RandomStringUtils.randomAlphabetic(10);
    }
}
