package com.lue.erp.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Local goods planning, picking and handoff. The controller holds the document/dispatch transaction. */
public class FulfillmentGoodsService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final GoodsStockService goods;
    private final PackageLocationService packages;
    public FulfillmentGoodsService(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;this.goods=new GoodsStockService(db);this.packages=new PackageLocationService(db);}

    public void expand(long dispatchItem,long sourceItem,long sku,BigDecimal skuQty,Object orderDate){
        Long fixed=db.query("SELECT composition_version_id FROM fulfillment_document_items WHERE id=?",r->r.next()?(Long)r.getObject(1):null,sourceItem);
        long version;
        if(fixed!=null){version=fixed;}
        else{
            String date=orderDate==null?java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString():orderDate.toString().substring(0,10);
            List<Long> versions=db.query("SELECT id FROM sku_composition_versions WHERE sku_id=? AND status='ACTIVE' AND (effective_from IS NULL OR effective_from<=?) AND (effective_to IS NULL OR effective_to>=?) ORDER BY version_no DESC LIMIT 2",(r,n)->r.getLong(1),sku,date,date);
            if(versions.size()!=1)throw new IllegalArgumentException("销售 SKU 缺少唯一有效组成版本，请先维护组成清单");version=versions.get(0);
            db.update("UPDATE fulfillment_document_items SET composition_version_id=? WHERE id=? AND composition_version_id IS NULL",version,sourceItem);
        }
        Integer matches=db.queryForObject("SELECT COUNT(*) FROM sku_composition_versions WHERE id=? AND sku_id=?",Integer.class,version,sku);
        if(matches==null||matches!=1)throw new IllegalArgumentException("履约明细固定的组成版本与销售 SKU 不匹配");
        db.update("UPDATE fulfillment_dispatch_items SET composition_version_id=? WHERE id=?",version,dispatchItem);
        List<Map<String,Object>> rows=db.queryForList("SELECT ci.warehouse_goods_id goodsId,ci.quantity units FROM sku_composition_items ci JOIN warehouse_goods g ON g.id=ci.warehouse_goods_id WHERE ci.version_id=? AND ci.substitute_for_item_id IS NULL AND g.is_deleted=FALSE",version);
        if(rows.isEmpty())throw new IllegalArgumentException("组成版本没有可拣仓库货品");
        for(Map<String,Object> row:rows){BigDecimal planned=quantity(dec(row.get("units")).multiply(skuQty),"货品需求");db.update("INSERT INTO fulfillment_goods_tasks(dispatch_item_id,warehouse_goods_id,units_per_sku,planned_qty) VALUES (?,?,?,?)",dispatchItem,row.get("goodsId"),row.get("units"),planned);}
    }

    public List<Map<String,Object>> tasks(long dispatch,long warehouse){
        List<Map<String,Object>> rows=db.queryForList("SELECT t.id taskId,di.id dispatchItemId,di.document_item_id itemId,i.platform_sku platformSku,i.sku_id skuId,i.composition_version_id compositionVersionId,g.id goodsId,g.goods_code goodsCode,g.goods_name goodsName,g.specification,g.barcode goodsBarcode,t.units_per_sku unitsPerSku,t.planned_qty plannedQty,t.picked_qty pickedQty,t.shipped_qty shippedQty FROM fulfillment_goods_tasks t JOIN fulfillment_dispatch_items di ON di.id=t.dispatch_item_id JOIN fulfillment_document_items i ON i.id=di.document_item_id JOIN warehouse_goods g ON g.id=t.warehouse_goods_id WHERE di.dispatch_id=? ORDER BY di.id,t.id",dispatch);
        for(Map<String,Object> row:rows){long good=((Number)row.get("goodsId")).longValue();List<Map<String,Object>> locations=db.queryForList("SELECT l.id locationId,l.location_code locationCode,l.scan_code scanCode,l.location_type locationType,COALESCE(b.available_qty,0) availableQty,COALESCE(a.is_primary_pick,FALSE) primaryPick FROM warehouse_locations l LEFT JOIN warehouse_goods_location_balances b ON b.warehouse_id=l.warehouse_id AND b.location_id=l.id AND b.warehouse_goods_id=? LEFT JOIN warehouse_goods_location_assignments a ON a.location_id=l.id AND a.warehouse_goods_id=? AND a.status='ACTIVE' WHERE l.warehouse_id=? AND l.status='ACTIVE' AND l.location_type IN ('STORAGE','PRIMARY_PICK','TEMPORARY') AND (COALESCE(b.available_qty,0)>0 OR COALESCE(a.is_primary_pick,FALSE)=TRUE) ORDER BY COALESCE(a.is_primary_pick,FALSE) DESC,COALESCE(b.available_qty,0) DESC,l.location_code",good,good,warehouse);
            row.put("locations",locations);BigDecimal available=locations.stream().map(v->dec(v.get("availableQty")).max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO,BigDecimal::add);row.put("shortageQty",dec(row.get("plannedQty")).subtract(available).max(BigDecimal.ZERO));
            row.put("picks",db.queryForList("SELECT p.id pickId,p.location_id locationId,l.location_code locationCode,p.picked_qty pickedQty,p.shipped_qty shippedQty FROM fulfillment_goods_picks p JOIN warehouse_locations l ON l.id=p.location_id WHERE p.task_id=? ORDER BY p.id",row.get("taskId")));
        }return rows;
    }

    public Map<String,Object> pick(long dispatch,long warehouse,Map<String,Object> body,long operator){
        long task=id(body,"taskId"),location=id(body,"locationId");BigDecimal qty=positive(body,"quantity");
        Map<String,Object> taskRow=db.queryForMap("SELECT t.id,t.warehouse_goods_id goodsId,t.planned_qty plannedQty,t.picked_qty pickedQty,t.dispatch_item_id dispatchItemId FROM fulfillment_goods_tasks t JOIN fulfillment_dispatch_items di ON di.id=t.dispatch_item_id WHERE t.id=? AND di.dispatch_id=? FOR UPDATE",task,dispatch);
        long good=((Number)taskRow.get("goodsId")).longValue();
        String code=db.query("SELECT goods_code FROM warehouse_goods WHERE id=? AND is_deleted=FALSE",r->r.next()?r.getString(1):null,good);
        String barcode=string(body,"barcode");if(barcode!=null){Integer n=db.queryForObject("SELECT COUNT(*) FROM warehouse_goods WHERE id=? AND (barcode=? OR goods_code=?)",Integer.class,good,barcode,barcode);if(n==null||n==0)throw new IllegalArgumentException("扫描货品与拣货任务不一致："+code);}
        Map<String,Object> bin=db.query("SELECT location_code locationCode,scan_code scanCode,location_type locationType FROM warehouse_locations WHERE id=? AND warehouse_id=? AND status='ACTIVE'",r->r.next()?Map.of("locationCode",r.getString(1),"scanCode",Objects.toString(r.getString(2),""),"locationType",r.getString(3)):null,location,warehouse);
        if(bin==null||!Set.of("STORAGE","PRIMARY_PICK","TEMPORARY").contains(bin.get("locationType")))throw new IllegalArgumentException("来源库位无效或不可拣");
        String scan=string(body,"locationScan");if(scan!=null&&!scan.equals(bin.get("locationCode"))&&!scan.equals(bin.get("scanCode")))throw new IllegalArgumentException("扫描库位与选择的来源位不一致");
        BigDecimal after=dec(taskRow.get("pickedQty")).add(qty);if(after.compareTo(dec(taskRow.get("plannedQty")))>0)throw new IllegalArgumentException("货品实拣超过本批次计划");
        db.update("INSERT INTO fulfillment_goods_picks(task_id,warehouse_id,location_id) VALUES (?,?,?) ON DUPLICATE KEY UPDATE id=id",task,warehouse,location);
        Map<String,Object> pick=db.queryForMap("SELECT id,picked_qty FROM fulfillment_goods_picks WHERE task_id=? AND location_id=? FOR UPDATE",task,location);
        db.update("UPDATE fulfillment_goods_picks SET picked_qty=picked_qty+? WHERE id=?",qty,pick.get("id"));db.update("UPDATE fulfillment_goods_tasks SET picked_qty=? WHERE id=?",after,task);
        db.update("INSERT IGNORE INTO warehouse_location_task_claims(warehouse_id,location_id,task_type,source_type,source_id) VALUES (?,?,'FULFILLMENT_PICK','fulfillment_goods_pick',?)",warehouse,location,pick.get("id"));
        long dispatchItem=((Number)taskRow.get("dispatchItemId")).longValue();
        List<Map<String,Object>> component=db.queryForList("SELECT picked_qty,units_per_sku FROM fulfillment_goods_tasks WHERE dispatch_item_id=?",dispatchItem);
        BigDecimal possible=component.stream().map(v->dec(v.get("picked_qty")).divide(dec(v.get("units_per_sku")),4,RoundingMode.DOWN)).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        db.update("UPDATE fulfillment_dispatch_items SET picked_qty=LEAST(planned_qty,?) WHERE id=?",possible,dispatchItem);
        db.update("UPDATE fulfillment_dispatches SET status=IF((SELECT COUNT(*) FROM fulfillment_dispatch_items WHERE dispatch_id=? AND picked_qty<planned_qty)=0,'PICKED','PICKING') WHERE id=?",dispatch,dispatch);
        BigDecimal stock=Objects.requireNonNull(db.queryForObject("SELECT COALESCE(available_qty,0) FROM warehouse_goods_location_balances WHERE warehouse_id=? AND warehouse_goods_id=? AND location_id=?",BigDecimal.class,warehouse,good,location));
        BigDecimal pickedAtBin=dec(pick.get("picked_qty")).add(qty);
        return Map.of("taskId",task,"pickedQty",after,"plannedQty",taskRow.get("plannedQty"),"availableQty",stock,"shortageWarning",stock.compareTo(pickedAtBin)<0);
    }

    public Map<String,Object> handoff(long dispatch,long warehouse,String type,Map<String,Object> body,long operator){
        List<Map<String,Object>> items=db.queryForList("SELECT di.id dispatchItemId,di.document_item_id itemId,di.planned_qty plannedQty,di.picked_qty pickedQty,i.ordered_qty orderedQty,i.sku_id skuId FROM fulfillment_dispatch_items di JOIN fulfillment_document_items i ON i.id=di.document_item_id WHERE di.dispatch_id=? ORDER BY di.id FOR UPDATE",dispatch);
        if(items.isEmpty())throw new IllegalArgumentException("发货批次没有明细");
        Map<Long,BigDecimal> shippedByItem=new LinkedHashMap<>();BigDecimal total=BigDecimal.ZERO;
        for(Map<String,Object> item:items){long itemId=((Number)item.get("itemId")).longValue();Object value=body.get("shippedQty_"+itemId);BigDecimal shipped=value==null?dec(item.get("pickedQty")):quantity(dec(value),"SKU 实发量");if(shipped.signum()<0||shipped.compareTo(dec(item.get("pickedQty")))>0||shipped.compareTo(dec(item.get("plannedQty")))>0)throw new IllegalArgumentException("SKU 实发量不得超过已拣和本批次计划");
            BigDecimal previous=Objects.requireNonNull(db.queryForObject("SELECT COALESCE(SUM(di.shipped_qty),0) FROM fulfillment_dispatch_items di JOIN fulfillment_dispatches d ON d.id=di.dispatch_id WHERE di.document_item_id=? AND d.status='HANDED_OFF'",BigDecimal.class,itemId));
            if(previous.add(shipped).compareTo(dec(item.get("orderedQty")))>0)throw new IllegalArgumentException("累计实发超过订单/备货明细剩余数量");shippedByItem.put(itemId,shipped);total=total.add(shipped);
        }
        if(total.signum()==0)throw new IllegalArgumentException("本次实发数量必须大于零");
        List<String> warnings=new ArrayList<>();
        for(Map<String,Object> item:items){long itemId=((Number)item.get("itemId")).longValue(),dispatchItem=((Number)item.get("dispatchItemId")).longValue();BigDecimal skuShipped=shippedByItem.get(itemId);if(skuShipped.signum()==0)continue;
            List<Map<String,Object>> tasks=db.queryForList("SELECT id,warehouse_goods_id goodsId,units_per_sku units,picked_qty pickedQty,shipped_qty shippedQty FROM fulfillment_goods_tasks WHERE dispatch_item_id=? ORDER BY id FOR UPDATE",dispatchItem);
            if(tasks.isEmpty())throw new IllegalArgumentException("履约批次缺少货品任务，请重新创建本地拣货批次");
            for(Map<String,Object> task:tasks){long taskId=((Number)task.get("id")).longValue(),good=((Number)task.get("goodsId")).longValue();BigDecimal remaining=quantity(skuShipped.multiply(dec(task.get("units"))),"货品实发量");if(remaining.compareTo(dec(task.get("pickedQty")).subtract(dec(task.get("shippedQty"))))>0)throw new IllegalArgumentException("货品实发量超过已拣数量");
                List<Map<String,Object>> picks=db.queryForList("SELECT id,location_id locationId,picked_qty pickedQty,shipped_qty shippedQty FROM fulfillment_goods_picks WHERE task_id=? ORDER BY id FOR UPDATE",taskId);
                for(Map<String,Object> pick:picks){if(remaining.signum()==0)break;BigDecimal can=dec(pick.get("pickedQty")).subtract(dec(pick.get("shippedQty")));BigDecimal use=can.min(remaining);if(use.signum()<=0)continue;long location=((Number)pick.get("locationId")).longValue();GoodsStockService.Outbound out=goods.outbound(warehouse,good,location,use,operator,"履约交接",dispatch,itemId,true);
                    db.update("UPDATE fulfillment_goods_picks SET shipped_qty=shipped_qty+? WHERE id=?",use,pick.get("id"));db.update("INSERT INTO fulfillment_goods_outbounds(pick_id,stock_document_id,quantity,unit_cost,cost_amount) VALUES (?,?,?,?,?)",pick.get("id"),out.documentId(),use,out.unitCost(),out.amount());
                    db.update("UPDATE warehouse_location_task_claims SET status='DONE' WHERE task_type='FULFILLMENT_PICK' AND source_type='fulfillment_goods_pick' AND source_id=?",pick.get("id"));
                    if(out.shortage().signum()>0){String warning="货品 "+good+" 库位 "+new LocationPresentation(db).describe(location)+" 缺货 "+out.shortage();warnings.add(warning);exception("FULFILLMENT_STOCK_SHORTAGE",itemId,dispatch,good,location,out.shortage(),warning);}
                    remaining=remaining.subtract(use);
                }
                if(remaining.signum()>0)throw new IllegalArgumentException("货品实际拣货不足，无法分配实发来源库位");
                db.update("UPDATE fulfillment_goods_tasks SET shipped_qty=shipped_qty+? WHERE id=?",skuShipped.multiply(dec(task.get("units"))),taskId);
            }
            db.update("UPDATE fulfillment_dispatch_items SET shipped_qty=? WHERE id=?",skuShipped,dispatchItem);
        }
        consumePackages(dispatch,warehouse,type,items,shippedByItem,body,operator,warnings);
        return Map.of("shippedQty",total,"warnings",warnings,"status","HANDED_OFF");
    }

    public List<Map<String,Object>> packageSuggestions(long dispatch,long warehouse,Map<Long,BigDecimal> shippedByItem){
        List<Map<String,Object>> result=new ArrayList<>();
        List<Map<String,Object>> items=db.queryForList("SELECT di.id dispatchItemId,di.document_item_id itemId,i.sku_id skuId FROM fulfillment_dispatch_items di JOIN fulfillment_document_items i ON i.id=di.document_item_id WHERE di.dispatch_id=?",dispatch);
        for(Map<String,Object> item:items){long itemId=((Number)item.get("itemId")).longValue(),dispatchItem=((Number)item.get("dispatchItemId")).longValue(),sku=((Number)item.get("skuId")).longValue();BigDecimal units=shippedByItem.getOrDefault(itemId,BigDecimal.ZERO);if(units.signum()<=0)continue;
            List<String> fields=new ArrayList<>();fields.add("default_product_packaging_plan");String type=db.queryForObject("SELECT d.document_type FROM fulfillment_documents d JOIN fulfillment_dispatches x ON x.document_id=d.id WHERE x.id=?",String.class,dispatch);if("JIT_ORDER".equals(type))fields.add("default_shipping_material_plan");
            for(String field:fields){String raw=db.queryForObject("SELECT "+field+" FROM skus WHERE id=?",String.class,sku);if(raw==null||raw.isBlank())continue;try{JsonNode plan=json.readTree(raw);if(!plan.isArray())throw new IllegalArgumentException("默认包材方案格式无效");for(JsonNode row:plan){String code=row.path("materialCode").asText();BigDecimal per=row.hasNonNull("quantity")?row.get("quantity").decimalValue():BigDecimal.ONE;BigDecimal suggested=quantity(per.multiply(units),"包材建议量");Map<String,Object> material=db.query("SELECT id,material_name materialName,category FROM package_materials WHERE material_code=? AND status='ACTIVE' AND is_deleted=FALSE",r->r.next()?Map.of("id",r.getLong(1),"materialName",r.getString(2),"category",r.getString(3)):null,code);
                        if(material==null)throw new IllegalArgumentException("默认包材方案引用了不存在的包材："+code);
                        if("default_product_packaging_plan".equals(field)&&!"PRODUCT_PACKAGING".equals(material.get("category")))throw new IllegalArgumentException("产品包装方案引用了非产品包装包材："+code);
                        if("default_shipping_material_plan".equals(field)&&!"SHIPPING_MATERIAL".equals(material.get("category")))throw new IllegalArgumentException("发货打包方案引用了非发货包材："+code);
                        Integer overlap=db.queryForObject("SELECT COUNT(*) FROM fulfillment_dispatch_items di JOIN fulfillment_goods_tasks t ON t.dispatch_item_id=di.id JOIN warehouse_goods g ON g.id=t.warehouse_goods_id WHERE di.id=? AND (g.goods_code=? OR g.barcode=?)",Integer.class,dispatchItem,code,code);if(overlap!=null&&overlap>0)throw new IllegalArgumentException("同一实体同时配置为组成货品和包材："+code);
                        Map<String,Object> suggestion=new LinkedHashMap<>();suggestion.put("dispatchItemId",dispatchItem);suggestion.put("itemId",itemId);suggestion.put("materialId",material.get("id"));suggestion.put("materialCode",code);suggestion.put("materialName",material.get("materialName"));suggestion.put("category",material.get("category"));suggestion.put("suggestedQty",suggested);
                        List<Map<String,Object>> bins=db.queryForList("SELECT b.location_id locationId,l.location_code locationCode,b.available_qty availableQty FROM package_location_balances b JOIN warehouse_locations l ON l.id=b.location_id WHERE b.warehouse_id=? AND b.material_id=? AND l.status='ACTIVE' ORDER BY b.available_qty DESC,l.location_code",warehouse,material.get("id"));suggestion.put("locations",bins);suggestion.put("locationId",bins.isEmpty()?null:bins.get(0).get("locationId"));result.add(suggestion);
                    }}catch(java.io.IOException e){throw new IllegalArgumentException("默认包材方案格式无效",e);}
            }
        }return result;
    }

    private void consumePackages(long dispatch,long warehouse,String type,List<Map<String,Object>> items,Map<Long,BigDecimal> shippedByItem,Map<String,Object> body,long operator,List<String> warnings){
        List<Map<String,Object>> suggestions=packageSuggestions(dispatch,warehouse,shippedByItem);
        Object input=body.get("packageUsages");if(!(input instanceof List<?> raw))throw new IllegalArgumentException("请确认本次包材实际消耗；无包材也请提交空数组");
        Map<String,BigDecimal> defaults=new HashMap<>();for(Map<String,Object> suggestion:suggestions){String key=suggestion.get("dispatchItemId")+":"+suggestion.get("materialId");defaults.merge(key,dec(suggestion.get("suggestedQty")),BigDecimal::add);}
        Map<String,BigDecimal> actual=new HashMap<>();Map<String,String> reasons=new HashMap<>();Set<Long> validItems=new HashSet<>();for(Map<String,Object> item:items)if(shippedByItem.getOrDefault(((Number)item.get("itemId")).longValue(),BigDecimal.ZERO).signum()>0)validItems.add(((Number)item.get("dispatchItemId")).longValue());
        List<Map<String,Object>> usages=new ArrayList<>();for(Object o:raw){if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("包材实耗格式错误");Map<String,Object> row=new HashMap<>();m.forEach((k,v)->row.put(String.valueOf(k),v));long dispatchItem=id(row,"dispatchItemId"),material=id(row,"materialId");if(!validItems.contains(dispatchItem))throw new IllegalArgumentException("包材实耗不属于本批次");BigDecimal qty=nonnegative(row,"quantity");String key=dispatchItem+":"+material;actual.merge(key,qty,BigDecimal::add);String reason=string(row,"reason");if(reason!=null)reasons.put(key,reason);usages.add(row);}
        Set<String> keys=new HashSet<>(defaults.keySet());keys.addAll(actual.keySet());for(String key:keys){BigDecimal suggested=defaults.getOrDefault(key,BigDecimal.ZERO),used=actual.getOrDefault(key,BigDecimal.ZERO);if(used.compareTo(suggested)!=0&&!reasons.containsKey(key))throw new IllegalArgumentException("包材实耗与建议不同，必须填写覆盖原因："+key);String[] pair=key.split(":");db.update("INSERT INTO fulfillment_package_usage_decisions(dispatch_item_id,material_id,suggested_qty,actual_qty,override_reason,operator_id) VALUES (?,?,?,?,?,?)",Long.parseLong(pair[0]),Long.parseLong(pair[1]),suggested,used,reasons.get(key),operator);}
        for(Map<String,Object> row:usages){BigDecimal qty=nonnegative(row,"quantity");if(qty.signum()==0)continue;long dispatchItem=id(row,"dispatchItemId"),material=id(row,"materialId"),location=id(row,"locationId");long itemId=items.stream().filter(v->((Number)v.get("dispatchItemId")).longValue()==dispatchItem).map(v->((Number)v.get("itemId")).longValue()).findFirst().orElseThrow();
            String category=db.queryForObject("SELECT category FROM package_materials WHERE id=?",String.class,material);if("PLATFORM_WAREHOUSE_STOCKING".equals(type)&&!"PRODUCT_PACKAGING".equals(category))throw new IllegalArgumentException("平台仓备货不能消耗发货打包材料");
            Integer overlap=db.queryForObject("SELECT COUNT(*) FROM package_materials m JOIN fulfillment_goods_tasks t ON t.dispatch_item_id=? JOIN warehouse_goods g ON g.id=t.warehouse_goods_id WHERE m.id=? AND (g.goods_code=m.material_code OR g.barcode=m.material_code)",Integer.class,dispatchItem,material);if(overlap!=null&&overlap>0)throw new IllegalArgumentException("同一实体不能同时作为组成货品与包材实耗");
            PackageLocationService.Consumption consumed=packages.consume(warehouse,material,location,qty,dispatch,itemId,"JIT_ORDER".equals(type)?"JIT_CONSUMPTION":"STOCKING_CONSUMPTION",Objects.toString(row.get("reason"),"履约包材实耗"),operator);
            if(consumed.shortage().signum()>0){String warning="包材 "+material+" 库位 "+new LocationPresentation(db).describe(location)+" 缺货 "+consumed.shortage();warnings.add(warning);exception("FULFILLMENT_PACKAGE_SHORTAGE",itemId,dispatch,material,location,consumed.shortage(),warning);}
        }
    }

    private void exception(String type,long item,long dispatch,long object,long location,BigDecimal shortage,String title){db.update("INSERT INTO exceptions(exception_no,exception_type,status,fulfillment_item_id,title,raw_data) VALUES (?,?,'OPEN',?,?,JSON_OBJECT('dispatchId',?,'objectId',?,'locationId',?,'shortageQty',?))","EX"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT),type,item,title,dispatch,object,location,shortage);}
    private static BigDecimal dec(Object x){return new BigDecimal(String.valueOf(x));}
    private static BigDecimal quantity(BigDecimal value,String name){if(value.signum()<0||value.stripTrailingZeros().scale()>4||value.precision()-value.scale()>14)throw new IllegalArgumentException(name+" 超出四位小数精度");return value.setScale(4);}
    private static BigDecimal positive(Map<String,Object> body,String key){BigDecimal v=quantity(dec(body.get(key)),key);if(v.signum()<=0)throw new IllegalArgumentException(key+" 必须大于零");return v;}
    private static BigDecimal nonnegative(Map<String,Object> body,String key){return quantity(dec(body.get(key)),key);}
    private static long id(Map<String,Object> body,String key){try{long n=Long.parseLong(String.valueOf(body.get(key)));if(n<=0)throw new Exception();return n;}catch(Exception e){throw new IllegalArgumentException(key+" 必填");}}
    private static String string(Map<String,Object> body,String key){Object v=body.get(key);return v==null||String.valueOf(v).trim().isEmpty()?null:String.valueOf(v).trim();}
}
