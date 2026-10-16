package com.lue.erp.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import com.lue.erp.common.api.ApiExceptionHandler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in real MySQL acceptance checks. Never uses application.yml or an existing business DB. */
@EnabledIfEnvironmentVariable(named="LUE_COMPOSITION_TEST_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseGoodsAcceptanceTest {
    private JdbcTemplate db;
    private WarehouseGoodsController controller;
    private AuthUser admin, denied;
    private AnnotationConfigApplicationContext context;
    private Map<String,List<Map<String,Object>>> legacySnapshot;
    private MockMvc mvc;
    private final MockHttpServletRequest request=new MockHttpServletRequest();
    private int sequence;

    @Configuration @EnableTransactionManagement
    static class TestConfig {
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean DataSourceTransactionManager transactions(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean AuthService auth(JdbcTemplate jdbc){return new AuthService(jdbc,new BCryptPasswordEncoder());}
        @Bean WarehouseGoodsController controller(JdbcTemplate jdbc,AuthService auth){return new WarehouseGoodsController(jdbc,auth,new ObjectMapper());}
    }

    @BeforeAll void initialize() {
        String url=System.getenv("LUE_COMPOSITION_TEST_URL");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:33479/lue_composition_acceptance?"),"Only the dedicated loopback test instance is permitted");
        String schema="lue_composition_acceptance_"+Long.toUnsignedString(System.nanoTime());
        DriverManagerDataSource initial=new DriverManagerDataSource(url,"root","");
        new JdbcTemplate(initial).execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        DataSource ds=new DriverManagerDataSource(url.replace("/lue_composition_acceptance?","/"+schema+"?"),"root","");
        db=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("11").cleanDisabled(true).load().migrate();
        db.update("INSERT INTO roles(id,code,name) VALUES (1,'ADMIN','Administrator')");
        db.update("INSERT INTO users(id,username,password_hash,display_name) VALUES (1,'acceptance','unused','Acceptance'),(2,'denied','unused','Denied')");
        db.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)");
        for(String p:List.of("master.skus.view","master.mappings.view","master.data.manage"))db.update("INSERT INTO permissions(code,name,type) VALUES (?,?,'API')",p,p);
        db.update("INSERT INTO role_permissions(role_id,permission_id) SELECT 1,id FROM permissions");
        db.update("INSERT INTO skus(id,internal_sku,product_name,purchase_price) VALUES (1,'LEGACY','Legacy SKU',12.3456)");
        db.update("INSERT INTO inventory_stocks(sku_id,warehouse_id,available_qty,locked_qty,pending_inbound_qty,pending_outbound_qty,abnormal_qty) VALUES (1,1,-3.5000,2.0000,7.0000,1.0000,0.5000)");
        db.update("INSERT INTO sku_profit_daily(profit_date,sku_id,order_mode,sales_qty,purchase_cost,gross_profit) VALUES ('2026-10-01',1,'JIT',4,49.3824,9.1234)");
        legacySnapshot=snapshot();
        Flyway flyway=Flyway.configure().dataSource(ds).cleanDisabled(true).load();
        flyway.migrate(); flyway.validate();
        assertEquals(legacySnapshot,snapshot(),"New migrations must preserve every legacy business row");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods",Integer.class),"No guessed SKU to goods mapping");
        context=new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class,()->ds); context.register(TestConfig.class); context.refresh();
        controller=context.getBean(WarehouseGoodsController.class);
        AuthService auth=context.getBean(AuthService.class); admin=auth.loadUser(1);denied=auth.loadUser(2);
        request.setRemoteAddr("127.0.0.1");
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
            public boolean supportsParameter(MethodParameter p){return p.hasParameterAnnotation(AuthenticationPrincipal.class);}
            public Object resolveArgument(MethodParameter p,ModelAndViewContainer m,NativeWebRequest r,WebDataBinderFactory b){return admin;}
        }).build();
    }

    @AfterAll void close() {
        try {if(legacySnapshot!=null)assertEquals(legacySnapshot,snapshot(),"Goods/composition APIs must not write V1 inventory, purchasing, fulfillment or profit");}
        finally {if(context!=null)context.close();}
    }

    @Test void multipleSkusShareGoodsAndOneSkuExpandsSeveralGoods() {
        long cup=goods("Cup"),accessory=goods("Accessory"),replacement=goods("Alternative cup");
        long a=sku(),b=sku();
        long first=version(a,List.of(line(cup,"1",null),line(accessory,"2.5",null),line(replacement,"1.25",0)),"First composition");
        version(b,List.of(line(cup,"2",null)),"Shared goods");
        assertEquals(2,db.queryForObject("SELECT COUNT(DISTINCT version_id) FROM sku_composition_items WHERE warehouse_goods_id=?",Integer.class,cup));
        Map<String,Object> result=controller.preview(a,new BigDecimal("3"),"2026-10-15",first,admin).data();
        List<Map<String,Object>> expanded=items(result,"items");
        assertEquals(2,expanded.size(),"Alternatives are not counted as additional goods demand");
        assertEquals(0,new BigDecimal("3").compareTo((BigDecimal)expanded.get(0).get("requiredQuantity")));
        assertEquals(0,new BigDecimal("7.5").compareTo((BigDecimal)expanded.get(1).get("requiredQuantity")));
        assertEquals(0,new BigDecimal("3.75").compareTo((BigDecimal)items(expanded.get(0),"alternatives").get(0).get("requiredQuantity")));
    }

    @Test void oldVersionContentsSurviveDeactivationAndNewVersion() {
        long sku=sku(),g=goods("History");long v1=version(sku,List.of(line(g,"1",null)),"Original reason");
        List<Map<String,Object>> before=db.queryForList("SELECT * FROM sku_composition_items WHERE version_id=?",v1);
        assertThrows(IllegalArgumentException.class,()->version(sku,List.of(line(g,"2",null)),"Overlapping version"));
        controller.deactivateVersion(sku,v1,Map.of("reason","Revised product"),admin,request);
        long v2=version(sku,List.of(line(g,"2",null)),"Second version");
        assertNotEquals(v1,v2);assertEquals(before,db.queryForList("SELECT * FROM sku_composition_items WHERE version_id=?",v1));
        Map<String,Object> stored=db.queryForMap("SELECT * FROM sku_composition_versions WHERE id=?",v1);
        assertEquals("Original reason",stored.get("change_reason"));assertEquals("Revised product",stored.get("deactivation_reason"));assertNotNull(stored.get("deactivated_at"));
        assertEquals(2,items(controller.versions(sku,admin).data(),"versions").size());
        assertEquals(v1,controller.preview(sku,BigDecimal.ONE,null,v1,admin).data().get("versionId"));
        assertEquals(v2,controller.preview(sku,BigDecimal.ONE,null,null,admin).data().get("versionId"));
        assertThrows(DataIntegrityViolationException.class,()->db.update("DELETE FROM warehouse_goods WHERE id=?",g));
        assertThrows(DataIntegrityViolationException.class,()->db.update("DELETE FROM sku_composition_versions WHERE id=?",v1));
    }

    @Test void invalidRelationshipsAndQuantitiesCannotCreateVersions() {
        long sku=sku(),g=goods("Validated"),other=goods("Other");
        for(List<Map<String,Object>> rows:List.of(List.of(line(g,"0",null)),List.of(line(g,"1",null),line(g,"2",null)),List.of(line(g,"1",null),line(g,"1",0)),List.of(line(g,"1",null),line(other,"1",9))))assertThrows(IllegalArgumentException.class,()->version(sku,rows,"Invalid"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sku_composition_versions WHERE sku_id=?",Integer.class,sku));
        controller.deactivateGoods(g,admin,request);
        assertThrows(IllegalArgumentException.class,()->version(sku,List.of(line(g,"1",null)),"Inactive goods"));
    }

    @Test void goodsAreUniqueAndReferencedUnitsRemainStable() {
        long g=goods("Stable"),sku=sku();
        Map<String,Object> body=goodsBody("Stable");body.put("goodsCode",db.queryForObject("SELECT goods_code FROM warehouse_goods WHERE id=?",String.class,g));
        assertThrows(DataIntegrityViolationException.class,()->controller.createGoods(body,admin,request));
        version(sku,List.of(line(g,"1",null)),"Unit is fixed");body.put("unit","box");
        assertThrows(IllegalArgumentException.class,()->controller.updateGoods(g,body,admin,request));
        body.put("unit","piece");body.put("referencePurchasePrice","999999999999999.0000");
        assertThrows(IllegalArgumentException.class,()->controller.updateGoods(g,body,admin,request));
    }

    @Test void permissionsAndAuditAreEnforcedAndFailuresRollBack() {
        assertThrows(AccessDeniedException.class,()->controller.createGoods(goodsBody("Forbidden"),denied,request));
        assertThrows(AccessDeniedException.class,()->controller.versions(1,denied));
        MockHttpServletRequest invalidAudit=new MockHttpServletRequest();invalidAudit.setRemoteAddr("x".repeat(60));
        int goodsBefore=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods",Integer.class);
        assertThrows(IllegalStateException.class,()->controller.createGoods(goodsBody("Audit rollback"),admin,invalidAudit));
        assertEquals(goodsBefore,db.queryForObject("SELECT COUNT(*) FROM warehouse_goods",Integer.class));
        long sku=sku(),g=goods("Audit component");
        assertThrows(IllegalStateException.class,()->controller.createVersion(sku,Map.of("changeReason","Audit rollback","items",List.of(line(g,"1",null))),admin,invalidAudit));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sku_composition_versions WHERE sku_id=?",Integer.class,sku));
        long v=version(sku,List.of(line(g,"1",null)),"Audit success");controller.deactivateVersion(sku,v,Map.of("reason","Test complete"),admin,request);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM operation_logs WHERE resource_type='sku_composition_version' AND resource_id=?",Integer.class,String.valueOf(v)));
    }

    @Test void concurrentAppendsAllocateDifferentVersionNumbers() throws Exception {
        long sku=sku(),g=goods("Concurrent");ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Map<String,Object>> action=()->controller.createVersion(sku,Map.of("changeReason","Concurrent archive","status","INACTIVE","items",List.of(line(g,"1",null))),admin,request).data();
            List<Future<Map<String,Object>>> results=pool.invokeAll(List.of(action,action));
            Set<Object> numbers=new HashSet<>();for(Future<Map<String,Object>> result:results)numbers.add(result.get(15,TimeUnit.SECONDS).get("versionNo"));
            assertEquals(Set.of(1,2),numbers);
        } finally {pool.shutdownNow();}
    }

    @Test void httpDetailsAreNotLimitedToFirstHundredSkusAndDeletionIsUnavailable() throws Exception {
        for(int i=0;i<105;i++)sku();
        mvc.perform(get("/api/skus/1/product-details")).andExpect(status().isOk()).andExpect(jsonPath("$.data.internalSku").value("LEGACY"));
        long sku=sku(),g=goods("HTTP");long v=version(sku,List.of(line(g,"1.5",null)),"HTTP preview");
        mvc.perform(get("/api/skus/"+sku+"/compositions/preview").param("quantity","2").param("versionId",String.valueOf(v))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].requiredQuantity").value(3));
        mvc.perform(delete("/api/warehouse-goods/"+g)).andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/skus/"+sku+"/compositions/"+v).contentType("application/json").content("{}" )).andExpect(status().isNotFound());
    }

    private long goods(String name){return ((Number)controller.createGoods(goodsBody(name),admin,request).data().get("id")).longValue();}
    private Map<String,Object> goodsBody(String name){return new LinkedHashMap<>(Map.of("goodsCode","ACCEPT-"+(++sequence),"goodsName",name,"unit","piece","referencePurchasePrice","3.1234","status","ACTIVE"));}
    private long sku(){db.update("INSERT INTO skus(internal_sku,product_name) VALUES (?,?)","ACCEPT-SKU-"+(++sequence),"Acceptance SKU");return db.queryForObject("SELECT MAX(id) FROM skus",Long.class);}
    private long version(long sku,List<Map<String,Object>>rows,String reason){return ((Number)controller.createVersion(sku,Map.of("changeReason",reason,"items",rows),admin,request).data().get("id")).longValue();}
    private Map<String,Object> line(long g,String qty,Integer parent){Map<String,Object>row=new LinkedHashMap<>();row.put("warehouseGoodsId",g);row.put("quantity",qty);row.put("substituteForIndex",parent);return row;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> data,String key){return (List<Map<String,Object>>)data.get(key);}
    private Map<String,List<Map<String,Object>>> snapshot(){Map<String,List<Map<String,Object>>>result=new LinkedHashMap<>();for(String table:List.of("inventory_stocks","inventory_flows","package_stocks","package_flows","purchase_orders","purchase_order_items","purchase_receipts","order_summaries","fulfillment_documents","fulfillment_dispatches","sku_profit_daily","settlement_summaries","other_cost_imports"))result.put(table,db.queryForList("SELECT * FROM "+table+" ORDER BY id"));result.put("legacy_sku",db.queryForList("SELECT * FROM skus WHERE id=1"));return result;}
}
