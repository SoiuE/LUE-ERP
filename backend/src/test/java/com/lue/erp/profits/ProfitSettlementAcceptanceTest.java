package com.lue.erp.profits;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthUser;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LUE_PROFIT_TEST_URL",matches=".+")
class ProfitSettlementAcceptanceTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean JdbcTemplate db(DataSource ds){return new JdbcTemplate(ds);}
        @Bean DataSourceTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean ProfitMatchService matches(JdbcTemplate db){return new ProfitMatchService(db);}
        @Bean ProfitCalculationService calculation(JdbcTemplate db,DataSourceTransactionManager tx,ProfitMatchService matches){return new ProfitCalculationService(db,tx,matches);}
        @Bean SettlementImportService settlements(JdbcTemplate db,ObjectMapper json,ProfitMatchService matches){return new SettlementImportService(db,json,matches);}
    }
    JdbcTemplate db;AnnotationConfigApplicationContext context;ProfitCalculationService calculation;SettlementImportService settlements;AuthUser actor=new AuthUser(1,"profit-test","Profit Test",List.of(),List.of());long order;
    @BeforeEach void setup(){String url=System.getenv("LUE_PROFIT_TEST_URL");assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:33483/lue_profit_acceptance?"));String schema="lue_profit_acceptance_"+Long.toUnsignedString(System.nanoTime());new JdbcTemplate(new DriverManagerDataSource(url.replace("/lue_profit_acceptance?","/mysql?"),"root","")).execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");DataSource ds=new DriverManagerDataSource(url.replace("/lue_profit_acceptance?","/"+schema+"?"),"root","");db=new JdbcTemplate(ds);Flyway flyway=Flyway.configure().dataSource(ds).cleanDisabled(true).load();flyway.migrate();flyway.validate();
        db.update("INSERT INTO users(id,username,password_hash,display_name) VALUES (1,'profit-test','unused','Profit Test')");db.update("INSERT INTO shops(id,shop_name,shop_identifier) VALUES (1,'Test Shop','test-shop')");db.update("INSERT INTO skus(id,internal_sku,product_name,purchase_price) VALUES (1,'KIT','Kit',99),(2,'LEGACY','Legacy',7)");db.update("INSERT INTO shop_sku_mappings(shop_id,platform_sku,sku_id,default_order_mode,status) VALUES (1,'PLAT-KIT',1,'JIT','ACTIVE')");db.update("INSERT INTO import_batches(batch_no,import_type,original_filename,stored_file_path,file_sha256,status,total_rows,valid_rows,imported_rows,created_by) VALUES ('ORDER-B','order','orders.xlsx','isolated','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','SUCCESS',2,2,2,1)");long batch=db.queryForObject("SELECT id FROM import_batches WHERE batch_no='ORDER-B'",Long.class);
        db.update("INSERT INTO order_summaries(import_batch_id,shop_id,platform_order_no,platform_line_no,platform_sku,sku_id,order_mode,quantity,sales_amount,refund_amount,platform_fee,order_date,business_key) VALUES (?,1,'ORDER-1','LINE-1','PLAT-KIT',1,'JIT',1,100,0,8,'2026-10-01',REPEAT('a',64)),(?,1,'OLD-ORDER',NULL,'OLD-SKU',2,'JIT',1,30,0,0,'2026-10-01',REPEAT('c',64))",batch,batch);order=db.queryForObject("SELECT id FROM order_summaries WHERE platform_order_no='ORDER-1'",Long.class);
        db.update("INSERT INTO warehouse_goods(goods_code,goods_name,unit) VALUES ('GOOD-A','Good A','pcs')");long good=db.queryForObject("SELECT id FROM warehouse_goods WHERE goods_code='GOOD-A'",Long.class);db.update("INSERT INTO package_materials(material_code,material_name,category,unit) VALUES ('BAG','Bag','PRODUCT_PACKAGING','pcs')");long material=db.queryForObject("SELECT id FROM package_materials WHERE material_code='BAG'",Long.class);
        db.update("INSERT INTO warehouse_location_nodes(warehouse_id,node_kind,node_code,node_name) VALUES (1,'AREA','A','Area')");long area=db.queryForObject("SELECT id FROM warehouse_location_nodes WHERE node_code='A'",Long.class);db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (1,?,'SHELF','S','Shelf')",area);long shelf=db.queryForObject("SELECT id FROM warehouse_location_nodes WHERE node_code='S'",Long.class);db.update("INSERT INTO warehouse_location_nodes(warehouse_id,parent_id,node_kind,node_code,node_name) VALUES (1,?,'LEVEL','L','Level')",shelf);long level=db.queryForObject("SELECT id FROM warehouse_location_nodes WHERE node_code='L'",Long.class);db.update("INSERT INTO warehouse_locations(warehouse_id,level_id,location_code,slot_code,location_type) VALUES (1,?,'BIN-1','BIN-1','PRIMARY_PICK')",level);long bin=db.queryForObject("SELECT id FROM warehouse_locations WHERE location_code='BIN-1'",Long.class);
        db.update("INSERT INTO sku_composition_versions(sku_id,version_no,change_reason) VALUES (1,1,'test')");long version=db.queryForObject("SELECT id FROM sku_composition_versions WHERE sku_id=1",Long.class);db.update("INSERT INTO sku_composition_items(version_id,warehouse_goods_id,quantity) VALUES (?,?,1)",version,good);
        db.update("INSERT INTO fulfillment_documents(document_no,document_type,shop_id,shop_identifier,source_document_id,local_status) VALUES ('FUL-1','JIT_ORDER',1,'test-shop','ORDER-1','SHIPPED')");long doc=db.queryForObject("SELECT id FROM fulfillment_documents WHERE document_no='FUL-1'",Long.class);db.update("INSERT INTO fulfillment_document_items(document_id,source_line_id,source_line_key,platform_sku,sku_id,ordered_qty,composition_version_id) VALUES (?,'LINE-1',REPEAT('f',64),'PLAT-KIT',1,1,?)",doc,version);long item=db.queryForObject("SELECT id FROM fulfillment_document_items WHERE document_id=?",Long.class,doc);
        db.update("INSERT INTO fulfillment_dispatches(dispatch_no,document_id,dispatch_sequence,idempotency_key,warehouse_id,status,created_by,handed_off_by,handed_off_at) VALUES ('DISP-1',?,1,'dispatch-1',1,'HANDED_OFF',1,1,'2026-10-03 12:00:00')",doc);long dispatch=db.queryForObject("SELECT id FROM fulfillment_dispatches WHERE dispatch_no='DISP-1'",Long.class);db.update("INSERT INTO fulfillment_dispatch_items(dispatch_id,document_item_id,planned_qty,picked_qty,shipped_qty,composition_version_id) VALUES (?,?,1,1,1,?)",dispatch,item,version);long di=db.queryForObject("SELECT id FROM fulfillment_dispatch_items WHERE dispatch_id=?",Long.class,dispatch);db.update("INSERT INTO fulfillment_goods_tasks(dispatch_item_id,warehouse_goods_id,units_per_sku,planned_qty,picked_qty,shipped_qty) VALUES (?,?,1,1,1,1)",di,good);long task=db.queryForObject("SELECT id FROM fulfillment_goods_tasks WHERE dispatch_item_id=?",Long.class,di);db.update("INSERT INTO fulfillment_goods_picks(task_id,warehouse_id,location_id,picked_qty,shipped_qty) VALUES (?,1,?,1,1)",task,bin);long pick=db.queryForObject("SELECT id FROM fulfillment_goods_picks WHERE task_id=?",Long.class,task);
        db.update("INSERT INTO warehouse_goods_stock_documents(document_type,warehouse_id,warehouse_goods_id,source_location_id,quantity,unit_cost_snapshot,cost_amount_snapshot,operator_id,fulfillment_dispatch_id,fulfillment_document_item_id) VALUES ('FULFILLMENT_OUTBOUND',1,?,?,1,20,20,1,?,?)",good,bin,dispatch,item);long stockDoc=db.queryForObject("SELECT id FROM warehouse_goods_stock_documents WHERE fulfillment_dispatch_id=?",Long.class,dispatch);db.update("INSERT INTO fulfillment_goods_outbounds(pick_id,stock_document_id,quantity,unit_cost,cost_amount) VALUES (?,?,1,20,20)",pick,stockDoc);
        db.update("INSERT INTO package_location_documents(document_type,warehouse_id,material_id,location_id,fulfillment_dispatch_id,fulfillment_document_item_id,quantity,unit_cost_snapshot,cost_amount_snapshot,operator_id) VALUES ('FULFILLMENT_CONSUMPTION',1,?,?,?,?,1,1,1,1)",material,bin,dispatch,item);
        context=new AnnotationConfigApplicationContext();context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",Map.of("lue.import.storage-dir",Path.of("target","profit-acceptance-files",schema).toAbsolutePath().toString())));context.registerBean(DataSource.class,()->ds);context.register(Config.class);context.refresh();calculation=context.getBean(ProfitCalculationService.class);settlements=context.getBean(SettlementImportService.class);
    }
    @AfterEach void close(){if(context!=null)context.close();}

    @Test void actualCostSettlementDatesAndIdempotency()throws Exception{
        LocalDate orderDate=LocalDate.of(2026,10,1);calculation.recalculate(orderDate,orderDate,"MANUAL",1L,null,null);
        assertMoney("20",db.queryForObject("SELECT purchase_cost FROM order_profit_facts WHERE order_summary_id=?",BigDecimal.class,order));assertMoney("1",db.queryForObject("SELECT package_cost FROM order_profit_facts WHERE order_summary_id=?",BigDecimal.class,order));assertEquals("ACTUAL",db.queryForObject("SELECT cost_basis FROM order_profit_facts WHERE order_summary_id=?",String.class,order));assertEquals("LEGACY_REFERENCE",db.queryForObject("SELECT cost_basis FROM order_profit_facts WHERE order_summary_id<>?",String.class,order));
        MockMultipartFile file=settlementFile("BILL-1","S-1","ORDER-1","LINE-1","PLAT-KIT","2026-10-10","5","95","OTHER_COST_DELTA","2","Y");Map<String,Object> first=settlements.upload(file,actor);assertEquals(1,first.get("matched"));assertEquals(0,first.get("pending"));assertEquals(true,settlements.upload(file,actor).get("duplicate"));
        Map<String,Object> conflict=settlements.upload(settlementFile("BILL-1","S-1","ORDER-1","LINE-1","PLAT-KIT","2026-10-10","6","95","OTHER_COST_DELTA","2","Y"),actor);assertEquals(1,conflict.get("pending"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM settlement_summaries",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM exceptions WHERE exception_type='SETTLEMENT_IMPORT_CONFLICT'",Integer.class));
        calculation.recalculate(orderDate,orderDate,"SETTLEMENT_IMPORT",1L,null,null);
        Map<String,Object> fact=db.queryForMap("SELECT sales_revenue revenue,platform_fee fee,purchase_cost goods,package_cost packageCost,other_cost otherCost,gross_profit gross,settlement_status status,settlement_difference difference FROM order_profit_facts WHERE order_summary_id=?",order);
        assertMoney("100",(BigDecimal)fact.get("revenue"));assertMoney("5",(BigDecimal)fact.get("fee"));assertMoney("20",(BigDecimal)fact.get("goods"));assertMoney("1",(BigDecimal)fact.get("packageCost"));assertMoney("2",(BigDecimal)fact.get("otherCost"));assertMoney("72",(BigDecimal)fact.get("gross"));assertEquals("SETTLED",fact.get("status"));assertMoney("1",(BigDecimal)fact.get("difference"));
        assertEquals(LocalDate.of(2026,10,10),db.queryForObject("SELECT settlement_date FROM settlement_summaries WHERE order_summary_id=?",java.sql.Date.class,order).toLocalDate());assertEquals(orderDate,db.queryForObject("SELECT profit_date FROM sku_profit_daily WHERE sku_id=1",java.sql.Date.class).toLocalDate());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM settlement_summaries WHERE is_deleted=FALSE",Integer.class));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM profit_recalculation_runs WHERE status='SUCCESS'",Integer.class));
        db.update("UPDATE skus SET purchase_price=9999 WHERE id=1");calculation.recalculate(orderDate,orderDate,"MANUAL",1L,null,null);assertMoney("20",db.queryForObject("SELECT purchase_cost FROM order_profit_facts WHERE order_summary_id=?",BigDecimal.class,order));
        Map<String,Object> unmapped=settlements.upload(settlementFile("BILL-3","S-3","ORDER-1","LINE-1","PLAT-KIT","2026-10-12","0","0","UNKNOWN","2","N"),actor);assertEquals(1,unmapped.get("pending"));long line=db.queryForObject("SELECT id FROM settlement_summaries WHERE platform_bill_no='BILL-3'",Long.class);assertEquals("UNMAPPED",db.queryForObject("SELECT match_status FROM settlement_summaries WHERE id=?",String.class,line));assertEquals(orderDate,context.getBean(ProfitMatchService.class).mapSettlementAdjustment(line,"OTHER_COST_DELTA"));calculation.recalculate(orderDate,orderDate,"MATCH_REPAIR",1L,null,null);assertMoney("100",db.queryForObject("SELECT sales_revenue FROM order_profit_facts WHERE order_summary_id=?",BigDecimal.class,order));assertMoney("20",db.queryForObject("SELECT purchase_cost FROM order_profit_facts WHERE order_summary_id=?",BigDecimal.class,order));assertMoney("4",db.queryForObject("SELECT other_cost FROM order_profit_facts WHERE order_summary_id=?",BigDecimal.class,order));
    }

    @Test void missingSnapshotAndManualSettlementMatchStayTraceable()throws Exception{
        LocalDate day=LocalDate.of(2026,10,1);
        db.update("DELETE FROM fulfillment_goods_outbounds");calculation.recalculate(day,day,"MANUAL",1L,null,null);
        assertEquals("COST_UNMATCHED",db.queryForObject("SELECT cost_basis FROM order_profit_facts WHERE order_summary_id=?",String.class,order));
        assertMoney("99",db.queryForObject("SELECT purchase_cost FROM order_profit_facts WHERE order_summary_id=?",BigDecimal.class,order));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM exceptions WHERE exception_type='PROFIT_COST_SOURCE_UNMATCHED'",Integer.class));
        MockMultipartFile noOrder=settlementFile("BILL-2","S-2","","LINE-1","PLAT-KIT","2026-10-11","4","96","","","N");
        Map<String,Object> imported=settlements.upload(noOrder,actor);assertEquals(1,imported.get("pending"));long line=db.queryForObject("SELECT id FROM settlement_summaries WHERE platform_bill_no='BILL-2'",Long.class);
        assertEquals("PENDING",db.queryForObject("SELECT match_status FROM settlement_summaries WHERE id=?",String.class,line));
        context.getBean(ProfitMatchService.class).repairSettlement(line,order,1L);calculation.recalculate(day,day,"MATCH_REPAIR",1L,null,null);
        assertEquals("PARTIALLY_SETTLED",db.queryForObject("SELECT settlement_status FROM order_profit_facts WHERE order_summary_id=?",String.class,order));
        assertMoney("100",db.queryForObject("SELECT sales_revenue FROM order_profit_facts WHERE order_summary_id=?",BigDecimal.class,order));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM settlement_summaries WHERE order_summary_id=?",Integer.class,order));
    }

    @Test void historicalProfitIsMarkedWithoutChangingItsAmounts(){
        String url=System.getenv("LUE_PROFIT_TEST_URL");String schema="lue_profit_history_"+Long.toUnsignedString(System.nanoTime());
        new JdbcTemplate(new DriverManagerDataSource(url.replace("/lue_profit_acceptance?","/mysql?"),"root","")).execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        DataSource source=new DriverManagerDataSource(url.replace("/lue_profit_acceptance?","/"+schema+"?"),"root","");JdbcTemplate old=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("17").cleanDisabled(true).load().migrate();
        old.update("INSERT INTO users(id,username,password_hash,display_name) VALUES (1,'history','unused','History')");old.update("INSERT INTO shops(id,shop_name,shop_identifier) VALUES (1,'History Shop','history-shop')");old.update("INSERT INTO skus(id,internal_sku,product_name,purchase_price) VALUES (1,'OLD','Old',9)");
        old.update("INSERT INTO import_batches(batch_no,import_type,original_filename,stored_file_path,file_sha256,status,total_rows,created_by) VALUES ('HISTORY-B','settlement','old.xlsx','isolated',REPEAT('d',64),'SUCCESS',1,1)");
        long batch=old.queryForObject("SELECT id FROM import_batches WHERE batch_no='HISTORY-B'",Long.class);
        old.update("INSERT INTO sku_profit_daily(profit_date,shop_id,sku_id,order_mode,sales_revenue,gross_profit) VALUES ('2025-01-01',1,1,'JIT',100,40)");
        old.update("INSERT INTO settlement_summaries(import_batch_id,source_row_number,shop_id,platform_order_no,platform_sku,sku_id,order_mode,settlement_date,platform_fee,settlement_amount) VALUES (?,2,1,'OLD-ORDER','OLD-SKU',1,'JIT','2025-01-10',5,95)",batch);
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();
        assertMoney("40",old.queryForObject("SELECT gross_profit FROM sku_profit_daily",BigDecimal.class));
        assertEquals(1,old.queryForObject("SELECT legacy_cost_orders FROM sku_profit_daily",Integer.class));
        assertEquals("UNMAPPED",old.queryForObject("SELECT match_status FROM settlement_summaries",String.class));
        assertEquals(0,old.queryForObject("SELECT COUNT(*) FROM order_profit_facts",Integer.class));
    }

    private static MockMultipartFile settlementFile(String bill,String line,String order,String orderLine,String sku,String date,String fee,String cash,String type,String adjustment,String complete)throws Exception{try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){var sh=wb.createSheet("Settlement");String[] headers={"店铺标识","平台账单号","平台结算明细号","平台订单号","订单明细号","平台 SKU","订单模式","结算日期","平台费用（元）","结算金额（元）","调整类型","调整金额（元）","最终结算"};String[] values={"test-shop",bill,line,order,orderLine,sku,"JIT",date,fee,cash,type,adjustment,complete};var h=sh.createRow(0);var r=sh.createRow(1);for(int i=0;i<headers.length;i++){h.createCell(i).setCellValue(headers[i]);r.createCell(i).setCellValue(values[i]);}wb.write(out);return new MockMultipartFile("file","settlement.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());}}
    private static void assertMoney(String expected,BigDecimal value){assertEquals(0,new BigDecimal(expected).compareTo(value));}
}
