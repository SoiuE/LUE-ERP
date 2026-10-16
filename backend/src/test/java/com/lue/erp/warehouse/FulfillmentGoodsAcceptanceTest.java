package com.lue.erp.warehouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LUE_FULFILLMENT_TEST_URL",matches=".+")
class FulfillmentGoodsAcceptanceTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean JdbcTemplate db(DataSource ds){return new JdbcTemplate(ds);}
        @Bean DataSourceTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean AuthService auth(JdbcTemplate db){return new AuthService(db,new BCryptPasswordEncoder());}
        @Bean FulfillmentController fulfillment(JdbcTemplate db,AuthService auth){return new FulfillmentController(db,auth,new ObjectMapper());}
        @Bean GoodsProcurementController procurement(JdbcTemplate db,AuthService auth){return new GoodsProcurementController(db,auth,new ObjectMapper());}
    }
    private JdbcTemplate db;
    private FulfillmentController fulfillment;
    private GoodsProcurementController procurement;
    private AuthUser user;
    private AnnotationConfigApplicationContext context;
    private long pending,bin1,bin2,good1,good2,material,document,item;
    private final MockHttpServletRequest request=new MockHttpServletRequest();

    @BeforeEach void setup(){
        String url=System.getenv("LUE_FULFILLMENT_TEST_URL");assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:33483/lue_fulfillment_acceptance?"));
        String schema="lue_fulfillment_acceptance_"+Long.toUnsignedString(System.nanoTime());
        new JdbcTemplate(new DriverManagerDataSource(url.replace("/lue_fulfillment_acceptance?","/mysql?"),"root","")).execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        DataSource ds=new DriverManagerDataSource(url.replace("/lue_fulfillment_acceptance?","/"+schema+"?"),"root","");db=new JdbcTemplate(ds);
        Flyway flyway=Flyway.configure().dataSource(ds).cleanDisabled(true).load();flyway.migrate();flyway.validate();
        db.update("INSERT INTO users(id,username,password_hash,display_name) VALUES (1,'fulfill-test','unused','Fulfillment Test')");
        db.update("INSERT INTO roles(id,code,name) VALUES (1,'ADMIN','Admin')");db.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)");
        db.update("INSERT INTO role_permissions(role_id,permission_id) SELECT 1,id FROM permissions WHERE code IN ('menu.fulfillment','button.fulfillment.operate','button.fulfillment.import','button.fulfillment.package.position','warehouse.goods.purchase','warehouse.goods.putaway','warehouse.location_stock.view','menu.purchases')");
        db.update("INSERT INTO suppliers(id,supplier_code,supplier_name) VALUES (1,'FUL-SUP','Supplier')");
        db.update("INSERT INTO skus(id,internal_sku,product_name,default_product_packaging_plan) VALUES (1,'FUL-SKU','Kit',CAST('[{\"materialCode\":\"BAG\",\"quantity\":1}]' AS JSON))");
        db.update("INSERT INTO warehouse_goods(goods_code,goods_name,unit,reference_purchase_price,barcode) VALUES ('FG-A','Good A','pcs',99,'A-BAR'),('FG-B','Good B','pcs',88,'B-BAR')");
        good1=db.queryForObject("SELECT id FROM warehouse_goods WHERE goods_code='FG-A'",Long.class);good2=db.queryForObject("SELECT id FROM warehouse_goods WHERE goods_code='FG-B'",Long.class);
        db.update("INSERT INTO sku_composition_versions(sku_id,version_no,change_reason) VALUES (1,1,'acceptance')");long version=db.queryForObject("SELECT id FROM sku_composition_versions WHERE sku_id=1",Long.class);
        db.update("INSERT INTO sku_composition_items(version_id,warehouse_goods_id,quantity) VALUES (?,?,2),(?,?,1)",version,good1,version,good2);
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,node_kind,node_code,node_name) VALUES (1,'AREA','A','Area')");long area=node("A");
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (1,?,'SHELF','S','Shelf')",area);long shelf=node("S");
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (1,?,'LEVEL','L','Level')",shelf);long level=node("L");
        pending=bin(level,"PENDING","PENDING_PUTAWAY");bin1=bin(level,"PICK-1","PRIMARY_PICK");bin2=bin(level,"STOCK-2","STORAGE");
        db.update("INSERT INTO warehouse_goods_location_assignments(warehouse_id,warehouse_goods_id,location_id,is_primary_pick) VALUES (1,?,?,TRUE)",good1,bin1);
        db.update("INSERT INTO package_materials(material_code,material_name,category,unit) VALUES ('BAG','Bag','PRODUCT_PACKAGING','pcs')");material=db.queryForObject("SELECT id FROM package_materials WHERE material_code='BAG'",Long.class);
        db.update("INSERT INTO package_stocks(material_id,warehouse_id,available_qty) VALUES (?,1,20)",material);
        db.update("INSERT INTO fulfillment_documents(document_no,document_type,shop_identifier,source_document_id) VALUES ('FD-TEST','JIT_ORDER','test-shop','order-1')");document=db.queryForObject("SELECT id FROM fulfillment_documents WHERE document_no='FD-TEST'",Long.class);
        db.update("INSERT INTO fulfillment_document_items(document_id,source_line_key,platform_sku,sku_id,ordered_qty,order_date) VALUES (?,'test-key','PLAT-SKU',1,3,CURRENT_DATE)",document);item=db.queryForObject("SELECT id FROM fulfillment_document_items WHERE document_id=?",Long.class,document);
        context=new AnnotationConfigApplicationContext();context.registerBean(DataSource.class,()->ds);context.register(Config.class);context.refresh();
        fulfillment=context.getBean(FulfillmentController.class);procurement=context.getBean(GoodsProcurementController.class);user=context.getBean(AuthService.class).loadUser(1);
    }
    @AfterEach void close(){if(context!=null)context.close();}

    @Test void splitGoodsBinsPackagesCostAndRetry(){
        Map<String,Object> created=procurement.create(Map.of("idempotencyKey","po-1","supplierId",1,"warehouseId",1,"items",List.of(Map.of("goodsId",good1,"quantity",6,"suggestedPrice",10),Map.of("goodsId",good2,"quantity",3,"suggestedPrice",20))),user).data();long po=((Number)created.get("id")).longValue();
        List<Map<String,Object>> poItems=(List<Map<String,Object>>)procurement.purchase(po,user).data().get("items");
        List<Map<String,Object>> confirmation=new ArrayList<>();for(Map<String,Object> row:poItems)confirmation.add(Map.of("itemId",row.get("id"),"confirmedQty",row.get("orderedQty")));
        procurement.confirm(po,Map.of("idempotencyKey","confirm-1","items",confirmation),user);
        for(Map<String,Object> row:poItems){long goods=((Number)row.get("goodsId")).longValue(),poItem=((Number)row.get("id")).longValue();int qty=goods==good1?6:3;int price=goods==good1?10:20;procurement.receive(po,Map.of("idempotencyKey","receive-"+goods,"itemId",poItem,"warehouseId",1,"locationId",pending,"quantity",qty,"actualUnitPrice",price),user);if(goods==good1){procurement.putaway(Map.of("idempotencyKey","put-a","warehouseId",1,"goodsId",goods,"sourceLocationId",pending,"targetLocationId",bin1,"quantity",2),user);procurement.putaway(Map.of("idempotencyKey","put-b","warehouseId",1,"goodsId",goods,"sourceLocationId",pending,"targetLocationId",bin2,"quantity",4),user);}else procurement.putaway(Map.of("idempotencyKey","put-c","warehouseId",1,"goodsId",goods,"sourceLocationId",pending,"targetLocationId",bin1,"quantity",3),user);}
        fulfillment.positionPackage(Map.of("idempotencyKey","pkg-pos","warehouseId",1,"materialId",material,"locationId",bin1,"quantity",20,"actualUnitCost","0.5000","costSource","confirmed opening"),user,request);
        long dispatch=((Number)fulfillment.createDispatch(document,Map.of("idempotencyKey","dispatch-1","items",List.of(Map.of("itemId",item,"quantity",3))),user,request).data().get("id")).longValue();
        assertEquals(dispatch,((Number)fulfillment.createDispatch(document,Map.of("idempotencyKey","dispatch-1","items",List.of(Map.of("itemId",item,"quantity",3))),user,request).data().get("id")).longValue());
        Map<String,Object> detail=fulfillment.dispatchDetail(dispatch,user).data();List<Map<String,Object>> tasks=(List<Map<String,Object>>)detail.get("goodsTasks");assertEquals(2,tasks.size());
        long taskA=tasks.stream().filter(x->((Number)x.get("goodsId")).longValue()==good1).map(x->((Number)x.get("taskId")).longValue()).findFirst().orElseThrow();long taskB=tasks.stream().filter(x->((Number)x.get("goodsId")).longValue()==good2).map(x->((Number)x.get("taskId")).longValue()).findFirst().orElseThrow();
        assertEquals(bin1,((Number)((List<Map<String,Object>>)tasks.get(0).get("locations")).get(0).get("locationId")).longValue());
        fulfillment.pick(dispatch,Map.of("taskId",taskA,"locationId",bin1,"barcode","A-BAR","locationScan","PICK-1","quantity",2),user,request);
        fulfillment.pick(dispatch,Map.of("taskId",taskA,"locationId",bin2,"quantity",4),user,request);
        fulfillment.pick(dispatch,Map.of("taskId",taskB,"locationId",bin1,"barcode","B-BAR","quantity",3),user,request);
        assertThrows(IllegalArgumentException.class,()->fulfillment.pick(dispatch,Map.of("taskId",taskA,"locationId",bin1,"barcode","B-BAR","quantity",1),user,request));
        List<Map<String,Object>> suggestion=(List<Map<String,Object>>)fulfillment.packageSuggestions(dispatch,user).data().get("items");assertEquals(1,suggestion.size());
        Map<String,Object> usage=Map.of("dispatchItemId",suggestion.get(0).get("dispatchItemId"),"materialId",material,"locationId",bin1,"quantity",3);
        Map<String,Object> handoff=Map.of("handoffReference","REF-1","packageUsages",List.of(usage));
        assertDecimal("3",(BigDecimal)fulfillment.handoff(dispatch,handoff,user,request).data().get("shippedQty"));
        assertEquals(true,fulfillment.handoff(dispatch,handoff,user,request).data().get("duplicate"));
        assertDecimal("0",db.queryForObject("SELECT quantity FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good1));
        assertDecimal("0",db.queryForObject("SELECT quantity FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good2));
        assertDecimal("0",db.queryForObject("SELECT available_qty FROM warehouse_goods_location_balances WHERE warehouse_goods_id=? AND location_id=?",BigDecimal.class,good1,bin2));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_stock_flows WHERE flow_type='FULFILLMENT_OUTBOUND'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM package_location_flows WHERE flow_type='FULFILLMENT_OUT'",Integer.class));
        assertDecimal("10",db.queryForObject("SELECT unit_cost_snapshot FROM warehouse_goods_stock_documents WHERE fulfillment_dispatch_id=? AND warehouse_goods_id=? LIMIT 1",BigDecimal.class,dispatch,good1));
        db.update("UPDATE warehouse_goods SET reference_purchase_price=999 WHERE id=?",good1);
        assertDecimal("10",db.queryForObject("SELECT unit_cost_snapshot FROM warehouse_goods_stock_documents WHERE fulfillment_dispatch_id=? AND warehouse_goods_id=? LIMIT 1",BigDecimal.class,dispatch,good1));
        assertEquals("SHIPPED",db.queryForObject("SELECT local_status FROM fulfillment_documents WHERE id=?",String.class,document));
        db.update("UPDATE fulfillment_document_items SET source_line_key=? WHERE id=?",hash("order-1|||PLAT-SKU"),item);
        db.update("INSERT INTO fulfillment_import_batches(batch_no,document_type,original_filename,stored_file_path,file_sha256,total_rows,created_by) VALUES ('REIMPORT-1','JIT_ORDER','changed.xlsx','isolated-only','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1,1)");
        long batch=db.queryForObject("SELECT id FROM fulfillment_import_batches WHERE batch_no='REIMPORT-1'",Long.class);
        String changed="{\"document_type\":\"JIT_ORDER\",\"platform\":\"ALIEXPRESS\",\"shop_identifier\":\"test-shop\",\"source_document_id\":\"order-1\",\"platform_sku\":\"PLAT-SKU\",\"quantity\":\"2\",\"source_status\":\"changed\"}";
        db.update("INSERT INTO fulfillment_import_rows(batch_id,source_row_number,raw_data,normalized_data,row_status) VALUES (?,1,CAST(? AS JSON),CAST(? AS JSON),'READY')",batch,changed,changed);
        fulfillment.commit(batch,user,request);
        assertDecimal("3",db.queryForObject("SELECT ordered_qty FROM fulfillment_document_items WHERE id=?",BigDecimal.class,item));
        assertEquals("HANDED_OFF",db.queryForObject("SELECT status FROM fulfillment_dispatches WHERE id=?",String.class,dispatch));
        assertEquals("NEEDS_REVIEW",db.queryForObject("SELECT local_status FROM fulfillment_documents WHERE id=?",String.class,document));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_stock_documents WHERE fulfillment_dispatch_id=?",Integer.class,dispatch));
        db.update("INSERT INTO fulfillment_documents(document_no,document_type,shop_identifier,source_document_id) VALUES ('FD-SHORT','PLATFORM_WAREHOUSE_STOCKING','test-shop','stocking-1')");
        long stocking=db.queryForObject("SELECT id FROM fulfillment_documents WHERE document_no='FD-SHORT'",Long.class);
        db.update("INSERT INTO fulfillment_document_items(document_id,source_line_key,platform_sku,sku_id,ordered_qty,order_date,order_mode) VALUES (?,'stock-key','PLAT-SKU',1,1,CURRENT_DATE,'WAREHOUSE')",stocking);
        long stockingItem=db.queryForObject("SELECT id FROM fulfillment_document_items WHERE document_id=?",Long.class,stocking);
        long shortDispatch=((Number)fulfillment.createDispatch(stocking,Map.of("idempotencyKey","short-dispatch","items",List.of(Map.of("itemId",stockingItem,"quantity",1))),user,request).data().get("id")).longValue();
        for(Map<String,Object> task:(List<Map<String,Object>>)fulfillment.dispatchDetail(shortDispatch,user).data().get("goodsTasks"))fulfillment.pick(shortDispatch,Map.of("taskId",task.get("taskId"),"locationId",bin1,"quantity",task.get("plannedQty")),user,request);
        Map<String,Object> shortUsage=Map.of("dispatchItemId",((List<Map<String,Object>>)fulfillment.dispatchDetail(shortDispatch,user).data().get("items")).get(0).get("dispatchItemId"),"materialId",material,"locationId",bin1,"quantity",25,"reason","实际加固包装");
        Map<String,Object> shortResult=fulfillment.handoff(shortDispatch,Map.of("packageUsages",List.of(shortUsage)),user,request).data();
        assertFalse(((List<?>)shortResult.get("warnings")).isEmpty());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM exceptions WHERE exception_type='FULFILLMENT_STOCK_SHORTAGE' AND status='OPEN'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM exceptions WHERE exception_type='FULFILLMENT_PACKAGE_SHORTAGE' AND status='OPEN'",Integer.class));
        assertDecimal("-2",db.queryForObject("SELECT quantity FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good1));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM package_location_flows WHERE document_id IN (SELECT id FROM package_location_documents WHERE fulfillment_dispatch_id=? AND material_id<>?)",Integer.class,shortDispatch,material));
        long recoveryPo=((Number)procurement.create(Map.of("idempotencyKey","recover-po","supplierId",1,"warehouseId",1,"items",List.of(Map.of("goodsId",good1,"quantity",2,"suggestedPrice",30))),user).data().get("id")).longValue();
        long recoveryItem=((Number)((List<Map<String,Object>>)procurement.purchase(recoveryPo,user).data().get("items")).get(0).get("id")).longValue();
        procurement.confirm(recoveryPo,Map.of("idempotencyKey","recover-confirm","items",List.of(Map.of("itemId",recoveryItem,"confirmedQty",2))),user);
        procurement.receive(recoveryPo,Map.of("idempotencyKey","recover-receipt","itemId",recoveryItem,"warehouseId",1,"locationId",pending,"quantity",2,"actualUnitPrice",30),user);
        assertDecimal("0",db.queryForObject("SELECT quantity FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good1));
        assertDecimal("0",db.queryForObject("SELECT total_cost FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good1));
        assertDecimal("40",db.queryForObject("SELECT variance_amount FROM warehouse_goods_negative_cost_variances WHERE warehouse_goods_id=?",BigDecimal.class,good1));
        assertDecimal("10",db.queryForObject("SELECT unit_cost_snapshot FROM warehouse_goods_stock_documents WHERE fulfillment_dispatch_id=? AND warehouse_goods_id=? LIMIT 1",BigDecimal.class,shortDispatch,good1));
    }
    private long node(String code){return db.queryForObject("SELECT id FROM warehouse_location_nodes WHERE warehouse_id=1 AND node_code=?",Long.class,code);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private long bin(long level,String code,String type){db.update("INSERT INTO warehouse_locations(warehouse_id,level_id,location_code,slot_code,scan_code,location_type) VALUES (1,?,?,?,?,?)",level,code,code,code,type);return db.queryForObject("SELECT id FROM warehouse_locations WHERE location_code=?",Long.class,code);}
    private static void assertDecimal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
}
