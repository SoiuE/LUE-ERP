package com.lue.erp.auth;

import java.util.List;

public record AuthUser(long id, String username, String displayName, List<String> roles, List<String> permissions) {}
