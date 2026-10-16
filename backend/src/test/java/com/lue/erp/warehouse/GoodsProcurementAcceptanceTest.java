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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LUE_GOODS_TEST_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoodsProcurementAcceptanceTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean JdbcTemplate db(DataSource ds){return new JdbcTemplate(ds);}
        @Bean DataSourceTransactionManager transactions(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean AuthService auth(JdbcTemplate db){return new AuthService(db,new BCryptPasswordEncoder());}
        @Bean GoodsProcurementController goods(JdbcTemplate db,AuthService auth){return new GoodsProcurementController(db,auth,new ObjectMapper());}
    }
    private JdbcTemplate db;
    private GoodsProcurementController service;
    private AnnotationConfigApplicationContext context;
    private AuthUser user;
    private long good,pending,storage1,storage2;

    @BeforeAll void init(){
        String url=System.getenv("LUE_GOODS_TEST_URL");assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:33483/lue_goods_acceptance?"));
        String schema="lue_goods_acceptance_"+Long.toUnsignedString(System.nanoTime());
        new JdbcTemplate(new DriverManagerDataSource(url.replace("/lue_goods_acceptance?","/mysql?"),"root","")).execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        DataSource ds=new DriverManagerDataSource(url.replace("/lue_goods_acceptance?","/"+schema+"?"),"root","");db=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("15").cleanDisabled(true).load().migrate();
        db.update("INSERT INTO users(id,username,password_hash,display_name) VALUES (1,'goods-admin','unused','Goods Admin')");
        db.update("INSERT INTO roles(id,code,name) VALUES (1,'ADMIN','Admin')");
        db.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)");
        db.update("INSERT INTO skus(id,internal_sku,product_name,purchase_price) VALUES (1,'OLD-GOODS','Old',12.3456)");
        db.update("INSERT INTO inventory_stocks(sku_id,warehouse_id,available_qty) VALUES (1,1,7.0000)");
        List<Map<String,Object>> legacy=db.queryForList("SELECT * FROM inventory_stocks");
        Flyway flyway=Flyway.configure().dataSource(ds).cleanDisabled(true).load();flyway.migrate();flyway.validate();
        assertEquals(legacy,db.queryForList("SELECT * FROM inventory_stocks"));
        db.update("INSERT IGNORE INTO role_permissions(role_id,permission_id) SELECT 1,id FROM permissions WHERE code IN ('menu.purchases','warehouse.location_stock.view','warehouse.goods.purchase','warehouse.goods.putaway','warehouse.goods.move','warehouse.goods.outbound','menu.fulfillment','button.fulfillment.operate')");
        db.update("INSERT INTO suppliers(id,supplier_code,supplier_name) VALUES (1,'GS','Supplier')");
        db.update("INSERT INTO warehouse_goods(goods_code,goods_name,unit,reference_purchase_price) VALUES ('GOOD-1','Goods','pcs',99.0000)");good=Objects.requireNonNull(db.queryForObject("SELECT id FROM warehouse_goods WHERE goods_code='GOOD-1'",Long.class));
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,node_kind,node_code,node_name) VALUES (1,'AREA','A','Area')");long area=node("A");
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (1,?,'SHELF','S','Shelf')",area);long shelf=node("S");
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (1,?,'LEVEL','L','Level')",shelf);long level=node("L");
        pending=location(level,"PENDING","PENDING_PUTAWAY");storage1=location(level,"STORAGE-1","STORAGE");storage2=location(level,"STORAGE-2","STORAGE");
        context=new AnnotationConfigApplicationContext();context.registerBean(DataSource.class,()->ds);context.register(Config.class);context.refresh();
        service=context.getBean(GoodsProcurementController.class);user=context.getBean(AuthService.class).loadUser(1);
    }

    @AfterAll void close(){if(context!=null)context.close();}

    @Test void splitReceiptsPutawayMoveCostAndIdempotency(){
        db.update("INSERT INTO master_code_sequences(sequence_key,sequence_date,sequence_value) VALUES ('PURCHASE_ORDER',?,3)",LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
        Map<String,Object> created=service.create(Map.of("idempotencyKey","create-1","supplierId",1,"warehouseId",1,"items",List.of(Map.of("goodsId",good,"quantity",10,"suggestedPrice",99))),user).data();
        assertEquals("PO"+LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE)+"000001",created.get("poNo"));
        long order=((Number)created.get("id")).longValue();Map<String,Object> retry=service.create(Map.of("idempotencyKey","create-1","supplierId",1,"warehouseId",1,"items",List.of(Map.of("goodsId",good,"quantity",10,"suggestedPrice",99))),user).data();assertEquals(order,((Number)retry.get("id")).longValue());assertEquals(created.get("poNo"),retry.get("poNo"));
        long item=((Number)((List<Map<String,Object>>)service.purchase(order,user).data().get("items")).get(0).get("id")).longValue();
        service.confirm(order,Map.of("idempotencyKey","confirm-1","items",List.of(Map.of("itemId",item,"confirmedQty",10))),user);
        db.update("INSERT INTO sku_composition_versions(sku_id,version_no,change_reason) VALUES (1,1,'acceptance')");
        long version=Objects.requireNonNull(db.queryForObject("SELECT id FROM sku_composition_versions WHERE sku_id=1 AND version_no=1",Long.class));
        db.update("INSERT INTO sku_composition_items(version_id,warehouse_goods_id,quantity) VALUES (?,?,2)",version,good);
        db.update("INSERT INTO fulfillment_documents(document_no,document_type,shop_identifier,source_document_id) VALUES ('FD-GOODS','JIT_ORDER','test-shop','test-order')");
        long document=Objects.requireNonNull(db.queryForObject("SELECT id FROM fulfillment_documents WHERE document_no='FD-GOODS'",Long.class));
        db.update("INSERT INTO fulfillment_document_items(document_id,source_line_key,platform_sku,sku_id,ordered_qty) VALUES (?,'abc','OLD-GOODS',1,3)",document);
        long documentItem=Objects.requireNonNull(db.queryForObject("SELECT id FROM fulfillment_document_items WHERE document_id=?",Long.class,document));
        db.update("INSERT INTO fulfillment_shortages(document_item_id,shortage_qty,reason,created_by) VALUES (?,3,'test shortage',1)",documentItem);
        long shortage=Objects.requireNonNull(db.queryForObject("SELECT id FROM fulfillment_shortages WHERE document_item_id=?",Long.class,documentItem));
        FulfillmentController fulfillment=new FulfillmentController(db,context.getBean(AuthService.class),new ObjectMapper());
        assertTrue(((List<Map<String,Object>>)fulfillment.purchaseOptions(user).data().get("items")).stream().anyMatch(x->((Number)x.get("purchaseOrderItemId")).longValue()==item));
        fulfillment.linkPurchase(shortage,Map.of("purchaseOrderItemId",item),user,new MockHttpServletRequest());
        assertDecimal("6",db.queryForObject("SELECT required_goods_qty FROM fulfillment_shortages WHERE id=?",BigDecimal.class,shortage));
        Map<String,Object> first=Map.of("idempotencyKey","receipt-1","itemId",item,"warehouseId",1,"locationId",pending,"quantity",4,"actualUnitPrice","10.0000");
        service.receive(order,first,user);service.receive(order,first,user);
        assertEquals("PURCHASE_LINKED",db.queryForObject("SELECT status FROM fulfillment_shortages WHERE id=?",String.class,shortage));
        assertThrows(IllegalArgumentException.class,()->service.receive(order,Map.of("idempotencyKey","receipt-1","itemId",item,"warehouseId",1,"locationId",pending,"quantity",5,"actualUnitPrice","10.0000"),user));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM purchase_receipts WHERE warehouse_goods_id=?",Integer.class,good));
        service.receive(order,Map.of("idempotencyKey","receipt-2","itemId",item,"warehouseId",1,"locationId",pending,"quantity",6,"actualUnitPrice","20.0000"),user);
        assertEquals("RECEIVED",db.queryForObject("SELECT status FROM fulfillment_shortages WHERE id=?",String.class,shortage));
        assertDecimal("10",db.queryForObject("SELECT quantity FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good));
        assertDecimal("160",db.queryForObject("SELECT total_cost FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good));
        assertDecimal("0",db.queryForObject("SELECT available_qty FROM warehouse_goods_location_balances WHERE location_id=?",BigDecimal.class,pending));
        assertDecimal("10",db.queryForObject("SELECT pending_putaway_qty FROM warehouse_goods_location_balances WHERE location_id=?",BigDecimal.class,pending));
        Map<String,Object> put1=Map.of("idempotencyKey","put-1","warehouseId",1,"goodsId",good,"sourceLocationId",pending,"targetLocationId",storage1,"quantity",4);
        service.putaway(put1,user);service.putaway(put1,user);
        service.putaway(Map.of("idempotencyKey","put-2","warehouseId",1,"goodsId",good,"sourceLocationId",pending,"targetLocationId",storage2,"quantity",6),user);
        Map<String,Object> move=Map.of("idempotencyKey","move-1","warehouseId",1,"goodsId",good,"sourceLocationId",storage2,"targetLocationId",storage1,"quantity",2);
        service.move(move,user);service.move(move,user);
        assertDecimal("6",db.queryForObject("SELECT available_qty FROM warehouse_goods_location_balances WHERE location_id=?",BigDecimal.class,storage1));
        assertDecimal("4",db.queryForObject("SELECT available_qty FROM warehouse_goods_location_balances WHERE location_id=?",BigDecimal.class,storage2));
        assertDecimal("160",db.queryForObject("SELECT total_cost FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good));
        db.update("UPDATE warehouse_goods SET reference_purchase_price=999 WHERE id=?",good);
        assertDecimal("10",db.queryForObject("SELECT actual_unit_price FROM warehouse_goods_cost_layers ORDER BY id LIMIT 1",BigDecimal.class));
        assertDecimal("16",db.queryForObject("SELECT average_unit_cost FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good));
        Map<String,Object> outbound=Map.of("idempotencyKey","out-1","warehouseId",1,"goodsId",good,"sourceLocationId",storage1,"quantity",2,"reason","manual dispatch");
        service.outbound(outbound,user);service.outbound(outbound,user);
        assertDecimal("16",db.queryForObject("SELECT unit_cost_snapshot FROM warehouse_goods_stock_flows WHERE flow_type='OUTBOUND'",BigDecimal.class));
        assertDecimal("128",db.queryForObject("SELECT total_cost FROM warehouse_goods_costs WHERE warehouse_goods_id=?",BigDecimal.class,good));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_stock_flows WHERE flow_type='OUTBOUND'",Integer.class));
        assertThrows(IllegalArgumentException.class,()->service.move(Map.of("idempotencyKey","move-too-much","warehouseId",1,"goodsId",good,"sourceLocationId",storage2,"targetLocationId",storage1,"quantity",100),user));
        assertDecimal("7",db.queryForObject("SELECT available_qty FROM inventory_stocks WHERE sku_id=1",BigDecimal.class));
    }

    private long location(long level,String code,String type){db.update("INSERT INTO warehouse_locations(warehouse_id,level_id,location_code,slot_code,location_type) VALUES (1,?,?,?,?)",level,code,code,type);return Objects.requireNonNull(db.queryForObject("SELECT id FROM warehouse_locations WHERE warehouse_id=1 AND location_code=?",Long.class,code));}
    private long node(String code){return Objects.requireNonNull(db.queryForObject("SELECT id FROM warehouse_location_nodes WHERE warehouse_id=1 AND node_code=?",Long.class,code));}
    private void assertDecimal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
}
