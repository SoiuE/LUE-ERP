package com.lue.erp.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapData implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    @Value("${lue.bootstrap.admin-username}") private String adminUsername;
    @Value("${lue.bootstrap.admin-password}") private String adminPassword;
    public BootstrapData(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    @Override public void run(String... args) {
        addRole("ADMIN", "系统管理员"); addRole("OPERATOR", "运营"); addRole("VIEWER", "只读用户"); addRole("WAREHOUSE", "仓库员工");
        permission("menu.dashboard", "工作台", "MENU", "/");
        permission("menu.users", "用户管理", "MENU", "/users");
        permission("menu.roles", "角色权限", "MENU", "/roles");
        permission("menu.logs", "操作日志", "MENU", "/logs");
        permission("button.users.manage", "用户维护", "BUTTON", null);
        permission("master.shops.view", "店铺管理", "MENU", "/shops");
        permission("master.skus.view", "SKU 中心", "MENU", "/skus");
        permission("master.suppliers.view", "供应商", "MENU", "/suppliers");
        permission("master.materials.view", "包材档案", "MENU", "/materials");
        permission("master.goods.view", "仓库货品档案", "MENU", "/warehouse-goods");
        permission("master.compositions.view", "销售 SKU 组成清单", "API", null);
        permission("button.goods.manage", "维护仓库货品", "BUTTON", null);
        permission("button.compositions.manage", "维护组成清单版本", "BUTTON", null);
        permission("warehouse.locations.view", "仓库库位档案", "MENU", "/warehouse-locations");
        permission("button.locations.manage", "维护仓库库位", "BUTTON", null);
        permission("warehouse.location_stock.view", "货品库位库存查询", "MENU", "/location-inventory");
        permission("warehouse.goods.purchase", "货品采购与确认", "API", null);
        permission("warehouse.goods.receive", "货品采购收货", "API", null);
        permission("warehouse.goods.putaway", "货品上架", "API", null);
        permission("warehouse.goods.move", "货品移库", "API", null);
        permission("warehouse.goods.outbound", "货品出库", "API", null);
        permission("master.mappings.view", "店铺 SKU 映射", "MENU", "/mappings");
        permission("menu.imports", "导入中心", "MENU", "/imports");
        permission("menu.purchases", "采购管理", "MENU", "/purchases");
        permission("menu.package-stocks", "包材库存", "MENU", "/package-stocks");
        permission("menu.mobile", "手机仓库", "MENU", "/mobile");
        permission("menu.profits", "SKU 利润报表", "MENU", "/profits");
        permission("button.profits.import", "利润数据导入", "BUTTON", null);
        permission("button.profits.recalculate", "利润手动重算", "BUTTON", null);
        permission("button.imports.manage", "执行 Excel 导入", "BUTTON", null);
        permission("master.data.manage", "维护主数据", "BUTTON", null);
        grant("ADMIN", "menu.dashboard", "menu.users", "menu.roles", "menu.logs", "button.users.manage",
            "master.shops.view", "master.skus.view", "master.suppliers.view", "master.materials.view", "master.goods.view", "master.compositions.view", "button.goods.manage", "button.compositions.manage", "master.mappings.view", "master.data.manage", "menu.imports", "button.imports.manage", "menu.purchases", "menu.package-stocks", "menu.mobile", "menu.profits", "button.profits.import", "button.profits.recalculate");
        grant("OPERATOR", "menu.dashboard", "master.shops.view", "master.skus.view", "master.suppliers.view", "master.materials.view", "master.goods.view", "master.compositions.view", "button.goods.manage", "button.compositions.manage", "master.mappings.view", "master.data.manage", "menu.imports", "button.imports.manage", "menu.purchases", "menu.package-stocks", "menu.mobile", "menu.profits", "button.profits.import", "button.profits.recalculate");
        grant("VIEWER", "menu.dashboard", "menu.profits");
        grant("WAREHOUSE", "menu.mobile");
        grant("ADMIN", "warehouse.locations.view", "button.locations.manage", "warehouse.location_stock.view");
        grant("OPERATOR", "warehouse.locations.view", "button.locations.manage", "warehouse.location_stock.view");
        grant("WAREHOUSE", "warehouse.locations.view", "warehouse.location_stock.view");
        grant("ADMIN", "warehouse.goods.purchase", "warehouse.goods.putaway", "warehouse.goods.move", "warehouse.goods.outbound");
        grant("OPERATOR", "warehouse.goods.purchase", "warehouse.goods.putaway", "warehouse.goods.move", "warehouse.goods.outbound");
        grant("ADMIN", "warehouse.goods.receive");
        grant("OPERATOR", "warehouse.goods.receive");
        grant("WAREHOUSE", "menu.purchases", "warehouse.goods.receive", "warehouse.goods.putaway", "warehouse.goods.move");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, adminUsername);
        if (count == 0) {
            jdbc.update("INSERT INTO users(username,password_hash,display_name) VALUES (?,?,?)", adminUsername, encoder.encode(adminPassword), "系统管理员");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u,roles r WHERE u.username=? AND r.code='ADMIN'", adminUsername);
        }
    }
    private void addRole(String code, String name) { jdbc.update("INSERT IGNORE INTO roles(code,name) VALUES (?,?)", code, name); }
    private void permission(String code, String name, String type, String path) { jdbc.update("INSERT IGNORE INTO permissions(code,name,type,path) VALUES (?,?,?,?)", code, name, type, path); }
    private void grant(String role, String... permissions) {
        for (String permission : permissions) jdbc.update("INSERT IGNORE INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r,permissions p WHERE r.code=? AND p.code=?", role, permission);
    }
}
