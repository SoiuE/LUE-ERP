package com.lue.erp.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ShopSkuMappingService {
    private static final Set<String> FIELDS = Set.of("shopId", "platformSku", "platformProductId", "platformProductName", "skuId", "defaultOrderMode", "status");
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final ObjectMapper mapper;
    public ShopSkuMappingService(JdbcTemplate jdbc, AuthService auth, ObjectMapper mapper) { this.jdbc=jdbc; this.auth=auth; this.mapper=mapper; }

    public void checkView(AuthUser user) { auth.requirePermission(user.id(), "master.mappings.view"); }
    public void checkManage(AuthUser user) { auth.requirePermission(user.id(), "master.data.manage"); }

    public Map<String,Object> list(int page, int pageSize, Long shopId, String platformSku, String internalSku, String q, String status) {
        int safePage=Math.max(1,page), safeSize=Math.min(100,Math.max(1,pageSize));
        StringBuilder where=new StringBuilder(" WHERE m.is_deleted=FALSE");
        List<Object> args=new ArrayList<>();
        if(shopId!=null){where.append(" AND m.shop_id=?");args.add(shopId);}
        if(platformSku!=null&&!platformSku.isBlank()){where.append(" AND m.platform_sku LIKE ?");args.add("%"+platformSku.trim()+"%");}
        if(internalSku!=null&&!internalSku.isBlank()){where.append(" AND s.internal_sku LIKE ?");args.add("%"+internalSku.trim()+"%");}
        if(q!=null&&!q.isBlank()){where.append(" AND (m.platform_sku LIKE ? OR m.platform_product_id LIKE ? OR m.platform_product_name LIKE ? OR s.internal_sku LIKE ? OR s.product_name LIKE ? OR sh.shop_name LIKE ?)");for(int i=0;i<6;i++)args.add("%"+q.trim()+"%");}
        if(status!=null&&!status.isBlank()){where.append(" AND m.status=?");args.add(status);}
        String from=" FROM shop_sku_mappings m JOIN shops sh ON sh.id=m.shop_id JOIN skus s ON s.id=m.sku_id";
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(safeSize);pageArgs.add((safePage-1)*safeSize);
        List<Map<String,Object>> items=jdbc.queryForList("SELECT m.id,m.shop_id AS shopId,sh.shop_name AS shopName,m.platform_sku AS platformSku,m.platform_product_id AS platformProductId,m.platform_product_name AS platformProductName,m.sku_id AS skuId,s.internal_sku AS internalSku,s.product_name AS productName,m.default_order_mode AS defaultOrderMode,m.status,m.created_at AS createdAt,m.updated_at AS updatedAt"+from+where+" ORDER BY m.id DESC LIMIT ? OFFSET ?",pageArgs.toArray());
        return Map.of("items",items,"page",safePage,"pageSize",safeSize,"total",total==null?0L:total);
    }

    @Transactional
    public Map<String,Object> create(Map<String,Object> body, AuthUser actor, HttpServletRequest request) {
        Map<String,Object> value=clean(body);value.putIfAbsent("defaultOrderMode","JIT");value.putIfAbsent("status","ACTIVE");validate(value,true);
        jdbc.update("INSERT INTO shop_sku_mappings(shop_id,platform_sku,platform_product_id,platform_product_name,sku_id,default_order_mode,status) VALUES (?,?,?,?,?,?,?)",
            value.get("shopId"),value.get("platformSku"),value.get("platformProductId"),value.get("platformProductName"),value.get("skuId"),value.get("defaultOrderMode"),value.get("status"));
        Long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        audit(actor,request,"shop_sku_mapping.create",id,value);
        return Map.of("id",id);
    }

    @Transactional
    public void update(long id, Map<String,Object> body, AuthUser actor, HttpServletRequest request) {
        Map<String,Object> value=clean(body);
        Map<String,Object> current=jdbc.queryForMap("SELECT shop_id AS shopId,platform_sku AS platformSku,platform_product_id AS platformProductId,platform_product_name AS platformProductName,sku_id AS skuId,default_order_mode AS defaultOrderMode,status FROM shop_sku_mappings WHERE id=? AND is_deleted=FALSE",id);
        current.putAll(value);validate(current,false);
        int changed=jdbc.update("UPDATE shop_sku_mappings SET shop_id=?,platform_sku=?,platform_product_id=?,platform_product_name=?,sku_id=?,default_order_mode=?,status=? WHERE id=? AND is_deleted=FALSE",
            current.get("shopId"),current.get("platformSku"),current.get("platformProductId"),current.get("platformProductName"),current.get("skuId"),current.get("defaultOrderMode"),current.get("status"),id);
        if(changed==0)throw new IllegalArgumentException("映射不存在或已删除");
        audit(actor,request,"shop_sku_mapping.update",id,value);
    }

    @Transactional
    public void deactivate(long id, AuthUser actor, HttpServletRequest request) {
        int changed=jdbc.update("UPDATE shop_sku_mappings SET status='INACTIVE' WHERE id=? AND is_deleted=FALSE AND status='ACTIVE'",id);
        if(changed==0){Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM shop_sku_mappings WHERE id=? AND is_deleted=FALSE",Integer.class,id);if(exists==null||exists==0)throw new IllegalArgumentException("映射不存在或已删除");}
        audit(actor,request,"shop_sku_mapping.deactivate",id,Map.of("status","INACTIVE"));
    }

    private Map<String,Object> clean(Map<String,Object> body){
        if(body==null)throw new IllegalArgumentException("请求数据不能为空");
        for(String key:body.keySet())if(!FIELDS.contains(key))throw new IllegalArgumentException("不支持的字段："+key);
        Map<String,Object> result=new LinkedHashMap<>(body);
        if(result.containsKey("shopId")&&result.get("shopId")!=null)result.put("shopId",parseId(result.get("shopId"),"店铺"));
        if(result.containsKey("skuId")&&result.get("skuId")!=null)result.put("skuId",parseId(result.get("skuId"),"内部 SKU"));
        if(result.containsKey("platformSku")&&result.get("platformSku")!=null)result.put("platformSku",String.valueOf(result.get("platformSku")).trim());
        return result;
    }
    private void validate(Map<String,Object> value,boolean creating){
        for(String required:List.of("shopId","platformSku","skuId"))if(value.get(required)==null||String.valueOf(value.get(required)).isBlank())throw new IllegalArgumentException(required+" 为必填项");
        if(String.valueOf(value.get("platformSku")).length()>160)throw new IllegalArgumentException("平台 SKU 最长 160 个字符");
        if(!Set.of("JIT","WAREHOUSE").contains(value.get("defaultOrderMode")))throw new IllegalArgumentException("订单模式只能为 JIT 或 WAREHOUSE");
        if(!Set.of("ACTIVE","INACTIVE").contains(value.get("status")))throw new IllegalArgumentException("映射状态无效");
        Integer shop=jdbc.queryForObject("SELECT COUNT(*) FROM shops WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",Integer.class,value.get("shopId"));
        if(shop==null||shop==0)throw new IllegalArgumentException("请选择已启用的店铺");
        Integer sku=jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE",Integer.class,value.get("skuId"));
        if(sku==null||sku==0)throw new IllegalArgumentException("请选择已启用的内部 SKU");
    }
    private long parseId(Object value,String label){try{return Long.parseLong(String.valueOf(value));}catch(NumberFormatException e){throw new IllegalArgumentException(label+"选择无效");}}
    private void audit(AuthUser actor,HttpServletRequest request,String action,long id,Map<String,Object> detail){
        try{jdbc.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,'shop_sku_mapping',?,CAST(? AS JSON),?,TRUE)",actor.id(),actor.username(),action,String.valueOf(id),mapper.writeValueAsString(detail),request.getRemoteAddr());}
        catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }
}
