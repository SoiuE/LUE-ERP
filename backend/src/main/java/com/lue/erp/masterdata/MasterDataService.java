package com.lue.erp.masterdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lue.erp.auth.AuthService;
import com.lue.erp.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class MasterDataService {
    private record Spec(String table, String permission, List<String> fields, List<String> search, Set<String> required, Set<String> jsonFields) {}
    private static final Map<String, Spec> SPECS = Map.of(
        "shops", spec("shops", "master.shops.view", "shop_name,platform,shop_identifier,account_entity,owner_name,status,note", "shop_name,shop_identifier,owner_name,account_entity", "shop_name,shop_identifier", ""),
        "skus", spec("skus", "master.skus.view", "internal_sku,spu,product_name,name_zh,name_en,specification,color,size,barcode,category,supplier_id,purchase_price,supply_price,weight,length,width,height,default_product_packaging_plan,default_shipping_material_plan,image_url,status,note", "internal_sku,spu,product_name,name_zh,name_en,barcode,category", "internal_sku,product_name", "default_product_packaging_plan,default_shipping_material_plan"),
        "suppliers", spec("suppliers", "master.suppliers.view", "supplier_code,supplier_name,contact_name,phone,email,address,note,status", "supplier_code,supplier_name,contact_name,phone,email", "supplier_code,supplier_name", ""),
        "materials", spec("package_materials", "master.materials.view", "material_code,material_name,category,specification,unit,unit_price,safety_stock,status,note", "material_code,material_name,specification", "material_code,material_name,category,unit", "")
    );

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final ObjectMapper mapper;
    public MasterDataService(JdbcTemplate jdbc, AuthService auth, ObjectMapper mapper) { this.jdbc = jdbc; this.auth = auth; this.mapper = mapper; }

    private static Spec spec(String table, String permission, String fields, String search, String required, String json) {
        return new Spec(table, permission, Arrays.stream(fields.split(",")).toList(), Arrays.stream(search.split(",")).toList(),
            required.isBlank() ? Set.of() : Set.of(required.split(",")), json.isBlank() ? Set.of() : Set.of(json.split(",")));
    }
    public String permission(String type) { return getSpec(type).permission; }
    public void checkView(String type, AuthUser user) { auth.requirePermission(user.id(), permission(type)); }
    public void checkManage(AuthUser user) { auth.requirePermission(user.id(), "master.data.manage"); }

    @Transactional
    public Map<String,String> nextCode(String type) { return Map.of("code", reserveCode(type)); }

    private String reserveCode(String type) {
        String sequenceKey;
        String column;
        String table;
        String prefix;
        if (type.equals("suppliers")) { sequenceKey="SUPPLIER"; column="supplier_code"; table="suppliers"; prefix="SUP"; }
        else if (type.equals("materials")) { sequenceKey="MATERIAL"; column="material_code"; table="package_materials"; prefix="MAT"; }
        else throw new IllegalArgumentException("该档案类型不使用自动编码");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT INTO master_code_sequences(sequence_key,sequence_date,sequence_value) VALUES (?,?,1) ON DUPLICATE KEY UPDATE sequence_value=IF(sequence_date=VALUES(sequence_date),sequence_value+1,1),sequence_date=VALUES(sequence_date)", sequenceKey, today);
        for (int attempt=0; attempt<100; attempt++) {
            Long sequence = jdbc.queryForObject("SELECT sequence_value FROM master_code_sequences WHERE sequence_key=?", Long.class, sequenceKey);
            String code = prefix + today.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format(Locale.ROOT, "%06d", sequence);
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, code);
            if (exists == null || exists == 0) return code;
            jdbc.update("UPDATE master_code_sequences SET sequence_value=sequence_value+1 WHERE sequence_key=?", sequenceKey);
        }
        throw new IllegalStateException("编码生成失败，请重试");
    }

    public Map<String,Object> list(String type, int page, int pageSize, String q, String status, String category) {
        Spec s = getSpec(type);
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder(" WHERE is_deleted=FALSE");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (").append(s.search.stream().map(f -> "CAST(" + f + " AS CHAR) LIKE ?").collect(Collectors.joining(" OR "))).append(")");
            for (int i=0;i<s.search.size();i++) args.add("%" + q.trim() + "%");
        }
        if (status != null && !status.isBlank()) { where.append(" AND status=?"); args.add(status); }
        if (category != null && !category.isBlank() && type.equals("materials")) { where.append(" AND category=?"); args.add(category); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + s.table + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(safeSize); pageArgs.add((safePage - 1) * safeSize);
        String select = s.fields.stream().map(f -> f + " AS " + camel(f)).collect(Collectors.joining(","));
        String supplierJoin = type.equals("skus") ? " LEFT JOIN suppliers supplier ON supplier.id=base.supplier_id" : "";
        String tableAlias = type.equals("skus") ? "skus base" : s.table;
        String itemSelect = type.equals("skus") ? "base.id," + select.replaceAll("(?<![A-Za-z0-9_])([a-z][a-z0-9_]*)(?= AS )", "base.$1") + ",supplier.supplier_name AS supplierName,base.created_at AS createdAt,base.updated_at AS updatedAt" : "id," + select + ",created_at AS createdAt,updated_at AS updatedAt";
        String itemWhere = type.equals("skus") ? where.toString().replaceAll("(?<![A-Za-z0-9_])is_deleted(?![A-Za-z0-9_])", "base.is_deleted").replaceAll("(?<![A-Za-z0-9_])status(?![A-Za-z0-9_])", "base.status") : where.toString();
        List<Map<String,Object>> items = jdbc.queryForList("SELECT " + itemSelect + " FROM " + tableAlias + supplierJoin + itemWhere + " ORDER BY " + (type.equals("skus") ? "base." : "") + "id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return Map.of("items", items, "page", safePage, "pageSize", safeSize, "total", total == null ? 0L : total);
    }

    @Transactional
    public Map<String,Object> create(String type, Map<String,Object> body, AuthUser actor, HttpServletRequest request) {
        Spec s = getSpec(type); Map<String,Object> values = cleanValues(s, body);
        if (type.equals("suppliers") && blank(values.get("supplier_code"))) values.put("supplier_code", reserveCode(type));
        if (type.equals("materials") && blank(values.get("material_code"))) values.put("material_code", reserveCode(type));
        validate(s, values, true); applyDefaults(type, values);
        List<String> columns = new ArrayList<>(values.keySet());
        String placeholders = columns.stream().map(f -> s.jsonFields.contains(f) ? "CAST(? AS JSON)" : "?").collect(Collectors.joining(","));
        Object[] args = columns.stream().map(values::get).toArray();
        jdbc.update("INSERT INTO " + s.table + "(" + String.join(",", columns) + ") VALUES (" + placeholders + ")", args);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        audit(actor, request, "master." + type + ".create", s.table, String.valueOf(id), values);
        return Map.of("id", id);
    }

    @Transactional
    public void update(String type, long id, Map<String,Object> body, AuthUser actor, HttpServletRequest request) {
        Spec s = getSpec(type); Map<String,Object> values = cleanValues(s, body);
        if (values.isEmpty()) throw new IllegalArgumentException("没有可更新的字段");
        validate(s, values, false);
        List<String> columns = new ArrayList<>(values.keySet());
        String assignments = columns.stream().map(f -> f + "=" + (s.jsonFields.contains(f) ? "CAST(? AS JSON)" : "?")).collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(columns.stream().map(values::get).toList()); args.add(id);
        int changed = jdbc.update("UPDATE " + s.table + " SET " + assignments + " WHERE id=? AND is_deleted=FALSE", args.toArray());
        if (changed == 0) throw new IllegalArgumentException("记录不存在或已删除");
        audit(actor, request, "master." + type + ".update", s.table, String.valueOf(id), values);
    }

    @Transactional
    public void deactivate(String type, long id, AuthUser actor, HttpServletRequest request) {
        Spec s = getSpec(type);
        int changed = jdbc.update("UPDATE " + s.table + " SET status='INACTIVE' WHERE id=? AND is_deleted=FALSE AND status<>'INACTIVE'", id);
        if (changed == 0) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM " + s.table + " WHERE id=? AND is_deleted=FALSE", Integer.class, id);
            if (exists == null || exists == 0) throw new IllegalArgumentException("记录不存在或已删除");
        }
        audit(actor, request, "master." + type + ".deactivate", s.table, String.valueOf(id), Map.of("status", "INACTIVE"));
    }

    @Transactional
    public void softDelete(String type, long id, AuthUser actor, HttpServletRequest request) {
        Spec s = getSpec(type);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM " + s.table + " WHERE id=? AND is_deleted=FALSE", Integer.class, id);
        if (exists == null || exists == 0) throw new IllegalArgumentException("记录不存在或已删除");
        if (type.equals("materials")) {
            String code = jdbc.queryForObject("SELECT material_code FROM package_materials WHERE id=?", String.class, id);
            Integer referenced = jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE is_deleted=FALSE AND (JSON_CONTAINS(default_product_packaging_plan,JSON_OBJECT('materialCode',?),'$') OR JSON_CONTAINS(default_shipping_material_plan,JSON_OBJECT('materialCode',?),'$'))", Integer.class, code, code);
            if (referenced != null && referenced > 0) throw new IllegalArgumentException("该包材仍被 SKU 默认方案引用，请先修改相关 SKU");
        }
        jdbc.update("UPDATE " + s.table + " SET is_deleted=TRUE WHERE id=? AND is_deleted=FALSE", id);
        audit(actor, request, "master." + type + ".delete", s.table, String.valueOf(id), Map.of("isDeleted", true));
    }

    private Map<String,Object> cleanValues(Spec s, Map<String,Object> body) {
        if (body == null) throw new IllegalArgumentException("请求数据不能为空");
        Map<String,Object> values = new LinkedHashMap<>();
        for (String field : s.fields) {
            String key = camel(field);
            if (body.containsKey(key)) {
                Object value = body.get(key);
                if ((field.equals("supplier_code") || field.equals("material_code")) && value != null) value = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
                // The SKU editor submits an empty string when its optional supplier selector is cleared.
                // Treat that as SQL NULL instead of trying to parse it as a supplier id.
                if (field.equals("supplier_id") && value instanceof String text && text.isBlank()) value = null;
                if (s.jsonFields.contains(field) && value != null && !(value instanceof String)) {
                    try { value = mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("包材方案格式无效"); }
                }
                if (s.jsonFields.contains(field) && value instanceof String text && !text.isBlank()) {
                    try { mapper.readTree(text); } catch (JsonProcessingException e) { throw new IllegalArgumentException("包材方案必须是有效 JSON"); }
                }
                values.put(field, value);
            }
        }
        for (String key : body.keySet()) if (s.fields.stream().noneMatch(field -> camel(field).equals(key))) throw new IllegalArgumentException("不支持的字段：" + key);
        return values;
    }

    private void validate(Spec s, Map<String,Object> values, boolean creating) {
        if (creating) for (String field : s.required) if (blank(values.get(field))) throw new IllegalArgumentException(camel(field) + " 为必填项");
        for (String field : List.of("supplier_code", "material_code")) if (values.containsKey(field) && values.get(field) != null && !String.valueOf(values.get(field)).matches("[A-Z0-9_-]{2,64}")) throw new IllegalArgumentException(camel(field) + "需为 2-64 位大写字母、数字、短横线或下划线");
        if (values.containsKey("status") && !Set.of("ACTIVE", "INACTIVE").contains(values.get("status"))) throw new IllegalArgumentException("状态值无效");
        if (values.containsKey("platform") && !Set.of("ALIEXPRESS", "TEMU", "TIKTOK", "SHOPEE", "OTHER").contains(values.get("platform"))) throw new IllegalArgumentException("平台类型无效");
        if (s.table.equals("package_materials") && values.containsKey("category") && !Set.of("PRODUCT_PACKAGING", "SHIPPING_MATERIAL").contains(values.get("category"))) throw new IllegalArgumentException("包材分类无效");
        for (String field : List.of("purchase_price", "supply_price", "unit_price", "safety_stock", "weight", "length", "width", "height")) {
            if (values.containsKey(field) && values.get(field) != null && !String.valueOf(values.get(field)).isBlank()) {
                try { BigDecimal value = new BigDecimal(String.valueOf(values.get(field))); if (value.precision() > 18 || value.scale() > 4) throw new IllegalArgumentException(field + " 最多 18 位且小数不超过 4 位"); if (value.signum() < 0) throw new IllegalArgumentException(field + " 不能小于 0"); values.put(field, value); }
                catch (NumberFormatException e) { throw new IllegalArgumentException(field + " 必须为数字"); }
            }
        }
        if (values.containsKey("supplier_id") && values.get("supplier_id") != null) {
            long supplierId; try { supplierId = Long.parseLong(String.valueOf(values.get("supplier_id"))); } catch (NumberFormatException e) { throw new IllegalArgumentException("供应商无效"); }
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM suppliers WHERE id=? AND status='ACTIVE' AND is_deleted=FALSE", Integer.class, supplierId);
            if (exists == null || exists == 0) throw new IllegalArgumentException("供应商不存在或已停用");
            values.put("supplier_id", supplierId);
        }
        if (s.table.equals("skus")) {
            verifyPackagingPlan(values.get("default_product_packaging_plan"), "PRODUCT_PACKAGING", "默认产品包装方案");
            verifyPackagingPlan(values.get("default_shipping_material_plan"), "SHIPPING_MATERIAL", "默认发货打包材料方案");
        }
    }

    private void verifyPackagingPlan(Object raw, String expectedCategory, String label) {
        if (raw == null || String.valueOf(raw).isBlank()) return;
        try {
            var root = mapper.readTree(String.valueOf(raw));
            if (!root.isArray()) throw new IllegalArgumentException(label + "必须是 JSON 数组");
            for (var item : root) {
                if (!item.isObject() || !item.hasNonNull("materialCode")) throw new IllegalArgumentException(label + "每项需要填写 materialCode");
                String code = item.get("materialCode").asText();
                Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM package_materials WHERE material_code=? AND category=? AND status='ACTIVE' AND is_deleted=FALSE", Integer.class, code, expectedCategory);
                if (active == null || active == 0) throw new IllegalArgumentException(label + "包含未启用或分类不匹配的包材：" + code);
                if (item.has("quantity") && item.get("quantity").decimalValue().signum() <= 0) throw new IllegalArgumentException(label + "的 quantity 必须大于 0");
            }
        } catch (JsonProcessingException e) { throw new IllegalArgumentException(label + "格式无效"); }
    }

    private void applyDefaults(String type, Map<String,Object> values) {
        values.putIfAbsent("status", "ACTIVE");
        if (type.equals("shops")) values.putIfAbsent("platform", "ALIEXPRESS");
        if (type.equals("skus")) { values.putIfAbsent("purchase_price", BigDecimal.ZERO); values.putIfAbsent("supply_price", BigDecimal.ZERO); }
        if (type.equals("materials")) { values.putIfAbsent("unit_price", BigDecimal.ZERO); values.putIfAbsent("safety_stock", BigDecimal.ZERO); }
    }

    private void audit(AuthUser actor, HttpServletRequest request, String action, String resource, String id, Map<String,Object> detail) {
        try {
            jdbc.update("INSERT INTO operation_logs(user_id,username,action,resource_type,resource_id,detail,ip_address,success) VALUES (?,?,?,?,?,CAST(? AS JSON),?,TRUE)", actor.id(), actor.username(), action, resource, id, mapper.writeValueAsString(detail), request.getRemoteAddr());
        } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private Spec getSpec(String type) { Spec spec = SPECS.get(type); if (spec == null) throw new IllegalArgumentException("未知主数据类型"); return spec; }
    private static boolean blank(Object value) { return value == null || String.valueOf(value).isBlank(); }
    private static String camel(String snake) { StringBuilder out = new StringBuilder(); boolean upper=false; for (char c : snake.toCharArray()) { if (c=='_') upper=true; else { out.append(upper ? Character.toUpperCase(c) : c); upper=false; } } return out.toString(); }
}
