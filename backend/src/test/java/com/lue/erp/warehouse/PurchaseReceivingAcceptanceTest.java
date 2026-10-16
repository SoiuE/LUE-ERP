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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LUE_RECEIVING_TEST_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseReceivingAcceptanceTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean JdbcTemplate db(DataSource ds){return new JdbcTemplate(ds);}
        @Bean DataSourceTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean AuthService auth(JdbcTemplate db){return new AuthService(db,new BCryptPasswordEncoder());}
        @Bean GoodsProcurementController controller(JdbcTemplate db,AuthService auth){return new GoodsProcurementController(db,auth,new ObjectMapper());}
    }
    private JdbcTemplate db;
    private GoodsProcurementController service;
    private WarehouseLocationsController locations;
    private AnnotationConfigApplicationContext context;
    private AuthUser user,purchaser,receiver;
    private long level,pending;

    @BeforeAll void init(){
        String url=System.getenv("LUE_RECEIVING_TEST_URL");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:33483/lue_receiving_acceptance?"));
        String schema="lue_receiving_acceptance_"+Long.toUnsignedString(System.nanoTime());
        new JdbcTemplate(new DriverManagerDataSource(url.replace("/lue_receiving_acceptance?","/mysql?"),"root","")).execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        DataSource ds=new DriverManagerDataSource(url.replace("/lue_receiving_acceptance?","/"+schema+"?"),"root","");
        Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();
        db=new JdbcTemplate(ds);
        db.update("INSERT INTO users(id,username,password_hash,display_name) VALUES (1,'receiving-admin','unused','收货管理员'),(2,'purchase-only','unused','仅采购'),(3,'receiving-only','unused','仅收货')");
        db.update("INSERT INTO roles(id,code,name) VALUES (1,'ADMIN','管理员'),(2,'PURCHASER','采购'),(3,'RECEIVER','收货')");
        db.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1),(2,2),(3,3)");
        db.update("INSERT INTO role_permissions(role_id,permission_id) SELECT 1,id FROM permissions");
        db.update("INSERT INTO role_permissions(role_id,permission_id) SELECT 2,id FROM permissions WHERE code='warehouse.goods.purchase'");
        db.update("INSERT INTO role_permissions(role_id,permission_id) SELECT 3,id FROM permissions WHERE code IN ('menu.purchases','warehouse.goods.receive','warehouse.locations.view')");
        db.update("INSERT INTO suppliers(id,supplier_code,supplier_name) VALUES (1,'RECEIVE-SUPPLIER','收货测试供应商')");
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,node_kind,node_code,node_name) VALUES (1,'AREA','RX','区')");long area=node("AREA");
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (1,?,'SHELF','RX','架')",area);long shelf=node("SHELF");
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (1,?,'LEVEL','RX','层')",shelf);level=node("LEVEL");
        pending=location("PENDING_PUTAWAY");
        context=new AnnotationConfigApplicationContext();context.registerBean(DataSource.class,()->ds);context.register(Config.class);context.refresh();
        service=context.getBean(GoodsProcurementController.class);AuthService auth=context.getBean(AuthService.class);
        user=auth.loadUser(1);purchaser=auth.loadUser(2);receiver=auth.loadUser(3);locations=new WarehouseLocationsController(db,auth,new ObjectMapper());
    }
    @AfterAll void close(){if(context!=null)context.close();}

    @Test void bulkReceivingSynchronizesWarehouseBindingsAndIsIdempotent(){
        long a=good(),b=good(),bound=location("PRIMARY_PICK"),empty=location("STORAGE");
        db.update("INSERT INTO warehouse_goods_location_assignments(warehouse_id,warehouse_goods_id,location_id,is_primary_pick,created_by) VALUES (1,?,?,TRUE,1)",a,bound);
        Order order=order(a,b);
        List<Map<String,Object>> options=items(service.receiptLocations(1,user).data(),"items");
        assertTrue(options.stream().anyMatch(x->number(x,"id")==pending));
        assertTrue(options.stream().anyMatch(x->number(x,"id")==bound&&number(x,"assignedGoodsId")==a));
        assertTrue(options.stream().anyMatch(x->number(x,"id")==empty));
        Map<String,Object> request=request(order,true,line(order,a,bound,2),line(order,b,empty,2));
        Map<String,Object> result=service.receive(order.id,request,user).data();
        service.receive(order.id,request,user);
        assertEquals("RECEIVED",result.get("status"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM purchase_receipts WHERE purchase_order_id=?",Integer.class,order.id));
        assertEquals(1,db.queryForObject("SELECT is_primary_pick FROM warehouse_goods_location_assignments WHERE warehouse_goods_id=? AND location_id=?",Integer.class,a,bound));
        List<Map<String,Object>> bindings=items(locations.assignments(1L,b,user).data(),"assignments");
        assertEquals(1,bindings.size());assertEquals(empty,number(bindings.get(0),"locationId"));
        assertEquals(0,db.queryForObject("SELECT is_primary_pick FROM warehouse_goods_location_assignments WHERE warehouse_goods_id=? AND location_id=?",Integer.class,b,empty));
        decimal("2",db.queryForObject("SELECT available_qty FROM warehouse_goods_location_balances WHERE warehouse_goods_id=? AND location_id=?",BigDecimal.class,b,empty));
        assertEquals("收货备注",db.queryForObject("SELECT receipt_note FROM purchase_receipts WHERE purchase_order_id=? LIMIT 1",String.class,order.id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM operation_logs WHERE action='purchase.receipt.location.bind' AND JSON_EXTRACT(detail,'$.purchaseOrderId')=?",Integer.class,order.id));
    }

    @Test void failureRollsBackEarlierReceiptBindingCostAndCommand(){
        long a=good(),b=good(),empty=location("STORAGE"),blocked=location("STORAGE");
        db.update("INSERT INTO warehouse_goods_location_assignments(warehouse_id,warehouse_goods_id,location_id) VALUES (1,?,?)",good(),blocked);
        Order order=order(a,b);
        Map<String,Object> request=request(order,true,line(order,a,empty,2),line(order,b,blocked,2));
        assertThrows(IllegalArgumentException.class,()->service.receive(order.id,request,user));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM purchase_receipts WHERE purchase_order_id=?",Integer.class,order.id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_assignments WHERE warehouse_goods_id=?",Integer.class,a));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_costs WHERE warehouse_goods_id=?",Integer.class,a));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM goods_purchase_commands WHERE command_key=? AND command_type='RECEIVE'",Integer.class,request.get("idempotencyKey")));
        assertEquals("CONFIRMED",db.queryForObject("SELECT status FROM purchase_orders WHERE id=?",String.class,order.id));
    }

    @Test void pendingReceiptDoesNotBindAndPartialThenAllUsesRemaining(){
        long a=good(),b=good();Order order=order(a,b);
        service.receive(order.id,request(order,false,line(order,a,pending,1)),user);
        assertEquals("PARTIALLY_RECEIVED",db.queryForObject("SELECT status FROM purchase_orders WHERE id=?",String.class,order.id));
        assertThrows(IllegalArgumentException.class,()->service.receive(order.id,request(order,true,line(order,a,pending,2),line(order,b,pending,2)),user));
        service.receive(order.id,request(order,true,line(order,a,pending,1),line(order,b,pending,2)),user);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_assignments WHERE warehouse_goods_id IN (?,?)",Integer.class,a,b));
        decimal("2",db.queryForObject("SELECT pending_putaway_qty FROM warehouse_goods_location_balances WHERE warehouse_goods_id=? AND location_id=?",BigDecimal.class,a,pending));
        decimal("0",db.queryForObject("SELECT available_qty FROM warehouse_goods_location_balances WHERE warehouse_goods_id=? AND location_id=?",BigDecimal.class,a,pending));
    }

    @Test void purchasingPermissionDoesNotGrantReceivingAndEmptyBinTasksBlockBinding(){
        long a=good(),empty=location("STORAGE");Order order=order(a);
        Map<String,Object> request=request(order,true,line(order,a,empty,2));
        assertThrows(AccessDeniedException.class,()->service.receive(order.id,request,purchaser));
        db.update("INSERT INTO warehouse_location_task_claims(warehouse_id,location_id,task_type,source_type,source_id) VALUES (1,?,'TEST','test',1)",empty);
        assertThrows(IllegalArgumentException.class,()->service.receive(order.id,request,user));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_assignments WHERE location_id=?",Integer.class,empty));
    }

    @Test void receivingOnlyRoleCanReceiveAndSynchronizeBinding(){
        long a=good(),empty=location("STORAGE");Order order=order(a);
        assertFalse(receiver.permissions().contains("warehouse.goods.purchase"));
        assertFalse(receiver.permissions().contains("button.locations.manage"));
        service.receive(order.id,request(order,true,line(order,a,empty,2)),receiver);
        assertEquals(1,items(locations.assignments(1L,a,receiver).data(),"assignments").size());
        assertEquals(3L,db.queryForObject("SELECT created_by FROM warehouse_goods_location_assignments WHERE warehouse_goods_id=? AND location_id=?",Long.class,a,empty));
    }

    private long good(){String code="G-"+UUID.randomUUID();db.update("INSERT INTO warehouse_goods(goods_code,goods_name,unit) VALUES (?,'收货货品','件')",code);return Objects.requireNonNull(db.queryForObject("SELECT id FROM warehouse_goods WHERE goods_code=?",Long.class,code));}
    private long location(String type){String code="B-"+UUID.randomUUID();db.update("INSERT INTO warehouse_locations(warehouse_id,level_id,location_code,slot_code,location_type) VALUES (1,?,?,?,?)",level,code,code,type);return Objects.requireNonNull(db.queryForObject("SELECT id FROM warehouse_locations WHERE warehouse_id=1 AND location_code=?",Long.class,code));}
    private long node(String kind){return Objects.requireNonNull(db.queryForObject("SELECT id FROM warehouse_location_nodes WHERE warehouse_id=1 AND node_kind=? AND node_code='RX'",Long.class,kind));}
    private record Order(long id,Map<Long,Long> lines){}
    private Order order(long... goods){List<Map<String,Object>> lines=new ArrayList<>();for(long good:goods)lines.add(Map.of("goodsId",good,"quantity",2,"suggestedPrice",3));long id=number(service.create(Map.of("idempotencyKey",UUID.randomUUID().toString(),"supplierId",1,"warehouseId",1,"items",lines),user).data(),"id");Map<Long,Long> mapping=new HashMap<>();List<Map<String,Object>> confirmed=new ArrayList<>();for(Map<String,Object> row:items(service.purchase(id,user).data(),"items")){mapping.put(number(row,"goodsId"),number(row,"id"));confirmed.add(Map.of("itemId",number(row,"id"),"confirmedQty",2));}service.confirm(id,Map.of("idempotencyKey",UUID.randomUUID().toString(),"items",confirmed),user);return new Order(id,mapping);}
    private Map<String,Object> line(Order order,long good,long bin,int qty){return Map.of("itemId",order.lines.get(good),"locationId",bin,"quantity",qty,"actualUnitPrice",3,"note","收货备注");}
    @SafeVarargs private final Map<String,Object> request(Order order,boolean all,Map<String,Object>... lines){return Map.of("idempotencyKey",UUID.randomUUID().toString(),"warehouseId",1,"receiveAll",all,"items",List.of(lines));}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> row,String key){return (List<Map<String,Object>>)row.get(key);}
    private static void decimal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
}
