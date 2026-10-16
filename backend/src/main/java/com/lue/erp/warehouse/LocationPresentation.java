package com.lue.erp.warehouse;

import com.lue.erp.common.api.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.*;

/** Resolve physical positions once per response, including nested picking and cost rows. */
@RestControllerAdvice(basePackages={"com.lue.erp.warehouse","com.lue.erp.profits"})
public class LocationPresentation implements ResponseBodyAdvice<Object> {
    private final JdbcTemplate db;
    public LocationPresentation(JdbcTemplate db) { this.db=db; }

    @Override
    public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
        return ApiResponse.class.isAssignableFrom(type.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter type, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof ApiResponse<?> result) || !result.success()) return body;
        Set<Long> ids=new LinkedHashSet<>(); collect(result.data(),ids);
        Map<Long,Map<String,Object>> positions=load(ids);
        return new ApiResponse<>(result.success(),result.code(),result.message(),decorate(result.data(),positions));
    }

    private void collect(Object value, Set<Long> ids) {
        if(value instanceof Map<?,?> map) {
            for(String key:List.of("locationId","sourceLocationId","targetLocationId")) add(ids,map.get(key));
            if(map.containsKey("locationCode")&&!map.containsKey("locationId"))add(ids,map.get("id"));
            map.values().forEach(child->collect(child,ids));
        } else if(value instanceof Iterable<?> rows) rows.forEach(child->collect(child,ids));
    }
    private void add(Set<Long> ids,Object value) { if(value instanceof Number n && n.longValue()>0)ids.add(n.longValue()); }

    private Map<Long,Map<String,Object>> load(Set<Long> ids) {
        Map<Long,Map<String,Object>> result=new HashMap<>(); List<Long> all=new ArrayList<>(ids);
        for(int offset=0;offset<all.size();offset+=500) {
            List<Long> batch=all.subList(offset,Math.min(offset+500,all.size()));
            List<Map<String,Object>> rows=db.queryForList("""
                SELECT l.id locationId,l.location_code locationCode,l.location_name locationName,
                  l.scan_code scanCode,l.slot_code slotCode,l.location_type locationType,l.status locationStatus,
                  w.warehouse_name warehouseName,w.warehouse_code warehouseCode,
                  a.node_name areaName,a.node_code areaCode,s.node_name shelfName,s.node_code shelfCode,
                  v.node_name levelName,v.node_code levelCode
                FROM warehouse_locations l LEFT JOIN warehouses w ON w.id=l.warehouse_id
                  LEFT JOIN warehouse_location_nodes v ON v.id=l.level_id
                  LEFT JOIN warehouse_location_nodes s ON s.id=v.parent_id
                  LEFT JOIN warehouse_location_nodes a ON a.id=s.parent_id
                WHERE l.id IN (
                """+String.join(",",Collections.nCopies(batch.size(),"?"))+")",batch.toArray());
            for(Map<String,Object> row:rows) {
                List<String> parts=new ArrayList<>();
                for(String prefix:List.of("warehouse","area","shelf","level"))parts.add(prefer(row,prefix+"Name",prefix+"Code"));
                String slot=text(row.get("slotCode"));parts.add(slot.isBlank()?"":slot.endsWith("位")?slot:slot+"位");
                boolean complete=parts.stream().noneMatch(String::isBlank);
                row.put("locationPath",complete?String.join(" / ",parts):"位置信息不完整 · "+text(row.get("locationCode")));
                row.put("locationTypeName",typeName(text(row.get("locationType"))));
                row.put("locationSearchText",String.join(" ",row.values().stream().map(LocationPresentation::text).toList()));
                result.put(((Number)row.get("locationId")).longValue(),row);
            }
        }
        return result;
    }
    public String describe(long id) {
        Map<String,Object> row=load(Set.of(id)).get(id);
        return row==null?"位置信息不完整（库位 ID "+id+"）":row.get("locationPath")+"（"+row.get("locationCode")+"）";
    }
    private Object decorate(Object value,Map<Long,Map<String,Object>> positions) {
        if(value instanceof Map<?,?> map) {
            Map<String,Object> out=new LinkedHashMap<>();map.forEach((key,child)->out.put(String.valueOf(key),decorate(child,positions)));
            Object id=map.get("locationId");
            if(!map.containsKey("locationId")&&map.containsKey("locationCode"))id=map.get("id");
            if(id instanceof Number n)merge(out,positions.get(n.longValue()),"");
            if(map.containsKey("locationCode")&&!out.containsKey("locationPath")) {
                boolean historical="HISTORICAL_UNLOCATED".equals(map.get("positionStatus"))||"历史未定位".equals(map.get("locationCode"))||(map.containsKey("locationId")&&map.get("locationId")==null);
                out.put("locationPath",historical?"历史未定位":"位置信息不完整 · "+text(map.get("locationCode")));
            }
            for(String prefix:List.of("source","target")) {
                Object ref=map.get(prefix+"LocationId");
                if(ref instanceof Number n)merge(out,positions.get(n.longValue()),prefix);
            }
            return out;
        }
        if(value instanceof Iterable<?> rows){List<Object> out=new ArrayList<>();rows.forEach(child->out.add(decorate(child,positions)));return out;}
        return value;
    }
    private void merge(Map<String,Object> out,Map<String,Object> position,String prefix) {
        if(position==null)return;
        position.forEach((key,value)->{String target=prefix.isEmpty()?key:prefix+Character.toUpperCase(key.charAt(0))+key.substring(1);if(key.equals("locationPath"))out.put(target,value);else out.putIfAbsent(target,value);});
    }
    private static String prefer(Map<String,Object> row,String name,String code){String value=text(row.get(name));return value.isBlank()?text(row.get(code)):value;}
    private static String text(Object value){return value==null?"":value.toString().trim();}
    private static String typeName(String type){return switch(type){case "STORAGE"->"正式存储位";case "PRIMARY_PICK"->"主拣货位";case "PENDING_PUTAWAY"->"待上架位";case "TEMPORARY"->"暂存位";case "QUARANTINE"->"异常 / 待检位";default->"";};}
}
