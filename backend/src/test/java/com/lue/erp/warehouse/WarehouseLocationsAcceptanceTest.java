package com.lue.erp.warehouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only on the dedicated local acceptance MySQL; never uses application.yml. */
@EnabledIfEnvironmentVariable(named="LUE_LOCATION_TEST_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseLocationsAcceptanceTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean DataSourceTransactionManager transactions(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean AuthService auth(JdbcTemplate jdbc){return new AuthService(jdbc,new BCryptPasswordEncoder());}
        @Bean WarehouseLocationsController controller(JdbcTemplate jdbc,AuthService auth){return new WarehouseLocationsController(jdbc,auth,new ObjectMapper());}
    }
    private JdbcTemplate db;
    private WarehouseLocationsController locations;
    private AnnotationConfigApplicationContext context;
    private AuthUser admin,denied;
    private final MockHttpServletRequest request=new MockHttpServletRequest();
    private Map<String,List<Map<String,Object>>> legacy;
    private int seq;

    @BeforeAll void initialize(){
        String url=System.getenv("LUE_LOCATION_TEST_URL");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:33481/lue_location_acceptance?"),"Only the dedicated loopback instance is permitted");
        String schema="lue_location_acceptance_"+Long.toUnsignedString(System.nanoTime());
        new JdbcTemplate(new DriverManagerDataSource(url.replace("/lue_location_acceptance?","/mysql?"),"root","")).execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        DataSource ds=new DriverManagerDataSource(url.replace("/lue_location_acceptance?","/"+schema+"?"),"root","");db=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("13").cleanDisabled(true).load().migrate();
        db.update("INSERT INTO users(id,username,password_hash,display_name) VALUES (1,'location-admin','unused','Admin'),(2,'location-denied','unused','Denied')");
        db.update("INSERT INTO roles(id,code,name) VALUES (1,'ADMIN','Admin')");
        db.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)");
        db.update("INSERT INTO skus(id,internal_sku,product_name,purchase_price) VALUES (1,'OLD-LOCATION','Legacy SKU',12.3456)");
        db.update("INSERT INTO inventory_stocks(sku_id,warehouse_id,available_qty,locked_qty,pending_inbound_qty,pending_outbound_qty,abnormal_qty) VALUES (1,1,-3.5000,2,7,1,0.5)");
        db.update("INSERT INTO sku_profit_daily(profit_date,sku_id,order_mode,sales_qty,purchase_cost,gross_profit) VALUES ('2026-10-01',1,'JIT',4,49.3824,9.1234)");
        legacy=snapshot();
        Flyway flyway=Flyway.configure().dataSource(ds).cleanDisabled(true).load();flyway.migrate();flyway.validate();
        assertEquals(legacy,snapshot());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_balances",Integer.class));
        db.update("INSERT IGNORE INTO role_permissions(role_id,permission_id) SELECT 1,id FROM permissions WHERE code IN ('warehouse.locations.view','button.locations.manage','warehouse.location_stock.view')");
        context=new AnnotationConfigApplicationContext();context.registerBean(DataSource.class,()->ds);context.register(Config.class);context.refresh();
        locations=context.getBean(WarehouseLocationsController.class);AuthService auth=context.getBean(AuthService.class);admin=auth.loadUser(1);denied=auth.loadUser(2);request.setRemoteAddr("127.0.0.1");
    }

    @AfterAll void close(){try{if(legacy!=null)assertEquals(legacy,snapshot(),"Stage 2 must preserve V1 stock, flows and profit");}finally{if(context!=null)context.close();}}

    @Test void hierarchyUniqueCodesAndMultipleGoodsLocations(){
        long area=node("AREA",null,"A"),shelf=node("SHELF",area,"S"),level=node("LEVEL",shelf,"L");
        long pick=location(level,"A-S-L-01","PRIMARY_PICK"),storage=location(level,"A-S-L-02","STORAGE");
        assertThrows(DataIntegrityViolationException.class,()->location(level,"A-S-L-01","STORAGE"));
        long good1=good(),good2=good();
        locations.assign(Map.of("warehouseId",1,"warehouseGoodsId",good1,"locationId",pick,"isPrimaryPick",true),admin,request);
        locations.assign(Map.of("warehouseId",1,"warehouseGoodsId",good1,"locationId",storage,"isPrimaryPick",false),admin,request);
        locations.assign(Map.of("warehouseId",1,"warehouseGoodsId",good2,"locationId",storage,"isPrimaryPick",false),admin,request);
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_assignments WHERE status='ACTIVE'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_assignments WHERE location_id=? AND status='ACTIVE'",Integer.class,storage));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods_location_balances",Integer.class));
        assertEquals(2,locations.locations(1L,good1,null,null,admin).data().get("locations") instanceof List<?> list?list.size():-1);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM operation_logs WHERE action='location.create' AND resource_id=?",Integer.class,String.valueOf(pick)));
    }

    @Test void deactivationRejectsStockOpenTaskAndBindings(){
        long level=node("LEVEL",node("SHELF",node("AREA",null,"B"),"S"),"L");
        long stockBin=location(level,"B-STOCK","STORAGE"),taskBin=location(level,"B-TASK","TEMPORARY"),assigned=location(level,"B-ASSIGNED","STORAGE");long good=good();
        db.update("INSERT INTO warehouse_goods_location_balances(warehouse_id,warehouse_goods_id,location_id,position_status,available_qty) VALUES (1,?,?,'LOCATED',-2)",good,stockBin);
        assertThrows(IllegalArgumentException.class,()->locations.deactivate(stockBin,admin,request));
        db.update("INSERT INTO warehouse_location_task_claims(warehouse_id,location_id,task_type,source_type,source_id) VALUES (1,?,'MOVE','test',1)",taskBin);
        assertThrows(IllegalArgumentException.class,()->locations.deactivate(taskBin,admin,request));
        locations.assign(Map.of("warehouseId",1,"warehouseGoodsId",good,"locationId",assigned,"isPrimaryPick",false),admin,request);
        assertThrows(IllegalArgumentException.class,()->locations.deactivate(assigned,admin,request));
        db.update("UPDATE warehouse_location_task_claims SET status='DONE' WHERE location_id=?",taskBin);
        locations.deactivate(taskBin,admin,request);
        assertEquals("INACTIVE",db.queryForObject("SELECT status FROM warehouse_locations WHERE id=?",String.class,taskBin));
    }

    @Test void summaryIsExactRollupAndUnknownPositionIsExplicit(){
        long level=node("LEVEL",node("SHELF",node("AREA",null,"C"),"S"),"L");long bin=location(level,"C-01","STORAGE"),good=good();
        db.update("INSERT INTO warehouse_goods_location_balances(warehouse_id,warehouse_goods_id,location_id,position_status,available_qty,locked_qty) VALUES (1,?,?,'LOCATED',4,2)",good,bin);
        db.update("INSERT INTO warehouse_goods_location_balances(warehouse_id,warehouse_goods_id,position_status,available_qty) VALUES (1,?,'HISTORICAL_UNLOCATED',3)",good);
        @SuppressWarnings("unchecked") List<Map<String,Object>> summary=(List<Map<String,Object>>)locations.stockSummary(1L,good,null,admin).data().get("items");
        assertEquals(0,new java.math.BigDecimal("7").compareTo((java.math.BigDecimal)summary.get(0).get("availableQty")));
        @SuppressWarnings("unchecked") List<Map<String,Object>> details=(List<Map<String,Object>>)locations.stockDetails(1L,good,null,admin).data().get("items");
        assertEquals(2,details.size());assertTrue(details.stream().anyMatch(x->"历史未定位".equals(x.get("locationCode"))&&x.get("locationId")==null));
        assertThrows(DataAccessException.class,()->db.update("INSERT INTO warehouse_goods_location_balances(warehouse_id,warehouse_goods_id,position_status) VALUES (1,?,'LOCATED')",good));
        assertThrows(AccessDeniedException.class,()->locations.createNode(Map.of("warehouseId",1,"nodeKind","AREA","nodeCode","DENIED","nodeName","Denied"),denied,request));
    }

    private long node(String kind,Long parent,String code){Map<String,Object>body=new HashMap<>(Map.of("warehouseId",1,"nodeKind",kind,"nodeCode",code+ ++seq,"nodeName",code));if(parent!=null)body.put("parentId",parent);return ((Number)locations.createNode(body,admin,request).data().get("id")).longValue();}
    private long location(long level,String code,String type){return ((Number)locations.createLocation(Map.of("warehouseId",1,"levelId",level,"locationCode",code,"slotCode",code,"locationType",type),admin,request).data().get("id")).longValue();}
    private long good(){String code="LOC-GOOD-"+ ++seq;db.update("INSERT INTO warehouse_goods(goods_code,goods_name,unit,status) VALUES (?,?, 'piece','ACTIVE')",code,"Location good");return db.queryForObject("SELECT id FROM warehouse_goods WHERE goods_code=?",Long.class,code);}
    private Map<String,List<Map<String,Object>>> snapshot(){Map<String,List<Map<String,Object>>>x=new LinkedHashMap<>();for(String table:List.of("inventory_stocks","inventory_flows","package_stocks","package_flows","purchase_orders","purchase_order_items","purchase_receipts","order_summaries","fulfillment_documents","fulfillment_dispatches","sku_profit_daily"))x.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY id"));return x;}
}
