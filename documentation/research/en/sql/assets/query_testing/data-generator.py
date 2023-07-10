from faker import Faker
import random
import time
from datetime import datetime

fake = Faker()
localeList = ["eng", "cze", "deu"]
facetGroupsNameList = ["Storage Type", "Format", "Brands", "Storage capacity", "Processor series", "Socket", "Number of processor cores", "Processor frequency", "Chip manufacturer", "Model designation", "Use", "GPU Passmark", "Size of operational RAM, Cooling type", "Diemensions", "Consumptions", "Backlight", "Basic connectors", "Standards", "Features", "Cable construction", "Chipset", "Motherboard form factor", "Number od RAM slots", "Series", "Memory", "Advanced parametrs", "Connectors", "Usage", "Size of operational RAM"]
prodCatIds = {'product':[], 'category':[]}
typeList = ["adjustedPricePolicy","brand","category","group","parameterItem","parameterType","paymentMethod","product","shippingMethod"] 
#"priceList"
priceLists = []
priceListsValidity = []
n = int(input("For how many entities should data be generated?: "))
start = time.time()
mt = time.time()
print("Starting...")

def prepareValidity():
    startBoundaryTypes = ["(", "["]
    endBoundaryTypes = [")", "]"]
    for i in range(1,6):
        minDate = datetime.combine(fake.date_between('-1y','now'),datetime.min.time())
        maxDate = datetime.combine(fake.date_between('now','+1y'),datetime.min.time())
        dt = 'null' if i % 2 == 0 else str(random.choice(startBoundaryTypes)) + str(minDate) + "," + str(maxDate) + str(random.choice(endBoundaryTypes))
        priceLists.append(i)
        priceListsValidity.append(dt)

def generateEntities():
    referenceHandlingList = ["FIRST_OCCURANCE","SUM"]

    script = "INSERT INTO t_entity(id,primaryKey,\"type\",parentEntityPrimaryKey,siblingsOrder,priceInnerEntityReferenceHandling) VALUES\n"

    for i in range(n):
        type = random.choice(typeList)
        parent = "null"
        if i % 4 == 0:
            type = "product"
        #if len(priceLists) < 5:
        #    priceLists.append(i)
        #    type = "priceList"
        if type == "product":
            if i % 2 == 1 and i > 20:
                parent = random.choice(prodCatIds['product'])
            prodCatIds['product'].append(i)
        elif type == "category":
            if i % 2 == 0 and i > 20:
                parent = random.choice(prodCatIds['category'])
            prodCatIds['category'].append(i)
        script += "({}, {}, '{}', {}, {}, '{}'),\n".format(i,i,type,parent, "null",random.choice(referenceHandlingList))

    script = script[:-2]
    script+=";"
    global mt
    print("Entities generation took " + str(round(time.time() - mt,2)) + " seconds [" + str(n) + " entities]")
    mt = time.time()
    return script

def generateAssociatedData():
    associatedDataNameList = ["phones","gaming","televisions","accessories","smart","monitors","wearables","laptops","printers","appliances"]

    script = "INSERT INTO t_associatedData(id,entityId,name,locale,data) VALUES\n"
    index = 0
    for i in range(n):
        for j in range(len(localeList)):
            for k in range(10):
                script += "({}, {}, '{}', '{}', '{}'),\n".format(index,i,associatedDataNameList[k],random.choice(localeList), str(fake.pydict(4,True,'str')).replace('\'', '\"'))
                index += 1

    script = script[:-2]
    script+=";"
    global mt
    print("Associated data generation took " + str(round(time.time() - mt,2)) + " seconds [" + str(index) + " associated data]")
    mt = time.time()
    return script

def generateAttributes():
    attributeNameList = ["visibility","validity","name","status","alias","catalogNumber","code","orderQuantity","ean","priority","referencedFiles","producerRole","availabilityExtension","saleRestriction","unitCode","minOrderAmount","productOnStock","cezarCode","onTheWay","stepOrderAmount",]

    script = "INSERT INTO t_attribute(id,entityId,name,filterable,sortable,locale,stringValue,intValue,dateValue,floatValuePrecision,floatValueScale,rangeValue) VALUES\n"

    valueTypes = ["string", "int", "date", "float", "range"]
    startBoundaryTypes = ["(", "["]
    endBoundaryTypes = [")", "]"]
    index = 0
    for i in range(n):
        for j in range(len(localeList)):
            for k in range(0,random.randint(0,20)):
                value = random.choice(valueTypes)
                if value == "string":
                    script += "({}, {}, '{}', {}, {}, '{}', '{}', {}, {}, {}, {}, {}),\n".format(index,i,attributeNameList[k],random.choice([True, False]), random.choice([True, False]),random.choice(localeList),fake.pystr(5,10),"null","null","null","null","null")
                elif value == "int":
                    script += "({}, {}, '{}', {}, {}, '{}', {}, {}, {}, {}, {}, {}),\n".format(index,i,attributeNameList[k],random.choice([True, False]), random.choice([True, False]),random.choice(localeList),"null",random.randint(-100,1000),"null","null","null","null")
                elif value == "date":
                    script += "({}, {}, '{}', {}, {}, '{}', {}, {}, '{}', {}, {}, {}),\n".format(index,i,attributeNameList[k],random.choice([True, False]), random.choice([True, False]),random.choice(localeList),"null","null",datetime.combine(fake.date_between('-10y','+10y'),datetime.min.time()),"null","null","null",)
                elif value == "float":
                    script += "({}, {}, '{}', {}, {}, '{}', {}, {}, {}, {}, {}, {}),\n".format(index,i,attributeNameList[k],random.choice([True, False]), random.choice([True, False]),random.choice(localeList),"null","null","null",random.randint(-100,1000),random.randint(0,99),"null")
                elif value == "range":
                    min = random.randint(0,1000)
                    script += "({}, {}, '{}', {}, {}, '{}', {}, {}, {}, {}, {}, '{}'),\n".format(index,i,attributeNameList[k],random.choice([True, False]), random.choice([True, False]),random.choice(localeList),"null","null","null","null","null",str(random.choice(startBoundaryTypes)) + str(min) + "," + str(random.randint(min,10000)) + str(random.choice(endBoundaryTypes)))
                index+=1

    script = script[:-2]
    script+=";"
    global mt
    print("Attributes generation took " + str(round(time.time() - mt,2)) + " seconds [" + str(index + 1) + " attributes]")
    mt = time.time()
    return script

def generateFacetGroups():
    script = "INSERT INTO t_facetGroup(id,primaryKey,\"type\") VALUES\n"
    for i in range(len(facetGroupsNameList)):
        script += "({}, {}, '{}'),\n".format(i,i,facetGroupsNameList[i])

    script = script[:-2]
    script+=";"
    global mt
    print("Facet groups generation took " + str(round(time.time() - mt,2)) + " seconds [" + str(len(facetGroupsNameList)) + " facet groups]")
    mt = time.time()
    return script

def generateFacets():
    script = "INSERT INTO t_facet(id,facetGroupId,entityId,referencedEntityPrimaryKey,referencedEntityType,indexed,attributes) VALUES\n"
    keys = prodCatIds['product'] + prodCatIds['category']
    index = 0
    for i in range(len(keys)):
        for j in range(random.randint(10,50)):
            script += "({}, {}, {}, {}, '{}', {}, '{}'),\n".format(index,random.randint(0,len(facetGroupsNameList) - 1),keys[i],random.randint(0,n * 2),random.choice(typeList),random.choice([True, False]), str(fake.pydict(4,True,'str')).replace('\'', '\"'))
            index+=1

    script = script[:-2]
    script+=";"
    global mt
    print("Facet generation took " + str(round(time.time() - mt,2)) + " seconds [" + str(index) + " facets]")
    mt = time.time()
    return script

def generatePrices():
    script = "INSERT INTO t_price(id,primaryKey,entityId,priceWithoutVAT,priceWithVAT,vat,innerEntityPrimaryKey,currency,priceList,priority,validRange,indexed) VALUES\n"
    currency = ['czk', 'eur', 'usd']
    vats = [10,15,21] 
    keys = prodCatIds['product']
    index = 0
    for i in range(len(keys)):
        for j in range(len(currency)):
            for k in range(len(priceLists)):
                if random.randint(0,4) < 3:
                    withoutVat = round(random.uniform(100,10000),2)
                    vat = random.choice(vats)
                    withVat = round(withoutVat * ((100 + vat) / 100),2)
                    dt = priceListsValidity[k]
                    if dt == 'null':
                        script += "({}, {}, {}, {}, {}, {}, {}, '{}', {}, {}, {}, {}),\n".format(index,index,keys[i],withoutVat,withVat,vat,random.randint(0,n),currency[j],priceLists[k],random.randint(1,5),dt,random.choice([True, False]))
                    else:
                        script += "({}, {}, {}, {}, {}, {}, {}, '{}', {}, {}, '{}', {}),\n".format(index,index,keys[i],withoutVat,withVat,vat,random.randint(0,n),currency[j],priceLists[k],random.randint(1,5),dt,random.choice([True, False]))
                    index+=1

    script = script[:-2]
    script+=";"
    global mt
    print("Prices generation took " + str(round(time.time() - mt,2)) + " seconds [" + str(index) + " prices]")
    mt = time.time()
    return script

prepareValidity()
finalScript = generateEntities() + "\n\n"
finalScript += generateAssociatedData() + "\n\n"
finalScript += generateAttributes() + "\n\n"
finalScript += generateFacetGroups() + "\n\n"
finalScript += generateFacets() + "\n\n"
finalScript += generatePrices()

f = open("D:\\temp\\generate-data.sql","w")
f.write(finalScript)
f.close()

print("\nFinished after " + str(round(time.time() - start,2)) + " seconds")